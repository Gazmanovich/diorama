package dev.diorama.render;

/**
 * The waypoint pins as pixel art, one texel per GUI pixel: a round badge with a dark rim and a
 * bevel lit from the top left, either standing on a point that touches the spot or, at the edge of
 * a view, with a caret pointing the way. Pure pixels, no GPU, so it can be drawn into a texture on
 * any version.
 */
public final class PinArt {
	static final int INK = 0x101418;
	/** Deaths are near-black so the headstone stands out. */
	static final int DEATH_FACE = 0x2B2D31;
	private static final float RIM_ALPHA = 0.94F;
	/**
	 * Kenney's 8x8 headstone (Micro Roguelike, CC0) cut to 7x7 and made mirror-symmetric. Pin heads
	 * are an odd number of pixels across both ways, so an odd-sized icon centres exactly.
	 */
	private static final String[] HEADSTONE = {
		"..gWg..",
		".gWWWg.",
		".WgdgW.",
		".WWWWW.",
		".WgdgW.",
		"ggggggg",
		"gWWWWWg"
	};

	/**
	 * The two standing pins' outlines, pixel for pixel as they were designed: a disc of 15 (or 13)
	 * raised on a point 6 (or 5) long, mirrored so both sides match. Fixed shapes, so they are kept
	 * as drawn rather than rasterised again here.
	 */
	private static final String[] LARGE_PIN = {
		"........###........",
		".....#########.....",
		"....###########....",
		"...#############...",
		"...#############...",
		"...#############...",
		"..###############..",
		"..###############..",
		"..###############..",
		"...#############...",
		"...#############...",
		"...#############...",
		"....###########....",
		".....#########.....",
		"......#######......",
		".......#####.......",
		".......#####.......",
		"........###........",
		"........###........",
		".........#.........",
		".........#........."
	};
	private static final String[] SMALL_PIN = {
		".......###.......",
		".....#######.....",
		"....#########....",
		"...###########...",
		"...###########...",
		"..#############..",
		"..#############..",
		"..#############..",
		"...###########...",
		"...###########...",
		"....#########....",
		".....#######.....",
		"......#####......",
		"......#####......",
		".......###.......",
		".......###.......",
		".......###.......",
		"........#........"
	};

	/** RGBA pixels with straight alpha, 0..255 per channel. */
	public static final class Image {
		public final int width;
		public final int height;
		final float[] r;
		final float[] g;
		final float[] b;
		final float[] a;

		Image(int width, int height) {
			this.width = width;
			this.height = height;
			this.r = new float[width * height];
			this.g = new float[width * height];
			this.b = new float[width * height];
			this.a = new float[width * height];
		}

		void put(boolean[] mask, int rgb, float alpha) {
			for (int i = 0; i < mask.length; i++) {
				if (mask[i]) {
					this.r[i] = (rgb >> 16) & 0xFF;
					this.g[i] = (rgb >> 8) & 0xFF;
					this.b[i] = rgb & 0xFF;
					this.a[i] = alpha * 255;
				}
			}
		}

		/** Blends a colour over what is there, for the ground shadow under the pin. */
		void over(boolean[] mask, int rgb, float alpha) {
			for (int i = 0; i < mask.length; i++) {
				if (!mask[i]) {
					continue;
				}
				float dst = this.a[i] / 255;
				float out = alpha + dst * (1 - alpha);
				this.r[i] = (((rgb >> 16) & 0xFF) * alpha + this.r[i] * dst * (1 - alpha)) / out;
				this.g[i] = (((rgb >> 8) & 0xFF) * alpha + this.g[i] * dst * (1 - alpha)) / out;
				this.b[i] = ((rgb & 0xFF) * alpha + this.b[i] * dst * (1 - alpha)) / out;
				this.a[i] = out * 255;
			}
		}

		/** The pixels as ABGR ints (RGBA in memory), in a square of {@code edge} with the image at its top left. */
		public int[] abgr(int edge) {
			int[] out = new int[edge * edge];
			for (int y = 0; y < this.height; y++) {
				for (int x = 0; x < this.width; x++) {
					int i = y * this.width + x;
					out[y * edge + x] = (Math.round(this.a[i]) << 24) | (Math.round(this.b[i]) << 16) | (Math.round(this.g[i]) << 8) | Math.round(this.r[i]);
				}
			}
			return out;
		}

		/** Pixel (x, y) as ARGB, for checks. */
		public int argb(int x, int y) {
			int i = y * this.width + x;
			return (Math.round(this.a[i]) << 24) | (Math.round(this.r[i]) << 16) | (Math.round(this.g[i]) << 8) | Math.round(this.b[i]);
		}
	}

