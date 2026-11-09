package dev.diorama.render;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;
import org.jspecify.annotations.Nullable;

/**
 * The map's fog painted on the CPU, for exported images: fog.fsh line for line, still (no drift
 * with time), over a known-ground mask built from the saved tiles the way {@link MapViewport}
 * builds its own. An exported pixel stands for a screen pixel at GUI scale 2, which picks the fog's
 * noise scale the way the zoom does on screen.
 */
public final class ExportFog implements ImageExport.Finish {
	private static final int NOISE = FogTextures.NOISE_SIZE;
	private static final float NOISE_PERIOD = 512;
	private static final float COS_PITCH = 0.643F;
	private static final int LAYERS = 8;
	private static final float FOG_HEIGHT = 10;
	private static final float BLUR_UNITS = 7;
	private static final float NO_FOG_KNOWN = 0.95F;
	private static final float LOWEST = 0.12F;
	/** The sun shading of fog without clouds: smoke's. */
	private static final float FOG_SHADOW = 0.7F;
	// fog.fsh's clouds; change both together.
	private static final float CLOUD_CELL = (float) VanillaClouds.CELL;
	private static final float CLOUD_PIXELS = 40;
	private static final int CLOUD_SIZES = 5;
	private static final float CLOUD_ROUND = 0.2F;
	private static final float CLOUD_THICK = 0.55F;
	private static final float[] CLOUD_TOP = {0.85F, 0.865F, 0.905F};
	private static final float[] CLOUD_SIDE_HIGH = {0.72F, 0.75F, 0.84F};
	private static final float[] CLOUD_SIDE_LOW = {0.55F, 0.59F, 0.72F};
	private static final float[] CLOUD_SHADED = {0.70F, 0.73F, 0.83F};
	private static final float SUN_U = -0.529999F;
	private static final float SUN_V = -0.847998F;
	/**
	 * The most mask texels a side. Bigger exports get coarser texels, as the map's own fog does when
	 * zoomed far out, so the mask stays tens of megabytes however big the image is.
	 */
	private static final int MAX_MASK = 2048;

	private static float[] noiseR;
	private static float[] noiseG;
	private static float[] noiseB;
	private static float[] noiseA;

	private final int lod;
	/** Map units per export pixel. */
	private final double unitsPerPixel;
	/** The mask: known share per texel of {@link #texel} map units, from {@link #originU}, {@link #originV}. */
	private final float[] known;
	/** How tall the clouds stand over each texel, 0 to 1, as the mask's alpha on screen. */
	private final float[] lift;
	private final boolean clouds;
	/** Per mask texel: 1 where everything the shader reads is fully known, 2 where fully unknown. */
	private final byte[] flags;
	private final int maskW;
	private final int maskH;
	private final double originU;
	private final double originV;
	private final float texel;
	private final float fogScale;
	private final int scaleSteps;
	private final float scaleFraction;
	private final float[] lit;
	private final float[] shade;
	private final int background;
	private final float[] moonlit;
	/** The clouds' pattern, read on the render thread (MapExport). */
	private VanillaClouds.@Nullable Pattern pattern;
	/** The signed distance from explored land per mask texel, map units, positive over the unexplored. */
	private float[] signed;
	/** As fog.fsh: the base cell, the grid's cells in base cells, the two sizes drawn and how far the first has grown into the second. */
	private float cloudBase;
	private int gridMult;
	private int cloudSize;
	private float cloudGrow;
	private float bigShare;
	/** Map units a screen pixel stands for. */
	private float pixelUnits;
	/** The codes of the cells at the two sizes drawn, over the mask, from cell (codeU[i], codeV[i]), codeW[i] a row. */
	private final int[][] codes = new int[2][];
	private final long[] codeU = new long[2];
	private final long[] codeV = new long[2];
	private final int[] codeW = new int[2];
	private final int[] codeH = new int[2];

	private ExportFog(int lod, float[] known, int maskW, int maskH, double originU, double originV, float texel, int steps, float fraction, boolean smoke, float night) {
		this.lod = lod;
		this.unitsPerPixel = (double) (1 << lod) / Projection.PIXELS_PER_UNIT;
		this.known = known;
		this.lift = new float[known.length];
		this.clouds = !smoke;
		this.flags = new byte[known.length];
		this.maskW = maskW;
		this.maskH = maskH;
		this.originU = originU;
		this.originV = originV;
		this.texel = texel;
		this.scaleSteps = steps;
		this.scaleFraction = fraction;
		this.fogScale = (float) Math.pow(2, steps + fraction);
		this.lit = smoke ? new float[] {0.26F, 0.22F, 0.21F} : new float[] {0.97F, 0.97F, 0.99F};
		this.shade = smoke ? new float[] {0.07F, 0.055F, 0.055F} : new float[] {0.48F, 0.55F, 0.67F};
		// What the map draws behind its tiles, dimmed at night as the map dims it.
		this.background = NightLook.darken(smoke ? 0xFF29211F : 0xFF8C99AB, night) & 0xFFFFFF;
		// The fog's colour at night, as fog.fsh's moonlit.
		this.moonlit = NightLook.darken(new float[] {1, 1, 1}, night);
	}

