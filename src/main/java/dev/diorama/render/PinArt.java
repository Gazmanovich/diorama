package dev.diorama.render;

import org.jspecify.annotations.Nullable;

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
	 * designed sizes, a head of 15 or 13 pixels; other sizes get the nearer one. {@code stretch}
	 * widens the head into a pill by that many pixels (see {@link #stretchFor}), for initials too
	 * wide for the round one.
	 */
	public static Art standing(int colour, boolean death, int head, int stretch) {
		int d = standingHead(head);
		int tip = d == 13 ? 5 : 6;
		int face = death ? DEATH_FACE : colour;
		int w = d + 4 + stretch;
		int h = d + tip + 3;
		Image image = new Image(w, h);
		int ox = 2;
		float ax = ox + (d + stretch) / 2.0F;
		float ay = d + tip;
		image.over(ellipse(w, h, ax, ay - 0.5F, d * 0.26F + 0.6F, 1.3F), 0x000000, 0.28F);
		boolean[] mask = outline(stretched(d == 13 ? SMALL_PIN : LARGE_PIN, d, stretch), w, h);
		face(image, mask, w, h, face);
		if (death) {
			headstone(image, ox + 1, 1, ox + d - 1 + stretch, d - 1);
		}
		return new Art(image, ax, ay, ox + 1, 1, ox + d - 1 + stretch, d - 1);
	}

	/**
	 * How many pixels wider than round a head of {@code head} has to be to hold initials whose ink
	 * is {@code ink} wide. Two letters fit the round head as they always have; wider ones get a pill
	 * whose round ends stay clear of the letters. Even, so the point stays under the middle.
	 */
	public static int stretchFor(int head, int ink) {
		int d = standingHead(head);
		if (ink <= d - 4) {
			return 0;
		}
		int extra = ink - (d - 8);
		return extra + (extra & 1);
	}

	/**
	 * A designed pin outline with its head pulled wider: the middle column of the head repeated
	 * {@code stretch} times, the point left as it is under the middle.
	 */
	private static String[] stretched(String[] rows, int head, int stretch) {
		if (stretch == 0) {
			return rows;
		}
		String[] out = new String[rows.length];
		int middle = rows[0].length() / 2;
		for (int y = 0; y < rows.length; y++) {
			String row = rows[y];
			out[y] = y < head
				? row.substring(0, middle) + String.valueOf(row.charAt(middle)).repeat(stretch) + row.substring(middle)
				: ".".repeat(stretch / 2) + row + ".".repeat(stretch / 2);
		}
		return out;
	}

	/**
	 * A round badge of diameter {@code d}; with {@code caretAngle} (radians, screen space, 0 is right)
	 * a caret beside it points that way. With an {@code icon}, the icon sits on it as on an icon pin,
	 * and {@code d} should be its {@link #iconHead}. {@code stretch} widens it into a pill, as for
	 * {@link #standing}.
	 */
	public static Art badge(int colour, boolean death, int d, int stretch, double caretAngle, int caretSize, @Nullable Image icon) {
		int face = death ? DEATH_FACE : colour;
		boolean caret = !Double.isNaN(caretAngle);
		int pad = caret ? 6 : 0;
		int w = d + stretch + pad * 2;
		int h = d + pad * 2;
		Image image = new Image(w, h);
		float cx = pad + (d + stretch) / 2.0F;
		float cy = pad + d / 2.0F;
		float r = d / 2.0F;
		float half = stretch / 2.0F;
		boolean[] mask = pill(w, h, cx, cy, r, half);
		face(image, mask, w, h, face);
		if (death) {
			headstone(image, pad + 1, pad + 1, pad + d + stretch - 1, pad + d - 1);
		} else if (icon != null) {
			sticker(image, icon, cx, cy);
		}
		if (caret) {
			float fx = (float) Math.cos(caretAngle);
			float fy = (float) Math.sin(caretAngle);
			float px = -fy;
			float py = fx;
			float reach = pillReach(r, half, fx, fy);
			float x = cx + fx * (reach + 1.5F);
			float y = cy + fy * (reach + 1.5F);
			boolean[] m = polygon(w, h, new float[] {
				x + fx * caretSize * 0.55F, y + fy * caretSize * 0.55F,
				x - fx * caretSize * 0.4F + px * caretSize * 0.5F, y - fy * caretSize * 0.4F + py * caretSize * 0.5F,
				x - fx * caretSize * 0.4F - px * caretSize * 0.5F, y - fy * caretSize * 0.4F - py * caretSize * 0.5F
			});
			boolean[] rim = dilate(m, w, h);
			for (int i = 0; i < rim.length; i++) {
				rim[i] &= !m[i] && image.a[i] < 1;
			}
			image.put(rim, INK, 0.9F);
			image.put(m, colour, 1);
		}
		if (icon != null && !death) {
			// No initials: an empty label box.
			return new Art(image, cx, cy, 0, 0, 0, 0);
		}
		return new Art(image, cx, cy, pad + 1, pad + 1, pad + d + stretch - 1, pad + d - 1);
	}

	/** How far from a pill's centre its edge is along the direction (fx, fy), a unit vector. */
	private static float pillReach(float r, float half, float fx, float fy) {
		// Out along the direction until the point is r from the pill's middle line.
		float lo = 0;
		float hi = r + half;
		for (int i = 0; i < 20; i++) {
			float t = (lo + hi) / 2;
			float dx = Math.max(0, Math.abs(fx * t) - half);
			float dy = fy * t;
			if (dx * dx + dy * dy < r * r) {
				lo = t;
			} else {
				hi = t;
			}
		}
		return lo;
	}

	/** The white round an icon, a touch off so it doesn't glare on snow. */
	private static final int STICKER_WHITE = 0xF6F6F6;
	/** The tip under an icon pin's head, as long as the large pin's. */
	private static final int ICON_TIP = 6;

	/**
	 * The head of a pin or badge showing an icon: the icon's tile, its white edge, then room for the
	 * tile's corners to clear the bevel and rim (21 for an 11-pixel tile; at 19 square icons touched
	 * the rim). Odd, so the tile centres.
	 */
	public static int iconHead(Image icon) {
		int d = Math.max(icon.width, icon.height) + 10;
		return d % 2 == 0 ? d + 1 : d;
	}

	/** How far above the marked spot an icon pin's head is centred. */
	public static float iconHeadAbove(Image icon) {
		return iconHead(icon) / 2.0F + ICON_TIP;
	}

	/**
	 * The standing pin for a waypoint with an icon: the round pin in the waypoint's colour, its head
	 * grown to hold the icon, the icon on it like a sticker (its own dark outline, part of the art,
	 * then a white edge round its shape). One texel per GUI pixel.
	 */
	public static Art iconPin(Image icon, int colour) {
		int d = iconHead(icon);
		int w = d + 4;
		int h = d + ICON_TIP + 3;
		Image image = new Image(w, h);
		float ax = 2 + d / 2.0F;
		float ay = d + ICON_TIP;
		image.over(ellipse(w, h, ax, ay - 0.5F, d * 0.26F + 0.6F, 1.3F), 0x000000, 0.28F);
		boolean[] mask = disc(w, h, ax, d / 2.0F, d / 2.0F);
		boolean[] point = polygon(w, h, new float[] {ax - d * 0.32F, d * 0.75F, ax + d * 0.32F, d * 0.75F, ax, ay});
		for (int i = 0; i < mask.length; i++) {
			mask[i] |= point[i];
		}
		face(image, mask, w, h, colour);
		sticker(image, icon, ax, d / 2.0F);
		return new Art(image, ax, ay, 0, 0, 0, 0);
	}

	/** Puts an icon centred on (cx, cy), with a white edge round its shape. */
	private static void sticker(Image image, Image icon, float cx, float cy) {
		int w = image.width;
		int ix = Math.round(cx - icon.width / 2.0F);
		int iy = Math.round(cy - icon.height / 2.0F);
		boolean[] shape = new boolean[image.width * image.height];
		for (int y = 0; y < icon.height; y++) {
			for (int x = 0; x < icon.width; x++) {
				shape[(iy + y) * w + ix + x] = icon.a[y * icon.width + x] >= 128;
			}
		}
		boolean[] edge = grow4(shape, w, image.height);
		for (int i = 0; i < edge.length; i++) {
			edge[i] &= !shape[i];
		}
		image.put(edge, STICKER_WHITE, 1);
		for (int y = 0; y < icon.height; y++) {
			for (int x = 0; x < icon.width; x++) {
				int s = y * icon.width + x;
				if (icon.a[s] >= 128) {
					int t = (iy + y) * w + ix + x;
					image.r[t] = icon.r[s];
					image.g[t] = icon.g[s];
					image.b[t] = icon.b[s];
					image.a[t] = 255;
				}
			}
		}
	}

	/** An image from ARGB pixels, row by row. */
	public static Image fromArgb(int width, int height, int[] argb) {
		Image image = new Image(width, height);
		for (int i = 0; i < argb.length; i++) {
			image.r[i] = (argb[i] >> 16) & 0xFF;
			image.g[i] = (argb[i] >> 8) & 0xFF;
			image.b[i] = argb[i] & 0xFF;
			image.a[i] = argb[i] >>> 24;
		}
		return image;
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

	/** A disc of radius r drawn out sideways by {@code half} each way from (cx, cy), as {@link #disc}. */
	private static boolean[] pill(int w, int h, float cx, float cy, float r, float half) {
		boolean[] m = new boolean[w * h];
		float rr = (r - 0.25F) * (r - 0.25F);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float dx = Math.max(0, Math.abs(x + 0.5F - cx) - half);
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

	/** The shape grown by a pixel up, down, left and right, so its corners come out cut. */
	private static boolean[] grow4(boolean[] m, int w, int h) {
		boolean[] out = new boolean[m.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[y * w + x] = at(m, w, h, x, y) || at(m, w, h, x - 1, y) || at(m, w, h, x + 1, y) || at(m, w, h, x, y - 1) || at(m, w, h, x, y + 1);
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
