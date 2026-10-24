package dev.diorama.render;

/**
 * A circle on screen, for the round minimap: a many-sided polygon that rectangles are clipped to.
 * The map draws in quads with float corners, so a tile on the circle's edge becomes a few quads
 * that follow it, smooth at any GUI scale, on every version, with no stencil or shader.
 */
public final class Circle {
	/** Sides of the polygon. At the largest minimap a side bows less than a tenth of a GUI pixel from the circle. */
	private static final int SIDES = 96;

	public final float cx;
	public final float cy;
	public final float r;
	/** x, y pairs, in the same turning order as the quads' corners: top left, bottom left, bottom right, top right. */
	private final float[] points = new float[SIDES * 2];

	public Circle(float cx, float cy, float r) {
		this.cx = cx;
		this.cy = cy;
		this.r = r;
		for (int i = 0; i < SIDES; i++) {
			double a = Math.PI * 2 * i / SIDES;
			this.points[i * 2] = (float) (cx + r * Math.cos(a));
			this.points[i * 2 + 1] = (float) (cy - r * Math.sin(a));
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

	/** Receives one quad of a clipped shape: corners and their texture coordinates, as {@link dev.diorama.render.gpu.MapCanvas#quad} takes them. */
	public interface QuadSink {
		void quad(float[] corners, float[] uvs);
	}

	/**
	 * The part of a rectangle inside the circle, as quads, with texture coordinates carried along
	 * from (u0, v0) at the top left to (u1, v1) at the bottom right. A rectangle wholly inside
	 * comes out as itself; one wholly outside as nothing.
	 */
	public void clip(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, QuadSink sink) {
		float nearX = Math.max(x0, Math.min(this.cx, x1)) - this.cx;
		float nearY = Math.max(y0, Math.min(this.cy, y1)) - this.cy;
		if (nearX * nearX + nearY * nearY >= this.r * this.r) {
			return;
		}
		if (this.contains(x0, y0) && this.contains(x1, y0) && this.contains(x0, y1) && this.contains(x1, y1)) {
			sink.quad(new float[] {x0, y0, x0, y1, x1, y1, x1, y0}, new float[] {u0, v0, u0, v1, u1, v1, u1, v0});
			return;
		}
		float[] shape = {x0, y0, x0, y1, x1, y1, x1, y0};
		int count = 4;
		for (int i = 0; i < SIDES && count > 0; i++) {
			int j = (i + 1) % SIDES;
			float ax = this.points[i * 2];
			float ay = this.points[i * 2 + 1];
			float ex = this.points[j * 2] - ax;
			float ey = this.points[j * 2 + 1] - ay;
			// Which side of this edge the centre is on is the inside.
			float inside = Math.signum(ex * (this.cy - ay) - ey * (this.cx - ax));
			float[] kept = new float[(count + 1) * 2];
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
			shape = kept;
			count = keptCount;
		}
		if (count < 3) {
			return;
		}
		float du = (u1 - u0) / (x1 - x0);
		float dv = (v1 - v0) / (y1 - y0);
		// A fan from the first corner, two triangles to a quad; an odd one left over repeats its last corner.
		for (int k = 1; k + 1 < count; k += 2) {
			int c = Math.min(k + 2, count - 1);
			int[] at = {0, k, k + 1, c};
			float[] corners = new float[8];
			float[] uvs = new float[8];
			for (int m = 0; m < 4; m++) {
				float x = shape[at[m] * 2];
				float y = shape[at[m] * 2 + 1];
				corners[m * 2] = x;
				corners[m * 2 + 1] = y;
				uvs[m * 2] = u0 + (x - x0) * du;
				uvs[m * 2 + 1] = v0 + (y - y0) * dv;
			}
			sink.quad(corners, uvs);
		}
	}

	/** The whole disc, as quads (texture coordinates unused). */
	public void fill(QuadSink sink) {
		this.clip(this.cx - this.r, this.cy - this.r, this.cx + this.r, this.cy + this.r, 0, 0, 1, 1, sink);
	}
}