	/**
	 * Reads the saved tiles under {@code area} and a margin around it into the fog's mask.
	 * {@code smoke} for the sky view under a roof, which has smoke instead of clouds.
	 */
	public static ExportFog build(TileStore store, ImageExport.Area area, boolean smoke) {
		return build(store, area, smoke, 0, smoke ? null : VanillaClouds.prepare());
	}

	/** The same, dimmed {@code night} into the night as the map draws it ({@link NightLook}). */
	public static ExportFog build(TileStore store, ImageExport.Area area, boolean smoke, float night, VanillaClouds.@Nullable Pattern clouds) {
		loadNoise();
		int lod = area.lod();
		double pixelsPerUnit = Projection.PIXELS_PER_UNIT / (double) (1 << lod);
		// As if on screen at GUI scale 2: the zoom is GUI pixels per map unit.
		double zoom = pixelsPerUnit / 2;
		double scaleLog = Math.min(7.99, Math.log(Math.max(1, MapViewport.DETAILED_ZOOM / zoom)) / Math.log(2));
		int steps = (int) Math.floor(scaleLog);
		float fraction = (float) Math.floor((scaleLog - steps) * 16) / 16;
		// About four export pixels per texel, as on screen, and never under the 2 units the screen stops at.
		float texel = (float) Math.max(2, 1 << lod);
		double margin = 64 + 24 * Math.pow(2, steps + 1);
		// The clouds read cells and distances a few of the biggest cells drawn away: read that far, or
		// the clouds at the picture's edge depend on where it's cut.
		if (!smoke) {
			margin += 4 * CLOUD_CELL * Math.pow(2, steps + 3);
		}
		double span = Math.max(area.width(), area.height()) / pixelsPerUnit + 2 * margin;
		while (span / texel > MAX_MASK) {
			texel *= 2;
		}
		double u0 = area.x() / pixelsPerUnit - margin;
		double v0 = area.y() / pixelsPerUnit - margin;
		double u1 = (area.x() + area.width()) / pixelsPerUnit + margin;
		double v1 = (area.y() + area.height()) / pixelsPerUnit + margin;
		double originU = Math.floor(u0 / texel) * texel;
		double originV = Math.floor(v0 / texel) * texel;
		int maskW = (int) Math.ceil((u1 - originU) / texel);
		int maskH = (int) Math.ceil((v1 - originV) / texel);

		// Known share per texel: the mean alpha of the tile pixels in it.
		float[] sum = new float[maskW * maskH];
		int texelPixels = (int) Math.round(texel * pixelsPerUnit);
		long px0 = Math.round(originU * pixelsPerUnit);
		long py0 = Math.round(originV * pixelsPerUnit);
		long px1 = px0 + (long) maskW * texelPixels;
		long py1 = py0 + (long) maskH * texelPixels;
		int tile = Projection.TILE_PIXELS;
		for (long tv = Math.floorDiv(py0, tile); tv <= Math.floorDiv(py1 - 1, tile); tv++) {
			for (long tu = Math.floorDiv(px0, tile); tu <= Math.floorDiv(px1 - 1, tile); tu++) {
				int[] pixels = store.read(new TileKey(TileKey.SURFACE, (int) tu, (int) tv, lod));
				if (pixels == null) {
					continue;
				}
				for (int y = 0; y < tile; y++) {
					long gy = tv * tile + y;
					if (gy < py0 || gy >= py1) {
						continue;
					}
					int my = (int) ((gy - py0) / texelPixels);
					for (int x = 0; x < tile; x++) {
						long gx = tu * tile + x;
						if (gx < px0 || gx >= px1) {
							continue;
						}
						sum[my * maskW + (int) ((gx - px0) / texelPixels)] += (pixels[y * tile + x] >>> 24) / 255F;
					}
				}
			}
		}
		float perTexel = texelPixels * texelPixels;
		for (int i = 0; i < sum.length; i++) {
			// Kept to bytes, like the mask texture.
			sum[i] = Math.round(Math.min(1, sum[i] / perTexel) * 255) / 255F;
		}
		ExportFog fog = new ExportFog(lod, sum, maskW, maskH, originU, originV, texel, steps, fraction, smoke, night);
		if (MapStyle.get().roundedFog() && texel < 16) {
			fog.roundOff(Math.max(1, Math.round(10 / texel)));
		}
		if (fog.clouds) {
			fog.pattern = clouds != null ? clouds : VanillaClouds.prepare();
			fog.cloudSetup(pixelsPerUnit);
		} else {
			java.util.Arrays.fill(fog.lift, 1);
		}
		fog.markReach();
		return fog;
	}

