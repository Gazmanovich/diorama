package dev.diorama.bake;

import org.jspecify.annotations.Nullable;

/**
 * Which camera rays are still inside the rock below a cut, for {@link GpuMesher}: the tracer's
 * {@code skippingRock}, which a ray keeps through every solid block, smaller ones too, and loses
 * in the first block that isn't solid. In a column of cells the rays run north and down in a
 * plane of constant x, so each has one u = z - y cot, cot the cotangent of the camera's pitch,
 * and a cell's rays still in the rock are ranges of u. A solid block's faces are drawn only where
 * the other rays meet them.
 *
 * <p>The rays through cell (y, z) have u from z - (y + 1) cot to z + 1 - y cot: those coming in
 * through its top first, then those through its south side. Those leaving through its north side
 * come first again, then those through its floor.
 */
final class RockRays {
	/** The ranges a cell holds at most; more send the tile to the tracer. */
	static final int MAX = 4;
	/** Every ray through the cell. */
	static final int ALL = -1;
	/** Pieces {@link #pieces} writes at most. */
	static final int MAX_PIECES = MAX + 1;
	private static final double EPS = 1e-9;

	private RockRays() {
	}

	/**
	 * Into {@code out}, the rays still in the rock coming into cell (y, z): those of the cell
	 * above, {@code aboveN} ranges of {@code above} or {@link #ALL}, through its floor, and those
	 * of the cell south through its north side. Returns how many ranges, {@link #ALL}, or more than
	 * {@link #MAX} when they don't fit.
	 */
	static int enter(double[] out, double @Nullable [] above, int aboveN, double @Nullable [] south, int southN, int y, int z, double cot) {
		if (aboveN == ALL && southN == ALL) {
			return ALL;
		}
		double low = z - (y + 1) * cot;
		double middle = z + 1 - (y + 1) * cot;
		double high = z + 1 - y * cot;
		int n = clip(out, 0, above, aboveN, low, middle);
		if (n > MAX) {
			return n;
		}
		n = clip(out, n, south, southN, middle, high);
		if (n == 1 && out[0] <= low + EPS && out[1] >= high - EPS) {
			return ALL;
		}
		return n;
	}

	/** Appends {@code span}'s ranges within [a, b] to {@code out}'s n, joining touching ones. */
	private static int clip(double[] out, int n, double @Nullable [] span, int spanN, double a, double b) {
		if (spanN == ALL) {
			return add(out, n, a, b);
		}
		for (int k = 0; k < spanN && n <= MAX; k++) {
			double lo = Math.max(span[k * 2], a);
			double hi = Math.min(span[k * 2 + 1], b);
			if (hi - lo > EPS) {
				n = add(out, n, lo, hi);
			}
		}
		return n;
	}

	private static int add(double[] out, int n, double lo, double hi) {
		if (n > 0 && out[n * 2 - 1] >= lo - EPS) {
			out[n * 2 - 1] = Math.max(out[n * 2 - 1], hi);
			return n;
		}
		if (n == MAX) {
			return MAX + 1;
		}
		out[n * 2] = lo;
		out[n * 2 + 1] = hi;
		return n + 1;
	}

	/**
	 * Into {@code out} from {@code at} as lo, hi pairs, the parts of a top piece ({@code top}) or
	 * south piece of cell (y, z) that rays in the rock meet ({@code inside}) or that the others
	 * do. A cell's floor is a top piece at height 0. Returns where the pairs end.
	 */
	static int pieces(int[] out, int at, boolean top, int lo, int hi, double[] rock, int rockN, int y, int z, double cot, boolean inside) {
		if (rockN == 0 || rockN == ALL) {
			if (inside == (rockN == ALL)) {
				out[at] = lo;
				out[at + 1] = hi;
				return at + 2;
			}
			return at;
		}
		// Along the piece, u runs one way: with z on a top piece, against y on a south one.
		double unit = ShapeFaces.UNIT;
		int a = top ? lo >> 16 & 255 : lo >> 8 & 255;
		int b = top ? hi >> 16 & 255 : hi >> 8 & 255;
		double plane = (top ? hi >> 8 & 255 : hi >> 16 & 255) / unit;
		double ua = top ? z + a / unit - (y + plane) * cot : z + plane - (y + b / unit) * cot;
		double ub = top ? z + b / unit - (y + plane) * cot : z + plane - (y + a / unit) * cot;
		boolean touched = false;
		for (int k = 0; k < rockN; k++) {
			if (Math.min(rock[k * 2 + 1], ub) - Math.max(rock[k * 2], ua) > EPS) {
				touched = true;
				break;
			}
		}
		if (!touched) {
			if (!inside) {
				out[at] = lo;
				out[at + 1] = hi;
				return at + 2;
			}
			return at;
		}
		// The rock ranges, in order, or the gaps between them.
		double cursor = ua;
		for (int k = 0; k < rockN; k++) {
			double r0 = Math.max(ua, rock[k * 2]);
			double r1 = Math.min(ub, rock[k * 2 + 1]);
			if (r1 - r0 <= EPS) {
				continue;
			}
			at = inside ? emit(out, at, top, lo, hi, r0, r1, y, z, plane, cot) : emit(out, at, top, lo, hi, cursor, r0, y, z, plane, cot);
			cursor = Math.max(cursor, r1);
		}
		return inside ? at : emit(out, at, top, lo, hi, cursor, ub, y, z, plane, cot);
	}

	/** The piece's part between u0 and u1, if it's at least a 128th wide. */
	private static int emit(int[] out, int at, boolean top, int lo, int hi, double u0, double u1, int y, int z, double plane, double cot) {
		if (u1 - u0 <= EPS) {
			return at;
		}
		double unit = ShapeFaces.UNIT;
		if (top) {
			// u = z + z' - (y + plane) cot, z' growing with u.
			int z0 = clamp((int) Math.round((u0 - z + (y + plane) * cot) * unit), lo >> 16 & 255, hi >> 16 & 255);
			int z1 = clamp((int) Math.round((u1 - z + (y + plane) * cot) * unit), lo >> 16 & 255, hi >> 16 & 255);
			if (z1 <= z0) {
				return at;
			}
			out[at] = lo & ~(255 << 16) | z0 << 16;
			out[at + 1] = hi & ~(255 << 16) | z1 << 16;
		} else {
			// u = z + plane - (y + y') cot, y' falling as u grows.
			int y0 = clamp((int) Math.round(((z + plane - u1) / cot - y) * unit), lo >> 8 & 255, hi >> 8 & 255);
			int y1 = clamp((int) Math.round(((z + plane - u0) / cot - y) * unit), lo >> 8 & 255, hi >> 8 & 255);
			if (y1 <= y0) {
				return at;
			}
			out[at] = lo & ~(255 << 8) | y0 << 8;
			out[at + 1] = hi & ~(255 << 8) | y1 << 8;
		}
		return at + 2;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
