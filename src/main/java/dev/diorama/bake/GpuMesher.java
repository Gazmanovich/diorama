package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;

/**
 * Builds the block faces a surface tile shows, for drawing it on the GPU instead of tracing it.
 *
 * <p>The camera looks north and down, so a camera ray only ever moves down and north: it can
 * enter a block through its top or its south face, never another. A cell is reached when the cell
 * above or the cell south of it was reached and let the ray through. Walking each column of x
 * that way, top to bottom and south to north, finds exactly the faces the tracer's camera rays
 * can hit, without looking at anything buried. Faces of chunks the map has no data for are kept
 * as "unknown", so their pixels stay unknown the way a traced ray that runs into one does.
 *
 * <p>Everything that belongs to a spot in the world rather than to the camera is worked out here,
 * once per face corner: colour with biome tint, corner ambient occlusion and sky occlusion. The
 * GPU adds the sun, its shadow, and the style's grade.
 */
public final class GpuMesher {
	/** Faces the camera can see. */
	public static final int TOP = 0;
	public static final int SOUTH = 1;
	/** Faces only the shadow map needs: they face the sun, which is in the west. */
	public static final int WEST = 2;

	/** {@link Quads#flags} bits. */
	public static final int UNKNOWN = 1;
	public static final int LAVA = 2;

	private GpuMesher() {
	}

	/** A growable list of faces, eight ints each: see {@link #add}. */
	public static final class Quads {
		public int[] data = new int[8 * 1024];
		public int count;

		/**
		 * One face of block {@code (x, y, z)}. {@code color} is sRGB {@code 0xAARRGGBB} where alpha
		 * carries the face's own weight (plants and glass) or emission; {@code ao} and {@code sky}
		 * pack the four corners a byte each, in the order of {@link GpuMesher#corners}.
		 */
		void add(int x, int y, int z, int face, int color, int ao, int sky, int flags) {
			int i = this.count * 8;
			if (i + 8 > this.data.length) {
				this.data = java.util.Arrays.copyOf(this.data, this.data.length * 2);
			}
			this.data[i] = x;
			this.data[i + 1] = y;
			this.data[i + 2] = z;
			this.data[i + 3] = face;
			this.data[i + 4] = color;
			this.data[i + 5] = ao;
			this.data[i + 6] = sky;
			this.data[i + 7] = flags;
			this.count++;
		}
	}

	/**
	 * Each thread's face lists, reused by its next build: a tile's lists run to several MB, and
	 * making new ones for every bake kept the collector busy. So a mesh's faces only last until
	 * the same thread builds another; pack them first ({@code GpuTileBaker.submit} does).
	 */
	private static final ThreadLocal<Quads[]> LISTS = ThreadLocal.withInitial(() -> new Quads[] {new Quads(), new Quads(), new Quads(), new Quads()});

	/** Everything one tile draws, plus the region its shadow casters cover. */
	public static final class Mesh {
		public final TileKey key;
		/** One above the tallest block any camera ray of this tile meets; rays start here. */
		public final int topY;
		/** Lowest block y in the dimension. */
		public final int minY;
		public final double sin;
		public final double cos;
		/** Solid and lava faces, and faces of unknown chunks. */
		public final Quads opaque;
		/** Water a ray enters, through its top or south face. */
		public final Quads water;
		/** Plants and glass, which tint what is behind them; sorted far to near before drawing. */
		public final Quads tinted;
		/** Sun-facing faces of solid blocks, for the shadow map. */
		public final Quads casters;
		/** Lowest y of a face the camera sees, or {@code Integer.MAX_VALUE} for none. */
		public int lowestY = Integer.MAX_VALUE;
		// The caster region, in blocks.
		public int casterX0;
		public int casterX1;
		public int casterZ0;
		public int casterZ1;
		public int casterY0;
		public int casterY1;
		/** Time spent building, for the experiment's numbers. */
		public long buildNanos;
		public long skyNanos;

		Mesh(TileKey key, int topY, int minY, double sin, double cos) {
			Quads[] lists = LISTS.get();
			for (Quads list : lists) {
				list.count = 0;
			}
			this.opaque = lists[0];
			this.water = lists[1];
			this.tinted = lists[2];
			this.casters = lists[3];
			this.key = key;
			this.topY = topY;
			this.minY = minY;
			this.sin = sin;
			this.cos = cos;
		}
	}

	/** The z of the first and last cell of row {@code y} whose map rows overlap {@code [minV, maxV)}. */
	private static int zLow(double minV, int y, double sin, double cos) {
		return (int) Math.floor((minV + y * cos) / sin) - 1;
	}

