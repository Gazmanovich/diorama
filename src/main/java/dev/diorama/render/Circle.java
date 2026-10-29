package dev.diorama.render;

import org.jspecify.annotations.Nullable;

/**
 * A circle on screen, for the round minimap: a many-sided polygon that rectangles are clipped to.
 * The map draws in quads with float corners, so a tile on the circle's edge becomes a few quads
 * that follow it, smooth at any GUI scale, on every version, with no stencil or shader.
 *
 * <p>Each clipped shape comes out as one set of quads, for one draw: drawn quad by quad, a disc
 * was dozens of draws before 1.21.6, and dozens of GUI layers after it, since each quad's bounds
 * overlap the one before's.
 */
public final class Circle {
	/** Sides of the polygon. At the largest minimap a side bows less than a tenth of a GUI pixel from the circle. */
	private static final int SIDES = 96;
	/** The unit circle's corners, x then y, in the same turning order as the quads' corners: top left, bottom left, bottom right, top right. */
	private static final float[] UNIT = new float[SIDES * 2];
	/**
	 * The shape being clipped and the next one, kept between calls: clipping by each side adds at
	 * most one corner to the shape's. Render thread only.
	 */
	private static float[] shape = new float[(SIDES + 5) * 2];
	private static float[] kept = new float[(SIDES + 5) * 2];

	static {
		for (int i = 0; i < SIDES; i++) {
			double a = Math.PI * 2 * i / SIDES;
			UNIT[i * 2] = (float) Math.cos(a);
			UNIT[i * 2 + 1] = (float) -Math.sin(a);
		}
	}

	public final float cx;
	public final float cy;
	public final float r;
	private final float[] points = new float[SIDES * 2];
	private float @Nullable [] disc;

	public Circle(float cx, float cy, float r) {
		this.cx = cx;
		this.cy = cy;
		this.r = r;
		for (int i = 0; i < SIDES; i++) {
			this.points[i * 2] = cx + r * UNIT[i * 2];
			this.points[i * 2 + 1] = cy + r * UNIT[i * 2 + 1];
		}
	}

	/** The circle filling a square view. */
	public static Circle in(MapViewport.View view) {
		return new Circle(view.x() + view.width() / 2.0F, view.y() + view.height() / 2.0F, Math.min(view.width(), view.height()) / 2.0F);
	}

	public boolean contains(float x, float y) {
		float dx = x - this.cx;
		float dy = y - this.cy;
		return dx * dx + dy * dy <= this.r * this.r;
	}

	/**
	 * Quads, as {@link dev.diorama.render.gpu.MapCanvas#quad} takes them: four corners each, and
	 * their texture coordinates.
	 */
	public record Quads(float[] corners, float[] uvs) {
	}

	/**
	 * The part of a rectangle inside the circle, with texture coordinates carried along from
	 * (u0, v0) at the top left to (u1, v1) at the bottom right, or null when none of it is. A
	 * rectangle wholly inside comes out as itself; one around the whole circle as the disc.
	 */
	public @Nullable Quads clip(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
		float nearX = Math.max(x0, Math.min(this.cx, x1)) - this.cx;
		float nearY = Math.max(y0, Math.min(this.cy, y1)) - this.cy;
		if (nearX * nearX + nearY * nearY >= this.r * this.r) {
			return null;
		}
		if (this.contains(x0, y0) && this.contains(x1, y0) && this.contains(x0, y1) && this.contains(x1, y1)) {
			return new Quads(new float[] {x0, y0, x0, y1, x1, y1, x1, y0}, new float[] {u0, v0, u0, v1, u1, v1, u1, v0});
		}
		float[] corners;
		if (x0 <= this.cx - this.r && y0 <= this.cy - this.r && x1 >= this.cx + this.r && y1 >= this.cy + this.r) {
			corners = this.disc();
		} else {
			int count = this.cut(x0, y0, x1, y1);
			if (count < 3) {
				return null;
			}
			corners = fan(shape, count);
		}
		float du = (u1 - u0) / (x1 - x0);
		float dv = (v1 - v0) / (y1 - y0);
		float[] uvs = new float[corners.length];
		for (int i = 0; i < corners.length; i += 2) {
			uvs[i] = u0 + (corners[i] - x0) * du;
			uvs[i + 1] = v0 + (corners[i + 1] - y0) * dv;
		}
		return new Quads(corners, uvs);
	}

	/** The whole disc, as the corners of its quads. The same array each time: don't change it. */
	public float[] disc() {
		if (this.disc == null) {
			this.disc = fan(this.points, SIDES);
		}
		return this.disc;
	}