	/**
	 * A drawn pin: its pixels, the anchor (the spot it marks, in texels from the top left) and the
	 * box its initials are centred in.
	 */
	public record Art(Image image, float anchorX, float anchorY, int labelX0, int labelY0, int labelX1, int labelY1) {
	}

	private PinArt() {
	}

	/**
	 * A badge raised on a point, with a small shadow where it touches. The pin comes in the two
	 * designed sizes, a head of 15 or 13 pixels; other sizes get the nearer one.
	 */
	public static Art standing(int colour, boolean death, int head) {
		int d = standingHead(head);
		int tip = d == 13 ? 5 : 6;
		int face = death ? DEATH_FACE : colour;
		int w = d + 4;
		int h = d + tip + 3;
		Image image = new Image(w, h);
		int ox = 2;
		float ax = ox + d / 2.0F;
		float ay = d + tip;
		image.over(ellipse(w, h, ax, ay - 0.5F, d * 0.26F + 0.6F, 1.3F), 0x000000, 0.28F);
		boolean[] mask = outline(d == 13 ? SMALL_PIN : LARGE_PIN, w, h);
		face(image, mask, w, h, face);
		if (death) {
			headstone(image, ox + 1, 1, ox + d - 1, d - 1);
		}
		return new Art(image, ax, ay, ox + 1, 1, ox + d - 1, d - 1);
	}

	/** A round badge of diameter {@code d}; with {@code caretAngle} (radians, screen space, 0 is right) a caret beside it points that way. */
	public static Art badge(int colour, boolean death, int d, double caretAngle, int caretSize) {
		int face = death ? DEATH_FACE : colour;
		boolean caret = !Double.isNaN(caretAngle);
		int pad = caret ? 6 : 0;
		int w = d + pad * 2;
		Image image = new Image(w, w);
		boolean[] mask = disc(w, w, pad + d / 2.0F, pad + d / 2.0F, d / 2.0F);
		face(image, mask, w, w, face);
		if (death) {
			headstone(image, pad + 1, pad + 1, pad + d - 1, pad + d - 1);
		}
		if (caret) {
			float cx = pad + d / 2.0F;
			float r = d / 2.0F;
			float fx = (float) Math.cos(caretAngle);
			float fy = (float) Math.sin(caretAngle);
			float px = -fy;
			float py = fx;
			float x = cx + fx * (r + 1.5F);
			float y = cx + fy * (r + 1.5F);
			boolean[] m = polygon(w, w, new float[] {
				x + fx * caretSize * 0.55F, y + fy * caretSize * 0.55F,
				x - fx * caretSize * 0.4F + px * caretSize * 0.5F, y - fy * caretSize * 0.4F + py * caretSize * 0.5F,
				x - fx * caretSize * 0.4F - px * caretSize * 0.5F, y - fy * caretSize * 0.4F - py * caretSize * 0.5F
			});
			boolean[] rim = dilate(m, w, w);
			for (int i = 0; i < rim.length; i++) {
				rim[i] &= !m[i] && image.a[i] < 1;
			}
			image.put(rim, INK, 0.9F);
			image.put(m, colour, 1);
		}
		return new Art(image, pad + d / 2.0F, pad + d / 2.0F, pad + 1, pad + 1, pad + d - 1, pad + d - 1);
	}

	/** The designed standing pin nearest a head size: 15 or 13. */
	public static int standingHead(int head) {
		return head >= 14 ? 15 : 13;
	}

	/** Where something {@code size} wide starts so it is centred in x0..x1; a half pixel rounds toward the bottom right, where the bevel is darker. */
	public static int centred(int x0, int x1, int size) {
		return x0 + Math.floorDiv(x1 - x0 - size + 1, 2);
	}

	/** Dark letters on light colours, white on dark ones (the mod's rule). */
	public static boolean light(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return 0.299 * r + 0.587 * g + 0.114 * b > 160;
	}