	private static synchronized void loadNoise() {
		if (noiseR != null) {
			return;
		}
		int[] pixels = FogTextures.noisePixels();
		float[] r = new float[pixels.length];
		float[] g = new float[pixels.length];
		float[] b = new float[pixels.length];
		float[] a = new float[pixels.length];
		for (int i = 0; i < pixels.length; i++) {
			r[i] = (pixels[i] & 0xFF) / 255F;
			g[i] = ((pixels[i] >> 8) & 0xFF) / 255F;
			b[i] = ((pixels[i] >> 16) & 0xFF) / 255F;
			a[i] = (pixels[i] >>> 24) / 255F;
		}
		noiseR = r;
		noiseG = g;
		noiseB = b;
		noiseA = a;
	}

	/** {@link MapViewport}'s rounding of the known area, so the edge follows a smooth line. */
	private void roundOff(int radius) {
		float[] a = this.known.clone();
		float[] b = new float[a.length];
		boxBlur(a, b, radius, true);
		boxBlur(b, a, radius, false);
		boxBlur(a, b, radius, true);
		boxBlur(b, a, radius, false);
		for (int i = 0; i < a.length; i++) {
			float t = Math.max(0, Math.min(1, (a[i] - 0.5F) / 0.35F));
			float rounded = t * t * (3 - 2 * t);
			this.known[i] = Math.min(this.known[i], Math.round(rounded * 255) / 255F);
		}
	}

	private void boxBlur(float[] from, float[] to, int radius, boolean horizontal) {
		int lines = horizontal ? this.maskH : this.maskW;
		int length = horizontal ? this.maskW : this.maskH;
		float scale = 1.0F / (2 * radius + 1);
		for (int line = 0; line < lines; line++) {
			float sum = 0;
			for (int k = -radius; k <= radius; k++) {
				sum += from[this.index(line, Math.max(0, Math.min(length - 1, k)), horizontal)];
			}
			for (int k = 0; k < length; k++) {
				to[this.index(line, k, horizontal)] = sum * scale;
				int add = Math.min(length - 1, k + radius + 1);
				int drop = Math.max(0, k - radius);
				sum += from[this.index(line, add, horizontal)] - from[this.index(line, drop, horizontal)];
			}
		}
	}

	private int index(int line, int k, boolean horizontal) {
		return horizontal ? line * this.maskW + k : k * this.maskW + line;
	}

	/**
	 * Marks texels where everything the fog can read from a pixel in them is fully known, or
	 * fully unknown, so those pixels skip work whose answer is known; see
	 * {@code MapViewport.markReach}.
	 */
	private void markReach() {
		double blur = 1.9 * Math.max(BLUR_UNITS * 2.4 / this.texel, 1);
		double layerShift = FOG_HEIGHT * COS_PITCH / this.texel;
		double sun = 9.0 * (1 << (this.scaleSteps + 1)) / this.texel;
		double slack = 1.5;
		// Only smoke reads them: the clouds have shortcuts of their own.
		double around = 0;
		double lean = 0;
		int west = (int) Math.ceil(Math.max(Math.max(blur, sun), around) + slack);
		int east = (int) Math.ceil(Math.max(blur, around) + slack);
		int north = (int) Math.ceil(Math.max(Math.max(blur, sun * 0.4), around) + slack);
		int south = (int) Math.ceil(Math.max(blur + layerShift, around + lean) + slack);
		int w = this.maskW;
		int h = this.maskH;
		int row = w + 1;
		int[] notKnown = new int[(w + 1) * (h + 1)];
		int[] notUnknown = new int[(w + 1) * (h + 1)];
		for (int j = 0; j < h; j++) {
			int lineNotKnown = 0;
			int lineNotUnknown = 0;
			for (int i = 0; i < w; i++) {
				float k = this.known[j * w + i];
				lineNotKnown += k >= 1 ? 0 : 1;
				// Plain: never explored and the clouds at full height, as the screen's deep flag.
				lineNotUnknown += k <= 0 && this.lift[j * w + i] >= 1 ? 0 : 1;
				notKnown[(j + 1) * row + i + 1] = notKnown[j * row + i + 1] + lineNotKnown;
				notUnknown[(j + 1) * row + i + 1] = notUnknown[j * row + i + 1] + lineNotUnknown;
			}
		}
		for (int j = 0; j < h; j++) {
			int top = Math.max(0, j - north);
			int bottom = Math.min(h - 1, j + south) + 1;
			for (int i = 0; i < w; i++) {
				int left = Math.max(0, i - west);
				int right = Math.min(w - 1, i + east) + 1;
				int a = bottom * row + right;
				int b = top * row + right;
				int c = bottom * row + left;
				int d = top * row + left;
				if (notKnown[a] - notKnown[b] - notKnown[c] + notKnown[d] == 0) {
					this.flags[j * w + i] = 1;
				} else if (notUnknown[a] - notUnknown[b] - notUnknown[c] + notUnknown[d] == 0) {
					this.flags[j * w + i] = 2;
				}
			}
		}
	}

	@Override
	public void row(int[] abgr, long y, long x0) {
		Pixel p = new Pixel();
		for (int i = 0; i < abgr.length; i++) {
			abgr[i] = this.paint(abgr[i], x0 + i, y, p);
		}
	}

	/** Scratch state for one pixel, like the shader's globals. */
	private static final class Pixel {
		boolean clear;
		boolean deep;
	}

