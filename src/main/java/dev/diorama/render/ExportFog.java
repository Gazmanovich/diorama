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
	/**
	 * The most mask texels a side. Bigger exports get coarser texels, as the map's own fog does when
	 * zoomed far out, so the mask stays tens of megabytes however big the image is.
	 */
	private static final int MAX_MASK = 2048;

	private static float[] noiseR;
	private static float[] noiseG;
	private static float[] noiseB;

	private final int lod;
	/** Map units per export pixel. */
	private final double unitsPerPixel;
	/** The mask: known share per texel of {@link #texel} map units, from {@link #originU}, {@link #originV}. */
	private final float[] known;
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
		for (int i = 0; i < pixels.length; i++) {
			r[i] = (pixels[i] & 0xFF) / 255F;
			g[i] = ((pixels[i] >> 8) & 0xFF) / 255F;
			b[i] = ((pixels[i] >> 16) & 0xFF) / 255F;
		}
		noiseR = r;
		noiseG = g;
		noiseB = b;
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
		int west = (int) Math.ceil(Math.max(blur, sun) + slack);
		int east = (int) Math.ceil(blur + slack);
		int north = (int) Math.ceil(Math.max(blur, sun * 0.4) + slack);
		int south = (int) Math.ceil(blur + layerShift + slack);
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
				lineNotUnknown += k <= 0 ? 0 : 1;
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
			float light = clamp(0.2F + 0.7F * (h / FOG_HEIGHT) + 0.3F - occlusion * 0.7F);
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
		float floorFog = smoothstep(0.5F, 2.5F, this.thicknessAt(u, v, soft, p));
		r += (1 - alpha) * floorFog * this.shade[0];
		g += (1 - alpha) * floorFog * this.shade[1];
		b += (1 - alpha) * floorFog * this.shade[2];
		alpha += (1 - alpha) * floorFog;

		// Only the alpha, as the shader does: the colour it divides by alpha later stays as it was.
		alpha *= 1 - 0.85F * smoothstep(0.45F, 0.95F, soft);

		float empty = 1 - smoothstep(0.3F, 0.85F, this.knownAt(u, v, p));
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

	private static float thicknessFrom(float known, float n) {
		float unknown = 1 - known;
		float edge = smoothstep(0, 0.35F, unknown);
		float amount = smoothstep(0.12F, 0.75F, unknown + (n - 0.5F) * 1.5F * edge);
		return FOG_HEIGHT * amount * (0.45F + 0.75F * n);
	}

	private float thicknessAt(double u, double v, float known, Pixel p) {
		return known >= NO_FOG_KNOWN ? 0 : thicknessFrom(known, this.noiseAt(u, v));
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