	/** A dark rim, the colour inside, a one-pixel bevel lit from the top left. */
	private static void face(Image image, boolean[] mask, int w, int h, int colour) {
		boolean[] ring = ring(mask, w, h);
		boolean[] inside = new boolean[mask.length];
		for (int i = 0; i < mask.length; i++) {
			inside[i] = mask[i] && !ring[i];
		}
		image.put(ring, INK, RIM_ALPHA);
		image.put(inside, colour, 1);
		boolean[] top = new boolean[mask.length];
		boolean[] bottom = new boolean[mask.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (!inside[i]) {
					continue;
				}
				boolean ringAboveOrLeft = at(ring, w, h, x, y - 1) || at(ring, w, h, x - 1, y);
				boolean ringBelowOrRight = at(ring, w, h, x, y + 1) || at(ring, w, h, x + 1, y);
				top[i] = ringAboveOrLeft && !ringBelowOrRight;
				bottom[i] = ringBelowOrRight && !ringAboveOrLeft;
			}
		}
		image.put(top, mix(colour, 0xFFFFFF, 0.35F), 1);
		image.put(bottom, mix(colour, 0x000000, 0.25F), 1);
	}

	private static void headstone(Image image, int x0, int y0, int x1, int y1) {
		int size = HEADSTONE.length;
		int left = centred(x0, x1, size);
		int top = centred(y0, y1, size);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int rgb = switch (HEADSTONE[y].charAt(x)) {
					case 'W' -> 0xC2C2D1;
					case 'g' -> 0x7E7E8F;
					case 'd' -> 0x606070;
					default -> -1;
				};
				if (rgb >= 0) {
					int i = (top + y) * image.width + left + x;
					image.r[i] = (rgb >> 16) & 0xFF;
					image.g[i] = (rgb >> 8) & 0xFF;
					image.b[i] = rgb & 0xFF;
					image.a[i] = 255;
				}
			}
		}
	}

	static int mix(int rgb, int other, float t) {
		int r = Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((other >> 16) & 0xFF) * t);
		int g = Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((other >> 8) & 0xFF) * t);
		int b = Math.round((rgb & 0xFF) * (1 - t) + (other & 0xFF) * t);
		return (r << 16) | (g << 8) | b;
	}

	private static boolean at(boolean[] m, int w, int h, int x, int y) {
		return x >= 0 && y >= 0 && x < w && y < h && m[y * w + x];
	}

	/** Pixels whose centre is inside the circle, a quarter pixel in from its edge. */
	private static boolean[] disc(int w, int h, float cx, float cy, float r) {
		boolean[] m = new boolean[w * h];
		float rr = (r - 0.25F) * (r - 0.25F);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float dx = x + 0.5F - cx;
				float dy = y + 0.5F - cy;
				m[y * w + x] = dx * dx + dy * dy <= rr;
			}
		}
		return m;
	}

	private static boolean[] ellipse(int w, int h, float cx, float cy, float rx, float ry) {
		boolean[] m = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float dx = (x + 0.5F - cx) / rx;
				float dy = (y + 0.5F - cy) / ry;
				m[y * w + x] = dx * dx + dy * dy <= 1;
			}
		}
		return m;
	}

	/** Pixels covered at least half by the polygon (x, y pairs), measured on a 4x4 grid inside each. */
	private static boolean[] polygon(int w, int h, float[] pts) {
		boolean[] m = new boolean[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int hits = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						if (inside(pts, x + (sx + 0.5F) / 4, y + (sy + 0.5F) / 4)) {
							hits++;
						}
					}
				}
				m[y * w + x] = hits >= 8;
			}
		}
		return m;
	}

	static boolean inside(float[] pts, float x, float y) {
		boolean in = false;
		int n = pts.length / 2;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			float xi = pts[i * 2];
			float yi = pts[i * 2 + 1];
			float xj = pts[j * 2];
			float yj = pts[j * 2 + 1];
			if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
				in = !in;
			}
		}
		return in;
	}

	private static boolean[] outline(String[] rows, int w, int h) {
		boolean[] m = new boolean[w * h];
		for (int y = 0; y < rows.length; y++) {
			for (int x = 0; x < w; x++) {
				m[y * w + x] = rows[y].charAt(x) == '#';
			}
		}
		return m;
	}

	/** The outermost pixels of a shape: those with a 4-neighbour outside it, or on the canvas edge. */
	private static boolean[] ring(boolean[] m, int w, int h) {
		boolean[] out = new boolean[m.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (m[i]) {
					boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
					out[i] = edge || !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w];
				}
			}
		}
		return out;
	}

	private static boolean[] dilate(boolean[] m, int w, int h) {
		boolean[] out = new boolean[m.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean any = false;
				for (int dy = -1; dy <= 1 && !any; dy++) {
					for (int dx = -1; dx <= 1 && !any; dx++) {
						any = at(m, w, h, x + dx, y + dy);
					}
				}
				out[y * w + x] = any;
			}
		}
		return out;
	}
}