	/** One export pixel {@code (x, y)}: the tile pixel over the backdrop, with the fog over that. */
	private int paint(int abgr, long x, long y, Pixel p) {
		double u = (x + 0.5) * this.unitsPerPixel;
		double v = (y + 0.5) * this.unitsPerPixel;
		if (this.clouds) {
			return this.paintClouds(abgr, u, v);
		}
		float liftHere = this.sampleLift(u, v);
		int ti = (int) Math.floor((u - this.originU) / this.texel);
		int tj = (int) Math.floor((v - this.originV) / this.texel);
		byte flag = ti < 0 || tj < 0 || ti >= this.maskW || tj >= this.maskH ? 0 : this.flags[tj * this.maskW + ti];
		p.clear = flag == 1;
		p.deep = flag == 2;

		float jitter = hash(x, y) - 0.5F;
		float r = 0;
		float g = 0;
		float b = 0;
		float alpha = 0;
		float toSunU = -9 * this.fogScale;
		float toSunV = -3.6F * this.fogScale;
		for (int i = 0; i < LAYERS; i++) {
			float h = FOG_HEIGHT * (1 - (i + 0.5F + jitter * 0.6F) / LAYERS);
			double gv = v + h * COS_PITCH;
			float th = this.thickness(u, gv, p);
			float a = smoothstep(h - 1.4F, h + 1.4F, th);
			if (a <= 0.002F) {
				continue;
			}
			double probeU = u + toSunU;
			double probeV = gv + toSunV;
			float toward = this.thicknessAt(probeU, probeV, p.clear ? 1 : this.knownAt(probeU, probeV, p), p);
			float occlusion = clamp((toward - th) / FOG_HEIGHT * 2.2F);
			float light = clamp(0.2F + 0.7F * (h / FOG_HEIGHT) + 0.3F - occlusion * FOG_SHADOW);
			float layerAlpha = a * 0.45F;
			r += (1 - alpha) * layerAlpha * mix(this.shade[0], this.lit[0], light);
			g += (1 - alpha) * layerAlpha * mix(this.shade[1], this.lit[1], light);
			b += (1 - alpha) * layerAlpha * mix(this.shade[2], this.lit[2], light);
			alpha += (1 - alpha) * layerAlpha;
			if (alpha > 0.985F) {
				break;
			}
		}

		float soft = this.knownSoft(u, v, p);
		float floorFog = smoothstep(0.5F, 2.5F, this.thicknessAt(u, v, soft, p)) * smoothstep(0.3F, 0.9F, liftHere);
		r += (1 - alpha) * floorFog * this.shade[0];
		g += (1 - alpha) * floorFog * this.shade[1];
		b += (1 - alpha) * floorFog * this.shade[2];
		alpha += (1 - alpha) * floorFog;

		// Only the alpha, as the shader does: the colour it divides by alpha later stays as it was.
		alpha *= 1 - 0.85F * smoothstep(0.45F, 0.95F, soft);

		float empty = (1 - smoothstep(0.3F, 0.85F, this.knownAt(u, v, p))) * smoothstep(0.7F, 1, liftHere);
		float coverR;
		float coverG;
		float coverB;
		if (alpha > 0.02F) {
			coverR = r / alpha;
			coverG = g / alpha;
			coverB = b / alpha;
		} else {
			coverR = mix(this.shade[0], this.lit[0], 0.7F);
			coverG = mix(this.shade[1], this.lit[1], 0.7F);
			coverB = mix(this.shade[2], this.lit[2], 0.7F);
		}
		r += (1 - alpha) * empty * coverR;
		g += (1 - alpha) * empty * coverG;
		b += (1 - alpha) * empty * coverB;
		alpha += (1 - alpha) * empty;

		r *= this.moonlit[0];
		g *= this.moonlit[1];
		b *= this.moonlit[2];

		// Under the fog: the tile over the map's backdrop.
		float ta = (abgr >>> 24) / 255F;
		float gr = ((abgr & 0xFF) / 255F) * ta + ((this.background >> 16) & 0xFF) / 255F * (1 - ta);
		float gg = (((abgr >> 8) & 0xFF) / 255F) * ta + ((this.background >> 8) & 0xFF) / 255F * (1 - ta);
		float gb = (((abgr >> 16) & 0xFF) / 255F) * ta + (this.background & 0xFF) / 255F * (1 - ta);
		if (alpha <= 0.002F) {
			return 0xFF000000 | channel(gb) << 16 | channel(gg) << 8 | channel(gr);
		}
		// The shader's grain, then its colour, which the render target clamps, blended over the ground.
		float grain = (hash(y, x) - 0.5F) / 255F;
		float or = clamp((r + grain) / alpha) * alpha + gr * (1 - alpha);
		float og = clamp((g + grain) / alpha) * alpha + gg * (1 - alpha);
		float ob = clamp((b + grain) / alpha) * alpha + gb * (1 - alpha);
		return 0xFF000000 | channel(ob) << 16 | channel(og) << 8 | channel(or);
	}