	/**
	 * The band between this circle's edge and a circle {@code width} smaller inside it, as the
	 * corners of its quads. A new array each time.
	 */
	public float[] ring(float width) {
		float inner = Math.max(0, this.r - width);
		float[] corners = new float[SIDES * 8];
		int out = 0;
		for (int i = 0; i < SIDES; i++) {
			int j = (i + 1) % SIDES;
			// Along the outside in the circle's turning order, then back along the inside: counter-clockwise
			// on screen, like disc()'s quads, since the GUI culls the other winding.
			corners[out++] = this.cx + this.r * UNIT[i * 2];
			corners[out++] = this.cy + this.r * UNIT[i * 2 + 1];
			corners[out++] = this.cx + this.r * UNIT[j * 2];
			corners[out++] = this.cy + this.r * UNIT[j * 2 + 1];
			corners[out++] = this.cx + inner * UNIT[j * 2];
			corners[out++] = this.cy + inner * UNIT[j * 2 + 1];
			corners[out++] = this.cx + inner * UNIT[i * 2];
			corners[out++] = this.cy + inner * UNIT[i * 2 + 1];
		}
		return corners;
	}

	/**
	 * The part of a convex polygon inside the circle, as the corners of its quads in the polygon's
	 * own turning order, or null when none of it is. For the shapes drawings are made of.
	 */
	public float @Nullable [] clip(float[] polygon, int count) {
		boolean inside = true;
		for (int k = 0; k < count && inside; k++) {
			inside = this.contains(polygon[k * 2], polygon[k * 2 + 1]);
		}
		if (inside) {
			return fan(polygon, count);
		}
		int left = this.cut(polygon, count);
		return left < 3 ? null : fan(shape, left);
	}

	/** Cuts the rectangle by each side of the polygon it crosses, into {@link #shape}. Returns its corner count. */
	private int cut(float x0, float y0, float x1, float y1) {
		return this.cut(new float[] {x0, y0, x0, y1, x1, y1, x1, y0}, 4);
	}

	/** Cuts a convex polygon by each side of the circle's it crosses, into {@link #shape}. Returns its corner count. */
	private int cut(float[] polygon, int corners) {
		if (shape.length < (corners + SIDES) * 2) {
			shape = new float[(corners + SIDES) * 2];
			kept = new float[(corners + SIDES) * 2];
		}
		System.arraycopy(polygon, 0, shape, 0, corners * 2);
		int count = corners;
		for (int i = 0; i < SIDES && count > 0; i++) {
			int j = (i + 1) % SIDES;
			float ax = this.points[i * 2];
			float ay = this.points[i * 2 + 1];
			float ex = this.points[j * 2] - ax;
			float ey = this.points[j * 2 + 1] - ay;
			// Which side of this edge the centre is on is the inside.
			float inside = Math.signum(ex * (this.cy - ay) - ey * (this.cx - ax));
			// The shape stays inside the polygon, so a side the whole polygon is inside of cuts nothing.
			boolean cuts = false;
			for (int k = 0; k < corners * 2 && !cuts; k += 2) {
				cuts = inside * (ex * (polygon[k + 1] - ay) - ey * (polygon[k] - ax)) < 0;
			}
			if (!cuts) {
				continue;
			}
			int keptCount = 0;
			for (int k = 0; k < count; k++) {
				int n = (k + 1) % count;
				float px = shape[k * 2];
				float py = shape[k * 2 + 1];
				float qx = shape[n * 2];
				float qy = shape[n * 2 + 1];
				float sp = inside * (ex * (py - ay) - ey * (px - ax));
				float sq = inside * (ex * (qy - ay) - ey * (qx - ax));
				if (sp >= 0) {
					kept[keptCount * 2] = px;
					kept[keptCount * 2 + 1] = py;
					keptCount++;
				}
				if (sp >= 0 != sq >= 0) {
					float t = sp / (sp - sq);
					kept[keptCount * 2] = px + (qx - px) * t;
					kept[keptCount * 2 + 1] = py + (qy - py) * t;
					keptCount++;
				}
			}
			float[] swap = shape;
			shape = kept;
			kept = swap;
			count = keptCount;
		}
		return count;
	}

	/** A convex polygon as quads: a fan from its first corner, two triangles to a quad; an odd one left over repeats its last corner. */
	private static float[] fan(float[] polygon, int count) {
		float[] corners = new float[(count - 1) / 2 * 8];
		int out = 0;
		for (int k = 1; k + 1 < count; k += 2) {
			out = corner(polygon, 0, corners, out);
			out = corner(polygon, k, corners, out);
			out = corner(polygon, k + 1, corners, out);
			out = corner(polygon, Math.min(k + 2, count - 1), corners, out);
		}
		return corners;
	}

	private static int corner(float[] polygon, int at, float[] corners, int out) {
		corners[out] = polygon[at * 2];
		corners[out + 1] = polygon[at * 2 + 1];
		return out + 2;
	}
}
