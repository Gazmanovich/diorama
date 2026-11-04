package dev.diorama.render;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;

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
	// fog.fsh's clouds and the soft fog over them; change both together.
	private static final float FOG_MIX = 0.5F;
	private static final float LOWEST = 0.12F;
	private static final float SHADOW = 1.6F;
	/** The sun shading of fog without clouds: smoke's. */
	private static final float FOG_SHADOW = 0.7F;
	private static final float CLOUD_CELL = 12.8F;
	private static final float CLOUD_LEVELS = 5;
	private static final float LEVEL_THICK = 0.9F;
	private static final float STAIR_NOISE = 0.6F;
	private static final float ROUND = 0.45F;
	private static final float EDGE_SOFT = 0.5F;
	private static final float PROFILE = 4;
	private static final float CLOUD_ALPHA = 0.95F;
	private static final float WISP = 1.5F;
	private static final float STRANDS = 1;
	private static final float WARP = 2;
	private static final float HAZE = 0.5F;
	private static final float HALO = 0.25F;
	private static final float DEPTH_DARK = 0.65F;
	private static final float PILLOW = 1;
	private static final float GROUND_SHADOW = 0.3F;
	/** How far from explored land the clouds over never-explored land reach full height, in cells a level; as MapViewport. */
	private static final float CELLS_PER_LEVEL = 5;
	private static final float SUN_U = -0.928477F;
	private static final float SUN_V = -0.371391F;
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

	private ExportFog(int lod, float[] known, int maskW, int maskH, double originU, double originV, float texel, int steps, float fraction, boolean smoke) {
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
		// What the map draws behind its tiles.
		this.background = smoke ? 0x29211F : 0x8C99AB;
	}

	/**
	 * Reads the saved tiles under {@code area} and a margin around it into the fog's mask.
	 * {@code smoke} for the sky view under a roof, which has smoke instead of clouds.
	 */
	public static ExportFog build(TileStore store, ImageExport.Area area, boolean smoke) {
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
		// The clouds climb with distance from explored land, which can lie past the margin: read as
		// far as the climb reaches, or the terraces at the picture's edge depend on where it's cut.
		if (!smoke) {
			margin += CLOUD_LEVELS * CELLS_PER_LEVEL * CLOUD_CELL * Math.pow(2, steps);
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
		ExportFog fog = new ExportFog(lod, sum, maskW, maskH, originU, originV, texel, steps, fraction, smoke);
		if (MapStyle.get().roundedFog() && texel < 16) {
			fog.roundOff(Math.max(1, Math.round(10 / texel)));
		}
		fog.climb();
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

	/**
	 * The clouds' climb over never-explored land, as {@code MapViewport.liftTargets} without a
	 * remembered map: a level every few cells away from explored land, full height with none.
	 */
	private void climb() {
		int w = this.maskW;
		int h = this.maskH;
		int[] d = new int[w * h];
		boolean any = false;
		for (int i = 0; i < d.length; i++) {
			boolean explored = this.known[i] >= 0.5F;
			any |= explored;
			d[i] = explored ? 0 : Integer.MAX_VALUE / 2;
		}
		if (!this.clouds || !any) {
			java.util.Arrays.fill(this.lift, 1);
			return;
		}
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
		double climbThirds = 3 * CLOUD_LEVELS * CELLS_PER_LEVEL * CLOUD_CELL * (1 << this.scaleSteps) / this.texel;
		for (int i = 0; i < d.length; i++) {
			// Kept to bytes, like the mask's alpha.
			this.lift[i] = Math.round((float) Math.min(1, d[i] / climbThirds) * 255) / 255F;
		}
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
		// The clouds' reach too: MapViewport.fogReach.
		double cell = this.clouds ? 2 * CLOUD_CELL * (1 << this.scaleSteps) / this.texel : 0;
		double around = 4 * cell;
		double lean = CLOUD_LEVELS * LEVEL_THICK * cell * COS_PITCH;
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
		float liftHere = this.sampleLift(u, v);
		int ti = (int) Math.floor((u - this.originU) / this.texel);
		int tj = (int) Math.floor((v - this.originV) / this.texel);
		byte flag = ti < 0 || tj < 0 || ti >= this.maskW || tj >= this.maskH ? 0 : this.flags[tj * this.maskW + ti];
		p.clear = flag == 1;
		p.deep = flag == 2;

		float jitter = hash(x, y) - 0.5F;
		float[] cloud = this.clouds ? this.cloudsAt(u, v, jitter, p) : null;
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
			float light = clamp(0.2F + 0.7F * (h / FOG_HEIGHT) + 0.3F - occlusion * (this.clouds ? SHADOW : FOG_SHADOW));
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

		if (cloud != null) {
			// The fog rolls over the cloud terraces: its premultiplied colour over theirs.
			float a = alpha * FOG_MIX;
			r = r * FOG_MIX + (1 - a) * cloud[0];
			g = g * FOG_MIX + (1 - a) * cloud[1];
			b = b * FOG_MIX + (1 - a) * cloud[2];
			alpha = 1 - (1 - a) * (1 - cloud[3]);
		}

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
	 * fog.fsh's clouds over a point, still in time: premultiplied colour and how much they
	 * cover, haze and shadow included; null-free, {0, 0, 0, 0} where there are none.
	 */
	private float[] cloudsAt(double u, double v, float jitter, Pixel p) {
		float size = CLOUD_CELL * (float) (1 << this.scaleSteps);
		float handover = smoothstep(0.42F, 0.58F, this.scaleFraction);
		if (handover >= 1) {
			size *= 2;
		}
		float[] c;
		float shadow = 0;
		if (p.clear) {
			c = new float[] {0, 0, 0, 1};
			handover = 0;
		} else if (p.deep) {
			c = this.flatTop(u, v, size, jitter);
			shadow = GROUND_SHADOW;
		} else {
			c = this.clouds(u, v, size, jitter);
			shadow = GROUND_SHADOW * this.groundShadow(u, v, size);
		}
		if (handover > 0 && handover < 1) {
			float[] c2 = p.deep ? this.flatTop(u, v, size * 2, jitter) : this.clouds(u, v, size * 2, jitter);
			float shadow2 = GROUND_SHADOW * (p.deep ? 1 : this.groundShadow(u, v, size * 2));
			for (int i = 0; i < 4; i++) {
				c[i] = mix(c[i], c2[i], handover);
			}
			shadow = mix(shadow, shadow2, handover);
		}
		float through = c[3];
		float soft = this.knownSoft(u, v, p);
		float halo = HALO * (1 - smoothstep(0.55F, 1, soft));
		float empty = 1 - smoothstep(0.3F, 0.85F, this.knownAt(u, v, p));
		float mist = Math.max(halo, empty);
		float r = c[0] + through * mist * 0.64F;
		float g = c[1] + through * mist * 0.75F;
		float b = c[2] + through * mist * 0.9F;
		through *= 1 - mist;
		return new float[] {r, g, b, 1 - through * (1 - shadow)};
	}

	private float cellLevel(double cu, double cv, float size) {
		double pu = (cu + 0.5) * size;
		double pv = (cv + 0.5) * size;
		if (this.sample(pu, pv) >= 0.5F) {
			return 0;
		}
		float n = noise(noiseR, pu / (size * 20) + 0.21, pv / (size * 20) + 0.21);
		return Math.max(1, Math.min(CLOUD_LEVELS, (float) Math.ceil(this.sampleLift(pu, pv) * CLOUD_LEVELS + (n - 0.5F) * STAIR_NOISE)));
	}

	private float levelCover(double pu, double pv, float size, float k) {
		double gx = pu / size - 0.5;
		double gy = pv / size - 0.5;
		double ix = Math.floor(gx);
		double iy = Math.floor(gy);
		float fx = smoothstep(0.5F - ROUND, 0.5F + ROUND, (float) (gx - ix));
		float fy = smoothstep(0.5F - ROUND, 0.5F + ROUND, (float) (gy - iy));
		float a = this.cellLevel(ix, iy, size) >= k ? 1 : 0;
		float b = this.cellLevel(ix + 1, iy, size) >= k ? 1 : 0;
		float c = this.cellLevel(ix, iy + 1, size) >= k ? 1 : 0;
		float d = this.cellLevel(ix + 1, iy + 1, size) >= k ? 1 : 0;
		return mix(mix(a, b, fx), mix(c, d, fx), fy);
	}

	private static float wispAt(double pu, double pv, float size) {
		double nu = pu / (size * 4);
		double nv = pv / (size * 4);
		return noise(noiseG, nu, nv) * 0.6F + noise(noiseB, nu * 2, nv * 2) * 0.4F;
	}

	/** {u, v} pushed about by the swirling noise. */
	private static double[] warped(double pu, double pv, float size, float k) {
		double nu = pu / (size * 2.5);
		double nv = pv / (size * 2.5);
		float au = noise(noiseG, nu, nv) - 0.5F;
		float av = noise(noiseB, nu + 0.37, nv + 0.37) - 0.5F;
		float bu = noise(noiseA, nu * 3 + 0.11, nv * 3 + 0.11) - 0.5F;
		float bv = noise(noiseG, nu * 3 + 0.71, nv * 3 + 0.71) - 0.5F;
		float amount = WARP * size * (k <= 1 ? 0.35F : 1);
		return new double[] {pu + (au + bu * 0.45F) * amount, pv + (av + bv * 0.45F) * amount};
	}

	private float wispCover(double pu, double pv, float size, float k) {
		double[] w = warped(pu, pv, size, k);
		float cover = this.levelCover(w[0], w[1], size, k);
		float bite = smoothstep(0.35F, 0.75F, wispAt(pu, pv, size));
		return cover - WISP * bite * (1 - cover) * cover * 2;
	}

	private float terraceInside(double pu, double pv, float h, float size, float level) {
		float k = Math.max(1, Math.min(CLOUD_LEVELS, (float) Math.ceil(h / level)));
		float t = (float) Math.pow(clamp(Math.abs(h - (k - 0.5F) * level) / (0.5F * level)), PROFILE);
		return this.wispCover(pu, pv, size, k) - (0.25F + 0.5F * t);
	}

	/** The cloud surface met at height {@code h}: {r, g, b, alpha}. */
	private float[] terraceSurface(double pu, double pv, float h, float size, float level, float hTop, float jitter) {
		float k = Math.max(1, Math.min(CLOUD_LEVELS, (float) Math.ceil(h / level)));
		float up = (float) Math.pow(clamp(h / level - (k - 1)), 0.8);
		double tu = SUN_U * size * 0.4F;
		double tv = SUN_V * size * 0.4F;
		float blocked = this.levelCover(pu + tu * (1 + jitter * 0.5F), pv + tv * (1 + jitter * 0.5F), size, k)
			+ this.levelCover(pu + tu * (2 + jitter * 0.5F), pv + tv * (2 + jitter * 0.5F), size, k + 1);
		float shading = (0.12F + 0.5F * up) * (2 - SHADOW * blocked * (1 - up * up));
		double su = SUN_U * size * 0.35F;
		double sv = SUN_V * size * 0.35F;
		float sunEdge = 1 - this.levelCover(pu + su, pv + sv, size, k);
		float farEdge = 1 - this.levelCover(pu - su, pv - sv, size, k);
		shading *= mix(1, 1 + PILLOW * (0.3F * sunEdge - 0.45F * farEdge), up);
		float depth = mix(DEPTH_DARK, 1, k / CLOUD_LEVELS);
		float hazeShare = HAZE * (float) Math.pow(1 - h / hTop, 1.5);
		float[] sky = {0.56F, 0.65F, 0.77F};
		float[] sunlight = {1.0F, 0.93F, 0.82F};
		float[] haze = {0.64F, 0.75F, 0.9F};
		float[] out = new float[4];
		for (int i = 0; i < 3; i++) {
			float color = (sky[i] * (1 - 0.35F * shading) + sunlight[i] * 0.5F * (0.1F + shading)) * depth;
			out[i] = mix(Math.min(color, 1), haze[i], hazeShare);
		}
		float cover = this.wispCover(pu, pv, size, k);
		float fringe = cover - 0.25F;
		double[] w = warped(pu, pv, size, k);
		float edge = 1 - smoothstep(0.55F, 0.95F, this.levelCover(w[0], w[1], size, k));
		float strands = smoothstep(0.25F, 0.75F, wispAt(pu * 0.35 + 53, pv * 1.4 + 53, size * 0.5F));
		out[3] = CLOUD_ALPHA * smoothstep(0, EDGE_SOFT, fringe) * mix(1, strands, edge * STRANDS);
		return out;
	}

	/** fog.fsh's march down the line of sight: premultiplied colour and how much shows through. */
	/** fog.fsh's flatTop: what {@link #clouds} returns over plain never-explored land, without the march. */
	private float[] flatTop(double u, double v, float size, float jitter) {
		float level = size * LEVEL_THICK;
		float hTop = CLOUD_LEVELS * level;
		float dh = hTop / (CLOUD_LEVELS * 3);
		float h = hTop - (0.5F + jitter * 0.9F) * dh / 8;
		float[] hit = this.terraceSurface(u, v + h * COS_PITCH, h, size, level, hTop, jitter);
		return new float[] {hit[0] * hit[3], hit[1] * hit[3], hit[2] * hit[3], 1 - hit[3]};
	}

	private float[] clouds(double u, double v, float size, float jitter) {
		float level = size * LEVEL_THICK;
		float hTop = CLOUD_LEVELS * level;
		int steps = (int) CLOUD_LEVELS * 3;
		float dh = hTop / steps;
		float last = hTop;
		boolean wasInside = false;
		int surfaces = 0;
		float through = 1;
		float r = 0;
		float g = 0;
		float b = 0;
		for (int s = 0; s < steps; s++) {
			float h = hTop - (s + 0.5F + jitter * 0.9F) * dh;
			boolean inside = this.terraceInside(u, v + h * COS_PITCH, h, size, level) > 0;
			if (inside && !wasInside) {
				float above = last;
				float below = h;
				for (int i = 0; i < 3; i++) {
					float mid = 0.5F * (above + below);
					if (this.terraceInside(u, v + mid * COS_PITCH, mid, size, level) > 0) {
						below = mid;
					} else {
						above = mid;
					}
				}
				float[] hit = this.terraceSurface(u, v + below * COS_PITCH, below, size, level, hTop, jitter);
				r += through * hit[3] * hit[0];
				g += through * hit[3] * hit[1];
				b += through * hit[3] * hit[2];
				through *= 1 - hit[3];
				surfaces++;
				if (surfaces >= 2 || through < 0.1F) {
					break;
				}
			}
			wasInside = inside;
			last = h;
		}
		return new float[] {r, g, b, through};
	}

	private float groundShadow(double u, double v, float size) {
		double d = size * LEVEL_THICK * 1.5;
		return smoothstep(0.3F, 0.6F, this.levelCover(u + SUN_U * d, v + SUN_V * d, size, 1));
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