	private float knownAt(double u, double v, Pixel p) {
		return p.deep ? 0 : this.sample(u, v);
	}

	/** The mask at a point, filtered linearly and clamped at the edge, like the texture. */
	private float sample(double u, double v) {
		double fx = (u - this.originU) / this.texel - 0.5;
		double fy = (v - this.originV) / this.texel - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		float tx = (float) (fx - x0);
		float ty = (float) (fy - y0);
		int xa = Math.max(0, Math.min(this.maskW - 1, x0));
		int xb = Math.max(0, Math.min(this.maskW - 1, x0 + 1));
		int ya = Math.max(0, Math.min(this.maskH - 1, y0));
		int yb = Math.max(0, Math.min(this.maskH - 1, y0 + 1));
		float top = mix(this.known[ya * this.maskW + xa], this.known[ya * this.maskW + xb], tx);
		float bottom = mix(this.known[yb * this.maskW + xa], this.known[yb * this.maskW + xb], tx);
		return mix(top, bottom, ty);
	}

	/** The clouds' climb at a point, filtered like the mask's alpha. */
	private float sampleLift(double u, double v) {
		double fx = (u - this.originU) / this.texel - 0.5;
		double fy = (v - this.originV) / this.texel - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		float tx = (float) (fx - x0);
		float ty = (float) (fy - y0);
		int xa = Math.max(0, Math.min(this.maskW - 1, x0));
		int xb = Math.max(0, Math.min(this.maskW - 1, x0 + 1));
		int ya = Math.max(0, Math.min(this.maskH - 1, y0));
		int yb = Math.max(0, Math.min(this.maskH - 1, y0 + 1));
		float top = mix(this.lift[ya * this.maskW + xa], this.lift[ya * this.maskW + xb], tx);
		float bottom = mix(this.lift[yb * this.maskW + xa], this.lift[yb * this.maskW + xb], tx);
		return mix(top, bottom, ty);
	}

