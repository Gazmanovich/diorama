package dev.diorama.bake;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the GPU baker draws of a block smaller than a cube, worked out once per shape. Boxes and
 * face pieces are packed two ints each, in 128ths of a block: {@code lo} holds x0, y0, z0 and
 * {@code hi} x1, y1, z1, a byte each, low first. A top piece lies in the plane {@code hi.y}, a
 * south piece in the plane {@code hi.z}.
 */
final class ShapeFaces {
	static final int UNIT = 128;
	/** A whole cell, which is what a cube's faces cover. */
	static final int FULL_LO = 0;
	static final int FULL_HI = pack(UNIT, UNIT, UNIT);

	private static final Map<float[], ShapeFaces> CACHE = new ConcurrentHashMap<>();

	/** The top and south faces a camera ray can meet, inner ones (inside another box) cut away: lo, hi pairs. */
	final int[] top;
	final int[] south;
	/** Every box, for the shadow map: lo, hi pairs. */
	final int[] boxes;
	/**
	 * A box fills the cell's floor (a bottom slab, snow, a path): a ray heading down and north
	 * can't leave the cell without meeting it, so the cell stops rays as a cube does.
	 */
	final boolean fillsFloor;
	/** Water's top and south faces in a waterlogged cell, less where the block's face is level with them. */
	final int[] wetTop;
	final int[] wetSouth;

	static int pack(int x, int y, int z) {
		return x | y << 8 | z << 16;
	}

	static ShapeFaces of(float[] boxes) {
		return CACHE.computeIfAbsent(boxes, ShapeFaces::new);
	}

	private ShapeFaces(float[] shape) {
		int n = shape.length / 6;
		int[][] b = new int[n][6];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 6; k++) {
				b[i][k] = Math.max(0, Math.min(UNIT, Math.round(shape[i * 6 + k] * UNIT)));
			}
		}
		this.boxes = new int[n * 2];
		boolean floor = false;
		List<int[]> top = new ArrayList<>();
		List<int[]> south = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int[] a = b[i];
			this.boxes[i * 2] = pack(a[0], a[1], a[2]);
			this.boxes[i * 2 + 1] = pack(a[3], a[4], a[5]);
			floor |= a[0] == 0 && a[3] == UNIT && a[2] == 0 && a[5] == UNIT && a[1] == 0;
			// The top face, less what other boxes cover from above it.
			List<int[]> pieces = new ArrayList<>(List.of(new int[] {a[0], a[3], a[2], a[5]}));
			for (int j = 0; j < n; j++) {
				if (j != i && b[j][1] <= a[4] && b[j][4] > a[4]) {
					subtract(pieces, b[j][0], b[j][3], b[j][2], b[j][5]);
				}
			}
			for (int[] r : pieces) {
				top.add(new int[] {pack(r[0], a[4], r[2]), pack(r[1], a[4], r[3])});
			}
			// The south face, less what other boxes cover south of it.
			pieces = new ArrayList<>(List.of(new int[] {a[0], a[3], a[1], a[4]}));
			for (int j = 0; j < n; j++) {
				if (j != i && b[j][2] <= a[5] && b[j][5] > a[5]) {
					subtract(pieces, b[j][0], b[j][3], b[j][1], b[j][4]);
				}
			}
			for (int[] r : pieces) {
				south.add(new int[] {pack(r[0], r[2], a[5]), pack(r[1], r[3], a[5])});
			}
		}
		this.fillsFloor = floor;
		this.top = flatten(top);
		this.south = flatten(south);
		// The tracer keeps a ray dry where it meets the block right as it comes into the cell:
		// where a top face is level with the cell's top, or a south face with its south side.
		List<int[]> wet = new ArrayList<>(List.of(new int[] {0, UNIT, 0, UNIT}));
		for (int[] a : b) {
			if (a[4] == UNIT) {
				subtract(wet, a[0], a[3], a[2], a[5]);
			}
		}
		List<int[]> wetTop = new ArrayList<>();
		for (int[] r : wet) {
			wetTop.add(new int[] {pack(r[0], UNIT, r[2]), pack(r[1], UNIT, r[3])});
		}
		this.wetTop = flatten(wetTop);
		wet = new ArrayList<>(List.of(new int[] {0, UNIT, 0, UNIT}));
		for (int[] a : b) {
			if (a[5] == UNIT) {
				subtract(wet, a[0], a[3], a[1], a[4]);
			}
		}
		List<int[]> wetSouth = new ArrayList<>();
		for (int[] r : wet) {
			wetSouth.add(new int[] {pack(r[0], r[2], UNIT), pack(r[1], r[3], UNIT)});
		}
		this.wetSouth = flatten(wetSouth);
	}

	/** Takes rectangle [s0, s1) x [t0, t1) out of every piece, [u0, u1, v0, v1] each; keeps what's left. */
	private static void subtract(List<int[]> pieces, int s0, int s1, int t0, int t1) {
		List<int[]> left = new ArrayList<>();
		for (int[] r : pieces) {
			if (s1 <= r[0] || s0 >= r[1] || t1 <= r[2] || t0 >= r[3]) {
				left.add(r);
				continue;
			}
			if (r[0] < s0) {
				left.add(new int[] {r[0], s0, r[2], r[3]});
			}
			if (s1 < r[1]) {
				left.add(new int[] {s1, r[1], r[2], r[3]});
			}
			int m0 = Math.max(r[0], s0);
			int m1 = Math.min(r[1], s1);
			if (r[2] < t0) {
				left.add(new int[] {m0, m1, r[2], t0});
			}
			if (t1 < r[3]) {
				left.add(new int[] {m0, m1, t1, r[3]});
			}
		}
		pieces.clear();
		pieces.addAll(left);
	}

	private static int[] flatten(List<int[]> pairs) {
		int[] out = new int[pairs.size() * 2];
		for (int i = 0; i < pairs.size(); i++) {
			out[i * 2] = pairs.get(i)[0];
			out[i * 2 + 1] = pairs.get(i)[1];
		}
		return out;
	}
}
