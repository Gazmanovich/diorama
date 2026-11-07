package dev.diorama.bake;

/**
 * Where a cave slice is cut, column by column. The cut is the slice's own height, raised over
 * ground the player can walk up to from the slice's level: steps and slopes that climb a block at
 * a time stay in view, up to {@link #LIFT} blocks, while a wall stays cut. It depends only on the
 * blocks, not on where the player stands, so tiles of the same slice agree.
 */
public final class CaveCut {
	/** The most the cut rises over walkable ground, in blocks. */
	public static final int LIFT = 8;
	/** Columns a climb is followed across; its result for a column depends on no block further away. */
	private static final int REACH = 14;
	/** Head room left over raised ground: the slice's own cut is this far above the floor its player stands on. */
	private static final int CLEARANCE = 4;
	private static final int NONE = Integer.MIN_VALUE;

	private final int base;
	private final int x0;
	private final int z0;
	private final int width;
	private final int depth;
	private final int[] cut;

	/** The cut for the columns from (x0, z0) to (x1, z1) of a slice cut at {@code base}. */
	CaveCut(VoxelReader reader, int base, int x0, int z0, int x1, int z1) {
		this.base = base;
		this.x0 = x0 - REACH;
		this.z0 = z0 - REACH;
		this.width = x1 - x0 + 1 + 2 * REACH;
		this.depth = z1 - z0 + 1 + 2 * REACH;
		int n = this.width * this.depth;
		// The highest place to stand in each column near the slice, or NONE.
		int[] ground = new int[n];
		int lowest = base - CLEARANCE - LIFT;
		int highest = base - CLEARANCE + LIFT;
		for (int j = 0; j < this.depth; j++) {
			for (int i = 0; i < this.width; i++) {
				int x = this.x0 + i;
				int z = this.z0 + j;
				int g = NONE;
				for (int y = highest; y > lowest && g == NONE; y--) {
					if (reader.solid(x, y - 1, z) > 0 && reader.solid(x, y, z) == 0 && reader.solid(x, y + 1, z) == 0) {
						g = y;
					}
				}
				ground[j * this.width + i] = g;
			}
		}
		// Reached by walking from ground the slice already shows, a block up at a time at most.
		boolean[] reached = new boolean[n];
		for (int c = 0; c < n; c++) {
			reached[c] = ground[c] != NONE && ground[c] <= base - CLEARANCE;
		}
		boolean[] next = new boolean[n];
		for (int step = 0; step < REACH; step++) {
			for (int j = 0; j < this.depth; j++) {
				for (int i = 0; i < this.width; i++) {
					int c = j * this.width + i;
					int g = ground[c];
					next[c] = reached[c] || g != NONE && (
						i > 0 && climbs(reached, ground, c - 1, g)
						|| i < this.width - 1 && climbs(reached, ground, c + 1, g)
						|| j > 0 && climbs(reached, ground, c - this.width, g)
						|| j < this.depth - 1 && climbs(reached, ground, c + this.width, g));
				}
			}
			boolean[] t = reached;
			reached = next;
			next = t;
		}
		this.cut = new int[n];
		for (int c = 0; c < n; c++) {
			this.cut[c] = reached[c] ? Math.max(base, ground[c] + CLEARANCE) : base;
		}
	}

	/**
	 * Whether the cut rises anywhere over the columns this was made for. The border around them,
	 * there only so the climb can be followed in from outside, doesn't count: a step out there
	 * changes nothing these columns draw.
	 */
	boolean raises() {
		for (int j = REACH; j < this.depth - REACH; j++) {
			for (int i = REACH; i < this.width - REACH; i++) {
				if (this.cut[j * this.width + i] != this.base) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean climbs(boolean[] reached, int[] ground, int from, int g) {
		return reached[from] && ground[from] != NONE && ground[from] >= g - 1;
	}

	/** The cut over column (x, z): the slice's own outside the columns this was made for. */
	int at(int x, int z) {
		int i = x - this.x0;
		int j = z - this.z0;
		return i < 0 || j < 0 || i >= this.width || j >= this.depth ? this.base : this.cut[j * this.width + i];
	}
}