	/**
	 * Works out what the clouds need for the export's zoom, as fog.fsh does per pixel: the cell
	 * sizes drawn (an export pixel is a screen pixel at GUI scale 2), the signed distance from
	 * explored land, as MapViewport.cloudDistances without a remembered map, and the cells' codes.
	 */
	private void cloudSetup(double pixelsPerUnit) {
		this.pixelUnits = (float) (1 / pixelsPerUnit);
		this.cloudBase = CLOUD_CELL * (1 << this.scaleSteps);
		this.gridMult = this.scaleFraction >= 0.58F ? 2 : 1;
		float size = (float) (Math.log(CLOUD_PIXELS * this.pixelUnits / this.cloudBase) / Math.log(2));
		size = Math.max(0, Math.min(CLOUD_SIZES - 1, size));
		this.cloudSize = Math.min((int) Math.floor(size), CLOUD_SIZES - 1);
		this.cloudGrow = this.cloudSize >= CLOUD_SIZES - 1 ? 0 : smoothstep(0.2F, 0.8F, size - this.cloudSize);
		if (this.cloudGrow >= 1) {
			this.cloudSize++;
			this.cloudGrow = 0;
		}
		this.bigShare = smoothstep(-0.6F, 1, (float) (Math.log(CLOUD_PIXELS * this.pixelUnits / CLOUD_CELL) / Math.log(2)));

		int w = this.maskW;
		int h = this.maskH;
		int[] toKnown = new int[w * h];
		int[] toUnknown = new int[w * h];
		for (int k = 0; k < w * h; k++) {
			boolean known = this.known[k] >= 0.5F;
			toKnown[k] = known ? 0 : Integer.MAX_VALUE / 2;
			toUnknown[k] = known ? Integer.MAX_VALUE / 2 : 0;
		}
		chamfer(toKnown, w, h);
		chamfer(toUnknown, w, h);
		float far = Math.max(w, h) * 3;
		float[] s = new float[w * h];
		for (int k = 0; k < w * h; k++) {
			s[k] = (Math.min(toKnown[k], far) - Math.min(toUnknown[k], far)) / 3F * this.texel;
		}
		float[] t = new float[w * h];
		// A texel either way, as MapViewport.cloudDistances.
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				float sum = 0;
				for (int d = -1; d <= 1; d++) {
					sum += s[j * w + Math.max(0, Math.min(w - 1, i + d))];
				}
				t[j * w + i] = sum / 3;
			}
		}
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				float sum = 0;
				for (int d = -1; d <= 1; d++) {
					sum += t[Math.max(0, Math.min(h - 1, j + d)) * w + i];
				}
				s[j * w + i] = sum / 3;
			}
		}
		// The cells are worked out from the distance (MapViewport.cloudCells).
		this.signed = s;

		for (int n = 0; n < 2; n++) {
			int level = Math.min(this.cloudSize + n, CLOUD_SIZES - 1);
			double cell = this.cloudBase * (1 << level);
			long u0 = (long) Math.floor(this.originU / cell) - 1;
			long v0 = (long) Math.floor(this.originV / cell) - 1;
			int cw = (int) Math.ceil(w * this.texel / cell) + 3;
			int ch = (int) Math.ceil(h * this.texel / cell) + 3;
			int[] c = new int[cw * ch];
			for (int j = 0; j < ch; j++) {
				for (int i = 0; i < cw; i++) {
					long cu = u0 + i;
					long cv = v0 + j;
					c[j * cw + i] = VanillaClouds.code(this.pattern, this.signedAt((cu + 0.5) * cell, (cv + 0.5) * cell), cu, cv, this.scaleSteps + level);
				}
			}
			this.codes[n] = c;
			this.codeU[n] = u0;
			this.codeV[n] = v0;
			this.codeW[n] = cw;
			this.codeH[n] = ch;
		}
	}

	/** Distances in thirds of a texel to the nearest 0, by the 3-4 chamfer, over a w by h grid. */
	private static void chamfer(int[] d, int w, int h) {
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int k = j * w + i;
				int v = d[k];
				if (i > 0) {
					v = Math.min(v, d[k - 1] + 3);
				}
				if (j > 0) {
					v = Math.min(v, d[k - w] + 3);
					if (i > 0) {
						v = Math.min(v, d[k - w - 1] + 4);
					}
					if (i < w - 1) {
						v = Math.min(v, d[k - w + 1] + 4);
					}
				}
				d[k] = v;
			}
		}
		for (int j = h - 1; j >= 0; j--) {
			for (int i = w - 1; i >= 0; i--) {
				int k = j * w + i;
				int v = d[k];
				if (i < w - 1) {
					v = Math.min(v, d[k + 1] + 3);
				}
				if (j < h - 1) {
					v = Math.min(v, d[k + w] + 3);
					if (i < w - 1) {
						v = Math.min(v, d[k + w + 1] + 4);
					}
					if (i > 0) {
						v = Math.min(v, d[k + w - 1] + 4);
					}
				}
				d[k] = v;
			}
		}
	}

	/** The signed distance at a point, filtered linearly and clamped at the edge, like the texture. */
	private float signedAt(double u, double v) {
		double fx = (u - this.originU) / this.texel - 0.5;
		double fy = (v - this.originV) / this.texel - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		float tx = (float) Math.max(0, Math.min(1, fx - x0));
		float ty = (float) Math.max(0, Math.min(1, fy - y0));
		int xa = Math.max(0, Math.min(this.maskW - 1, x0));
		int xb = Math.max(0, Math.min(this.maskW - 1, x0 + 1));
		int ya = Math.max(0, Math.min(this.maskH - 1, y0));
		int yb = Math.max(0, Math.min(this.maskH - 1, y0 + 1));
		float top = mix(this.signed[ya * this.maskW + xa], this.signed[ya * this.maskW + xb], tx);
		float bottom = mix(this.signed[yb * this.maskW + xa], this.signed[yb * this.maskW + xb], tx);
		return mix(top, bottom, ty);
	}

	/** fog.fsh's cloudRaw: how much of a point is under a layer at one of the two sizes drawn, 0.5 the edge. */
	private float cloudRaw(double u, double v, int n, int bit) {
		int level = Math.min(this.cloudSize + n, CLOUD_SIZES - 1);
		double size = this.cloudBase * (1 << level);
		double gu = u / size - 0.5;
		double gv = v / size - 0.5;
		long iu = (long) Math.floor(gu);
		long iv = (long) Math.floor(gv);
		float fu = smoothstep(0.5F - CLOUD_ROUND, 0.5F + CLOUD_ROUND, (float) (gu - iu));
		float fv = smoothstep(0.5F - CLOUD_ROUND, 0.5F + CLOUD_ROUND, (float) (gv - iv));
		float a = this.cell(n, iu, iv) / bit % 2;
		float b = this.cell(n, iu + 1, iv) / bit % 2;
		float c = this.cell(n, iu, iv + 1) / bit % 2;
		float d = this.cell(n, iu + 1, iv + 1) / bit % 2;
		return mix(mix(a, b, fu), mix(c, d, fu), fv);
	}

	private int cell(int n, long u, long v) {
		int i = (int) Math.max(0, Math.min(this.codeW[n] - 1, u - this.codeU[n]));
		int j = (int) Math.max(0, Math.min(this.codeH[n] - 1, v - this.codeV[n]));
		return this.codes[n][j * this.codeW[n] + i];
	}

	private float cloudShape(double u, double v, int bit) {
		float a = this.cloudRaw(u, v, 0, bit);
		return this.cloudGrow > 0 ? mix(a, this.cloudRaw(u, v, 1, bit), this.cloudGrow) : a;
	}

	private float cloudCover(double u, double v, int bit) {
		float aa = (float) Math.max(0.01, Math.min(0.5, this.pixelUnits / (this.cloudBase * Math.pow(2, this.cloudSize + this.cloudGrow))));
		return smoothstep(0.48F - aa, 0.52F + aa, this.cloudShape(u, v, bit));
	}

	/** fog.fsh's bigHeight. */
	private float bigHeight(double u, double v, double wrap) {
		float fine = noise(noiseR, u * 2 / wrap + 0.41, v * 2 / wrap + 0.27);
		float coarse = noise(noiseR, u / wrap + 0.41, v / wrap + 0.27);
		return mix(fine, coarse, this.scaleFraction);
	}

	/** fog.fsh's surface path for one export pixel: the clouds and the cover, over the tile and the backdrop. */
	private int paintClouds(int abgr, double u, double v) {
		float ta = (abgr >>> 24) / 255F;
		float gr = ((abgr & 0xFF) / 255F) * ta + ((this.background >> 16) & 0xFF) / 255F * (1 - ta);
		float gg = (((abgr >> 8) & 0xFF) / 255F) * ta + ((this.background >> 8) & 0xFF) / 255F * (1 - ta);
		float gb = (((abgr >> 16) & 0xFF) / 255F) * ta + (this.background & 0xFF) / 255F * (1 - ta);
		// Explored land is never under the clouds: they are cut at its edge, the blend across it over
		// the unexplored side. Ground with no map at all is never seen.
		float cover = 1 - smoothstep(0.05F, 0.5F, this.sample(u, v));
		if (cover <= 0) {
			return 0xFF000000 | channel(gb) << 16 | channel(gg) << 8 | channel(gr);
		}
		float big = 1;
		if (this.bigShare > 0) {
			double wrap = NOISE_PERIOD * Math.pow(2, this.scaleSteps + 1);
			double e = wrap / 48;
			float h0 = this.bigHeight(u, v, wrap);
			float nx = -(this.bigHeight(u + e, v, wrap) - h0) * 9;
			float ny = -(this.bigHeight(u, v + e, wrap) - h0) * 9;
			float len = (float) Math.sqrt(nx * nx + ny * ny + 1);
			float sl = (float) Math.sqrt(0.45 * 0.45 + 0.55 * 0.55 + 0.7 * 0.7);
			float dot = (nx * -0.45F + ny * -0.55F + 0.7F) / (len * sl);
			float light = clamp(dot * 1.1F - 0.2F + 0.8F * (h0 - 0.5F));
			big = mix(1, mix(0.92F, 1.03F, light), this.bigShare);
		}
		float size = (float) (this.cloudBase * Math.pow(2, this.cloudSize + this.cloudGrow));
		float thick = size * CLOUD_THICK;
		boolean tall = thick * COS_PITCH / this.pixelUnits >= 10;
		float r = 0;
		float g = 0;
		float b = 0;
		float through = 1;
		for (int k = 3; k >= 1; k--) {
			int bit = k == 3 ? 4 : k == 2 ? 2 : 1;
			float top = k * thick;
			double pv = v + top * COS_PITCH;
			float face = this.cloudCover(u, pv, bit);
			float w1 = face;
			float w2 = face;
			float w3 = face;
			if (face < 0.999F) {
				w1 = this.cloudCover(u, v + (top - (tall ? 0.33F : 0.5F) * thick) * COS_PITCH, bit);
				w2 = tall ? this.cloudCover(u, v + (top - 0.66F * thick) * COS_PITCH, bit) : w1;
				w3 = this.cloudCover(u, v + (top - 0.97F * thick) * COS_PITCH, bit);
			}
			float a1 = Math.max(w1 - face, 0);
			float a2 = Math.max(w2 - Math.max(face, w1), 0);
			float a3 = Math.max(w3 - Math.max(Math.max(face, w1), w2), 0);
			float a = Math.min(face + a1 + a2 + a3, 1);
			if (a <= 0.001F) {
				continue;
			}
			float shaded = 0;
			if (k < 3 && face > 0.001F) {
				for (int up = k + 1; up <= 3; up++) {
					float lift = (up - k) * thick;
					shaded = Math.max(shaded, this.cloudShape(u + SUN_U * lift * 1.2, pv + lift * COS_PITCH + SUN_V * lift * 1.2, up == 3 ? 4 : 2));
				}
				shaded = smoothstep(0.15F, 0.85F, shaded) * (1 - this.cloudCover(u, v + (top + thick) * COS_PITCH, k == 2 ? 4 : 2));
			}
			float sum = Math.max(face + a1 + a2 + a3, 1e-4F);
			float sideBig = mix(1, big, 0.5F);
			float[] color = new float[3];
			for (int c = 0; c < 3; c++) {
				float topColor = mix(CLOUD_TOP[c], CLOUD_SHADED[c], 0.8F * shaded) * big;
				float sides = a1 * mix(CLOUD_SIDE_LOW[c], CLOUD_SIDE_HIGH[c], 0.85F) + a2 * mix(CLOUD_SIDE_LOW[c], CLOUD_SIDE_HIGH[c], 0.5F) + a3 * mix(CLOUD_SIDE_LOW[c], CLOUD_SIDE_HIGH[c], 0.15F);
				color[c] = (face * topColor + sides * sideBig) / sum;
			}
			r += through * a * color[0];
			g += through * a * color[1];
			b += through * a * color[2];
			through *= 1 - a;
			if (through < 0.01F) {
				break;
			}
		}
		r *= cover;
		g *= cover;
		b *= cover;
		through = 1 - (1 - through) * cover;
		r += through * cover * CLOUD_TOP[0];
		g += through * cover * CLOUD_TOP[1];
		b += through * cover * CLOUD_TOP[2];
		through *= 1 - cover;
		float alpha = 1 - through;
		if (alpha <= 0.002F) {
			return 0xFF000000 | channel(gb) << 16 | channel(gg) << 8 | channel(gr);
		}
		float or = clamp(r / alpha * this.moonlit[0]) * alpha + gr * (1 - alpha);
		float og = clamp(g / alpha * this.moonlit[1]) * alpha + gg * (1 - alpha);
		float ob = clamp(b / alpha * this.moonlit[2]) * alpha + gb * (1 - alpha);
		return 0xFF000000 | channel(ob) << 16 | channel(og) << 8 | channel(or);
	}

	private float knownBlur(double u, double v, float units) {
		float r = Math.max(units, this.texel);
		float k = this.sample(u, v) * 0.2F;
		k += this.sample(u + r, v) * 0.1F;
		k += this.sample(u - r, v) * 0.1F;
		k += this.sample(u, v + r) * 0.1F;
		k += this.sample(u, v - r) * 0.1F;
		float d = r * 1.9F;
		k += this.sample(u + d, v + d) * 0.1F;
		k += this.sample(u - d, v + d) * 0.1F;
		k += this.sample(u + d, v - d) * 0.1F;
		k += this.sample(u - d, v - d) * 0.1F;
		return k;
	}

	private float knownSoft(double u, double v, Pixel p) {
		if (p.clear) {
			return 1;
		}
		if (p.deep) {
			return 0;
		}
		return 0.5F * this.knownBlur(u, v, BLUR_UNITS) + 0.5F * this.knownBlur(u, v, BLUR_UNITS * 2.4F);
	}

	private static float noiseLayer(double nu, double nv) {
		return noise(noiseR, nu, nv) * 0.55F + noise(noiseG, nu, nv) * 0.3F + noise(noiseB, nu, nv) * 0.15F;
	}

	/** One channel of the noise texture, repeating and filtered linearly. */
	private static float noise(float[] channel, double nu, double nv) {
		double fx = nu * NOISE - 0.5;
		double fy = nv * NOISE - 0.5;
		long x0 = (long) Math.floor(fx);
		long y0 = (long) Math.floor(fy);
		float tx = (float) (fx - x0);
		float ty = (float) (fy - y0);
		int xa = (int) Math.floorMod(x0, NOISE);
		int xb = (int) Math.floorMod(x0 + 1, NOISE);
		int ya = (int) Math.floorMod(y0, NOISE);
		int yb = (int) Math.floorMod(y0 + 1, NOISE);
		float top = mix(channel[ya * NOISE + xa], channel[ya * NOISE + xb], tx);
		float bottom = mix(channel[yb * NOISE + xa], channel[yb * NOISE + xb], tx);
		return mix(top, bottom, ty);
	}

	private float noiseAt(double u, double v) {
		// The noise sits still on the world: on screen its origin snaps to eighths of this period.
		double coarsePeriod = NOISE_PERIOD * Math.pow(2, this.scaleSteps + 1);
		double cu = u / coarsePeriod;
		double cv = v / coarsePeriod;
		float fine = noiseLayer(cu * 2, cv * 2);
		if (this.scaleFraction == 0) {
			return fine;
		}
		return mix(fine, noiseLayer(cu, cv), this.scaleFraction);
	}

	private static float thicknessFrom(float known, float n, float lift) {
		float unknown = 1 - known;
		float edge = smoothstep(0, 0.35F, unknown);
		float amount = smoothstep(0.12F, 0.75F, unknown + (n - 0.5F) * 1.5F * edge);
		return FOG_HEIGHT * mix(LOWEST, 1, lift) * amount * (0.45F + 0.75F * n);
	}

	private float thicknessAt(double u, double v, float known, Pixel p) {
		return known >= NO_FOG_KNOWN ? 0 : thicknessFrom(known, this.noiseAt(u, v), this.sampleLift(u, v));
	}

	private float thickness(double u, double v, Pixel p) {
		return this.thicknessAt(u, v, this.knownSoft(u, v, p), p);
	}

	/** The shader's hash, on pixel coordinates; wraps them first so floats keep their precision. */
	private static float hash(long x, long y) {
		double px = Math.floorMod(x, 4096) + 0.5;
		double py = Math.floorMod(y, 4096) + 0.5;
		double s = Math.sin(px * 12.9898 + py * 78.233) * 43758.5453;
		return (float) (s - Math.floor(s));
	}

	private static float smoothstep(float e0, float e1, float x) {
		float t = clamp((x - e0) / (e1 - e0));
		return t * t * (3 - 2 * t);
	}

	private static float clamp(float x) {
		return Math.max(0, Math.min(1, x));
	}

	private static float mix(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static int channel(float c) {
		return Math.max(0, Math.min(255, Math.round(c * 255)));
	}
}