	private static int zHigh(double maxV, int y, double sin, double cos) {
		return (int) Math.floor((maxV + (y + 1) * cos) / sin) + 1;
	}

	public static Mesh build(TileKey key, ChunkSource source, BlockLooks looks) {
		long start = System.nanoTime();
		TileBaker helper = TileBaker.forMeshing(key, source, looks);
		VoxelReader reader = helper.reader;
		MapStyle style = MapStyle.get();
		double sin = Projection.sin();
		double cos = Projection.cos();
		int topY = helper.topY;
		int minY = source.minY();
		Mesh mesh = new Mesh(key, topY, minY, sin, cos);
		boolean skyOcclusion = style.skyOcclusion() > 0;
		Sky sky = new Sky(helper, skyOcclusion);

		double minV = key.minV();
		double maxV = minV + Projection.TILE_UNITS;
		int x0 = (int) Math.floor(key.minU());
		int x1 = x0 + Projection.TILE_UNITS;
		int zBase = zLow(minV, minY, sin, cos) - 2;
		int zSpan = zHigh(maxV, topY, sin, cos) - zBase + 3;
		boolean[] above = new boolean[zSpan];
		boolean[] here = new boolean[zSpan];
		// Whether the reached cell was water, so only the face a ray first enters water by is drawn.
		boolean[] aboveWater = new boolean[zSpan];
		boolean[] hereWater = new boolean[zSpan];
		int lowest = Integer.MAX_VALUE;

		// One column more each side: the bake draws a pixel past the tile's edges for outlines.
		for (int x = x0 - 1; x <= x1; x++) {
			// Above the tallest block every cell is open: that is where the rays start.
			java.util.Arrays.fill(above, true);
			java.util.Arrays.fill(aboveWater, false);
			for (int y = topY - 1; y >= minY; y--) {
				int lo = zLow(minV, y, sin, cos);
				int hi = zHigh(maxV, y, sin, cos);
				java.util.Arrays.fill(here, false);
				java.util.Arrays.fill(hereWater, false);
				boolean any = false;
				for (int z = hi; z >= lo; z--) {
					int i = z - zBase;
					boolean fromAbove = above[i];
					boolean fromSouth = z < hi && here[i + 1];
					if (!fromAbove && !fromSouth) {
						continue;
					}
					int id = reader.state(x, y, z);
					if (id == VoxelReader.UNKNOWN) {
						if (fromAbove) {
							mesh.opaque.add(x, y, z, TOP, 0, 0, 0, UNKNOWN);
						}
						if (fromSouth) {
							mesh.opaque.add(x, y, z, SOUTH, 0, 0, 0, UNKNOWN);
						}
						continue;
					}
					byte kind = looks.kind[id];
					switch (kind) {
						case BlockLooks.SOLID, BlockLooks.LAVA -> {
							int flags = kind == BlockLooks.LAVA ? LAVA : 0;
							int emission = looks.emission[id];
							if (fromAbove) {
								mesh.opaque.add(x, y, z, TOP, solidColor(helper, id, x, y, z, true, emission, kind, style), aoTop(helper, x, y + 1, z), sky.top(x, y + 1, z), flags);
							}
							if (fromSouth) {
								mesh.opaque.add(x, y, z, SOUTH, solidColor(helper, id, x, y, z, false, emission, kind, style), aoSouth(helper, x, y, z + 1), sky.south(x, y, z + 1), flags);
							}
							lowest = Math.min(lowest, y);
						}
						case BlockLooks.WATER -> {
							here[i] = true;
							hereWater[i] = true;
							any = true;
							boolean entersTop = fromAbove && !aboveWater[i];
							boolean entersSouth = fromSouth && !hereWater[i + 1];
							if (entersTop || entersSouth) {
								helper.albedo(id, x, z, true);
								int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, 255);
								// Lit like the tracer: sky over the top of the cell it entered.
								int s = sky.top(x, y + 1, z);
								if (entersTop) {
									mesh.water.add(x, y, z, TOP, color, 0, s, 0);
								}
								if (entersSouth) {
									mesh.water.add(x, y, z, SOUTH, color, 0, s, 0);
								}
							}
						}
						case BlockLooks.PLANT, BlockLooks.GLASS -> {
							here[i] = true;
							any = true;
							helper.albedo(id, x, z, true);
							int weight = kind == BlockLooks.PLANT ? 102 : 64;
							int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, weight);
							if (fromAbove) {
								mesh.tinted.add(x, y, z, TOP, color, 0, sky.top(x, y + 1, z), 0);
							}
							if (fromSouth) {
								mesh.tinted.add(x, y, z, SOUTH, color, 0, sky.south(x, y, z + 1), 0);
							}
						}
						default -> {
							here[i] = true;
							any = true;
						}
					}
				}
				boolean[] t = above;
				above = here;
				here = t;
				t = aboveWater;
				aboveWater = hereWater;
				hereWater = t;
				if (!any) {
					break;
				}
			}
		}
		mesh.lowestY = lowest;
		mesh.skyNanos = sky.nanos;
		casters(mesh, reader, style, x0, x1, minV, maxV, minY);
		sortFarToNear(mesh.tinted, sin, cos);
		mesh.buildNanos = System.nanoTime() - start;
		return mesh;
	}

	/**
	 * Sun-facing faces of solid blocks that can shade this tile: west of it, since the sun is in
	 * the west, and a little south. Each column is scanned from its top down to the lowest face
	 * the camera sees; a block more than {@code SHADOW_DISTANCE} away along the sun can't shade
	 * anything anyway.
	 */
	private static void casters(Mesh mesh, VoxelReader reader, MapStyle style, int x0, int x1, double minV, double maxV, int minY) {
		double elevation = Math.toRadians(style.sunElevation());
		double flat = TileBaker.SHADOW_DISTANCE * Math.cos(elevation);
		int reachX = (int) Math.ceil(flat * 0.878) + 1;
		int reachZ = (int) Math.ceil(flat * 0.479) + 1;
		int reachY = (int) Math.ceil(TileBaker.SHADOW_DISTANCE * Math.sin(elevation)) + 1;
		int cx0 = x0 - reachX;
		int cx1 = x1 + 2;
		// With nothing solid in view the region still has to exist: it places the tile's origin.
		int bottom = mesh.lowestY == Integer.MAX_VALUE ? Math.max(minY, mesh.topY - 1) : Math.max(minY, mesh.lowestY);
		int cz0 = zLow(minV, bottom, mesh.sin, mesh.cos) - 1;
		int cz1 = zHigh(maxV, mesh.topY, mesh.sin, mesh.cos) + reachZ;
		int highest = Integer.MIN_VALUE;
		for (int x = cx0; x <= cx1 && mesh.lowestY != Integer.MAX_VALUE; x++) {
			for (int z = cz0; z <= cz1; z++) {
				ChunkSnapshot chunk = reader.snapshot(x, z);
				if (chunk == null) {
					continue;
				}
				// One above the column's top block: nothing above it casts.
				int top = Math.min(chunk.surface(x & 15, z & 15) + 1, mesh.topY + reachY);
				for (int y = top; y >= bottom; y--) {
					if (reader.solid(x, y, z) != 1) {
						continue;
					}
					if (reader.solid(x, y + 1, z) != 1) {
						mesh.casters.add(x, y, z, TOP, 0, 0, 0, 0);
						highest = Math.max(highest, y + 1);
					}
					if (reader.solid(x - 1, y, z) != 1) {
						mesh.casters.add(x, y, z, WEST, 0, 0, 0, 0);
					}
					if (reader.solid(x, y, z + 1) != 1) {
						mesh.casters.add(x, y, z, SOUTH, 0, 0, 0, 0);
					}
				}
			}
		}
		mesh.casterX0 = cx0;
		mesh.casterX1 = cx1 + 1;
		mesh.casterZ0 = cz0;
		mesh.casterZ1 = cz1 + 1;
		mesh.casterY0 = bottom;
		mesh.casterY1 = Math.max(highest, mesh.topY);
	}

	/** Sorts faces by camera depth, farthest first, so blending them in order matches the tracer. */
	private static void sortFarToNear(Quads quads, double sin, double cos) {
		int n = quads.count;
		if (n < 2) {
			return;
		}
		long[] order = new long[n];
		for (int q = 0; q < n; q++) {
			int i = q * 8;
			double y = quads.data[i + 1] + (quads.data[i + 3] == TOP ? 1 : 0.5);
			double z = quads.data[i + 2] + (quads.data[i + 3] == TOP ? 0.5 : 1);
			double depth = -(sin * y + cos * z);
			// Larger depth is farther: sort descending by flipping the sign.
			long d = (long) (-depth * 1024);
			order[q] = (d << 24) | q;
		}
		java.util.Arrays.sort(order);
		int[] sorted = new int[n * 8];
		for (int q = 0; q < n; q++) {
			int from = (int) (order[q] & 0xFFFFFF) * 8;
			System.arraycopy(quads.data, from, sorted, q * 8, 8);
		}
		// Back into the list, which is reused: see LISTS.
		System.arraycopy(sorted, 0, quads.data, 0, n * 8);
	}

	private static int solidColor(TileBaker helper, int id, int x, int y, int z, boolean top, int emission, byte kind, MapStyle style) {
		helper.albedo(id, x, z, top);
		float r = helper.albedoR;
		float g = helper.albedoG;
		float b = helper.albedoB;
		float variation = style.blockVariation();
		if (variation > 0) {
			float nudge = 1 + variation * TileBaker.blockNoise(x, y, z);
			r *= nudge;
			g *= nudge;
			b *= nudge;
		}
		if (kind == BlockLooks.LAVA) {
			float crust = 1 + 0.3F * TileBaker.blockNoise(x, y, z);
			r *= crust;
			g *= crust * crust;
			b *= crust;
		}
		// Emission rides in alpha, 0..15 scaled up.
		return encode(r, g, b, emission * 17);
	}

	/** Linear colour, halved for headroom, to sRGB bytes; alpha as given. */
	private static int encode(float r, float g, float b, int alpha) {
		return (alpha << 24) | (srgb(r * 0.5F) << 16) | (srgb(g * 0.5F) << 8) | srgb(b * 0.5F);
	}

	private static int srgb(float linear) {
		double v = Math.max(0, Math.min(1, linear));
		double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
		return (int) Math.round(s * 255);
	}

	/** The four corners of a face, in the order every packed corner value uses: see {@link #pack}. */
	private static int pack(float a, float b, float c, float d) {
		return (byte4(a) << 24) | (byte4(b) << 16) | (byte4(c) << 8) | byte4(d);
	}

	private static int byte4(float v) {
		return Math.round(Math.max(0, Math.min(1, v)) * 255);
	}

	/** Corner occlusion on a top face, from the eight blocks around the cell {@code (x, y, z)} above it: nw, ne, sw, se. */
	private static int aoTop(TileBaker h, int x, int y, int z) {
		boolean n = h.occludes(x, y, z - 1);
		boolean s = h.occludes(x, y, z + 1);
		boolean w = h.occludes(x - 1, y, z);
		boolean e = h.occludes(x + 1, y, z);
		return pack(
			TileBaker.corner(w, n, h.occludes(x - 1, y, z - 1)),
			TileBaker.corner(e, n, h.occludes(x + 1, y, z - 1)),
			TileBaker.corner(w, s, h.occludes(x - 1, y, z + 1)),
			TileBaker.corner(e, s, h.occludes(x + 1, y, z + 1))
		);
	}

	/** Same for a south face; {@code z} is the open layer in front of it: top west, top east, bottom west, bottom east. */
	private static int aoSouth(TileBaker h, int x, int y, int z) {
		boolean up = h.occludes(x, y + 1, z);
		boolean down = h.occludes(x, y - 1, z);
		boolean w = h.occludes(x - 1, y, z);
		boolean e = h.occludes(x + 1, y, z);
		return pack(
			TileBaker.corner(w, up, h.occludes(x - 1, y + 1, z)),
			TileBaker.corner(e, up, h.occludes(x + 1, y + 1, z)),
			TileBaker.corner(w, down, h.occludes(x - 1, y - 1, z)),
			TileBaker.corner(e, down, h.occludes(x + 1, y - 1, z))
		);
	}

	/** Sky visibility at face corners, through the tracer's own per-corner rays and cache. */
	private static final class Sky {
		private final TileBaker helper;
		private final boolean on;
		long nanos;

		Sky(TileBaker helper, boolean on) {
			this.helper = helper;
			this.on = on;
		}

		/** A top face at height {@code y}: nw, ne, sw, se. */
		int top(int x, int y, int z) {
			if (!this.on) {
				return -1;
			}
			long start = System.nanoTime();
			int packed = pack(this.helper.skyAt(x, y, z), this.helper.skyAt(x + 1, y, z), this.helper.skyAt(x, y, z + 1), this.helper.skyAt(x + 1, y, z + 1));
			this.nanos += System.nanoTime() - start;
			return packed;
		}

		/** A south face whose open layer is {@code z}: top west, top east, bottom west, bottom east. */
		int south(int x, int y, int z) {
			if (!this.on) {
				return -1;
			}
			long start = System.nanoTime();
			int packed = pack(this.helper.skyAt(x, y + 1, z), this.helper.skyAt(x + 1, y + 1, z), this.helper.skyAt(x, y, z), this.helper.skyAt(x + 1, y, z));
			this.nanos += System.nanoTime() - start;
			return packed;
		}
	}
}
