package dev.diorama.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The store-page scene: a castle on a raised mound, with a moat, a gatehouse facing the map's
 * camera, round corner towers under tall spires, a keep with a donjon, and a busy courtyard. The
 * map looks north, so everything that should read well faces south.
 */
final class ShowcaseScene {
	/** Inside this rounded-square distance the mound is flat; it blends into the land by {@link #BLEND}. */
	private static final double FLAT = 37, BLEND = 52;
	private static final String WALL = "stone_bricks";

	private final ServerLevel level;
	private final HolderLookup<Block> blocks;
	private final Map<String, BlockState> states = new HashMap<>();
	private final RandomSource random = RandomSource.create(26);
	private final int cx, cz;
	private int base;
	private String roof = "waxed_oxidized_cut_copper", roofStairs = "waxed_oxidized_cut_copper_stairs";

	private ShowcaseScene(ServerLevel level, int cx, int cz) {
		this.level = level;
		this.blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		this.cx = cx;
		this.cz = cz;
	}

	/** Raises the mound for a castle centred on {@code cx, cz}; {@link #castle} builds on it. */
	static ShowcaseScene site(ServerLevel level, int cx, int cz) {
		ShowcaseScene scene = new ShowcaseScene(level, cx, cz);
		scene.mound();
		scene.moat();
		scene.courtyard();
		scene.road();
		scene.fields();
		scene.trees();
		return scene;
	}

	/** The courtyard's ground height. */
	int base() {
		return base;
	}

	/** Builds, or rebuilds over itself, the castle with the given roof block and its stairs. */
	void castle(String roof, String roofStairs) {
		this.roof = roof;
		this.roofStairs = roofStairs;
		curtainWall();
		keep();
		cornerTowers();
		sideTowers();
		gatehouse();
		outbuildings();
	}

	// --- terrain ---------------------------------------------------------------------------

	private int ground(int x, int z) {
		return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
	}

	private static double roundSquare(int dx, int dz) {
		return Math.pow(Math.pow(Math.abs(dx), 4) + Math.pow(Math.abs(dz), 4), 0.25);
	}

	private void mound() {
		List<Integer> heights = new ArrayList<>();
		for (int dx = -36; dx <= 36; dx += 3) {
			for (int dz = -36; dz <= 36; dz += 3) {
				heights.add(ground(cx + dx, cz + dz));
			}
		}
		heights.sort(null);
		base = heights.get(heights.size() / 2) + 3;
		int reach = (int) BLEND + 6;
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				double d = roundSquare(dx, dz);
				if (d > BLEND + 6) {
					continue;
				}
				int x = cx + dx, z = cz + dz;
				int was = ground(x, z);
				int target;
				if (d <= FLAT) {
					target = base;
				} else if (d <= BLEND) {
					double t = (d - FLAT) / (BLEND - FLAT);
					t = t * t * (3 - 2 * t);
					target = (int) Math.round(Mth.lerp(t, base, was));
				} else {
					target = was;
				}
				for (int y = Math.min(was, target) - 4; y <= target; y++) {
					set(x, y, z, y == target ? "grass_block" : y >= target - 3 ? "dirt" : "stone");
				}
				// Clear trees and anything else above the new ground.
				for (int y = target + 1; y <= target + 40; y++) {
					air(x, y, z);
				}
			}
		}
	}

	private void moat() {
		for (int dx = -32; dx <= 32; dx++) {
			for (int dz = -32; dz <= 32; dz++) {
				int c = Math.max(Math.abs(dx), Math.abs(dz));
				if (c < 27 || c > 31) {
					continue;
				}
				int x = cx + dx, z = cz + dz;
				set(x, base - 4, z, "gravel");
				for (int y = base - 3; y <= base - 1; y++) {
					set(x, y, z, "water");
				}
				air(x, base, z);
			}
		}
		// The drawbridge, lowered across the south moat.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = 25; dz <= 32; dz++) {
				set(cx + dx, base, cz + dz, Math.abs(dx) == 2 ? "stripped_spruce_log[axis=z]" : "spruce_planks");
			}
		}
		for (int dz = 27; dz <= 31; dz += 2) {
			set(cx - 3, base, cz + dz, "spruce_fence");
			set(cx + 3, base, cz + dz, "spruce_fence");
		}
		set(cx - 3, base + 1, cz + 32, "lantern");
		set(cx + 3, base + 1, cz + 32, "lantern");
		set(cx - 3, base, cz + 32, "spruce_fence");
		set(cx + 3, base, cz + 32, "spruce_fence");
	}

	// --- walls and towers ------------------------------------------------------------------

	private String stone() {
		float r = random.nextFloat();
		return r < 0.12F ? "mossy_stone_bricks" : r < 0.22F ? "cracked_stone_bricks" : WALL;
	}

	private void curtainWall() {
		int top = base + 10;
		for (int dx = -24; dx <= 24; dx++) {
			for (int dz = -24; dz <= 24; dz++) {
				int c = Math.max(Math.abs(dx), Math.abs(dz));
				if (c < 22 || c > 24) {
					continue;
				}
				int x = cx + dx, z = cz + dz;
				set(x, base, z, "cobblestone");
				for (int y = base + 1; y < top; y++) {
					set(x, y, z, stone());
				}
				set(x, top, z, c == 23 ? "polished_andesite" : "stone_bricks");
				boolean merlon = ((dx + dz) & 1) == 0;
				if (c == 24 && merlon) {
					set(x, top + 1, z, "stone_bricks");
				}
				if (c == 22 && merlon) {
					set(x, top + 1, z, "stone_brick_wall");
				}
			}
		}
		// A row of arrow slits along the south wall, where the map's camera can see them.
		for (int dx = -18; dx <= 18; dx += 4) {
			if (Math.abs(dx) > 3) {
				set(cx + dx, base + 6, cz + 24, "black_stained_glass_pane");
				set(cx + dx, base + 7, cz + 24, "black_stained_glass_pane");
			}
		}
	}

	private void roundTower(int tx, int tz, double radius, int height, double roofRadius, int roofHeight, String roof) {
		int r = (int) Math.ceil(Math.max(radius, roofRadius));
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				int x = tx + dx, z = tz + dz;
				if (d <= radius) {
					set(x, base, z, "cobblestone");
					for (int y = base + 1; y <= base + height; y++) {
						set(x, y, z, d > radius - 1.5 ? stone() : y == base + height ? "spruce_planks" : "air");
					}
				}
			}
		}
		// Slit windows on the four sides.
		for (int y = base + 5; y < base + height - 2; y += 5) {
			set(tx, y, tz + (int) radius, "black_stained_glass_pane");
			set(tx + (int) radius, y, tz, "black_stained_glass_pane");
			set(tx - (int) radius, y, tz, "black_stained_glass_pane");
		}
		cone(tx, tz, base + height + 1, roofRadius, roofHeight, roof);
	}

	/** A pointed roof that steps in from {@code radius} to a point over {@code height} blocks. */
	private void cone(int tx, int tz, int y0, double radius, int height, String roof) {
		int r = (int) Math.ceil(radius);
		for (int k = 0; k < height; k++) {
			double rk = radius * (1 - (double) k / height);
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d <= rk + 0.3) {
						set(tx + dx, y0 + k, tz + dz, roof);
					}
				}
			}
		}
		set(tx, y0 + height, tz, "lightning_rod");
	}

	private void cornerTowers() {
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				roundTower(cx + 23 * sx, cz + 23 * sz, 5.5, 17, 6.6, 13, roof);
			}
		}
	}

	private void squareTower(int tx, int tz, int half, int height, boolean pyramid) {
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				int x = tx + dx, z = tz + dz;
				boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
				boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
				set(x, base, z, "cobblestone");
				for (int y = base + 1; y <= base + height; y++) {
					set(x, y, z, corner ? "polished_andesite" : edge ? stone() : y == base + height ? "spruce_planks" : "air");
				}
				if (!pyramid && edge && ((dx + dz) & 1) == 0) {
					set(x, base + height + 1, z, "stone_bricks");
				}
			}
		}
		if (pyramid) {
			for (int k = 0; k <= half + 1; k++) {
				int h = half + 1 - k;
				for (int dx = -h; dx <= h; dx++) {
					for (int dz = -h; dz <= h; dz++) {
						set(tx + dx, base + height + 1 + k, tz + dz, roof);
					}
				}
			}
			set(tx, base + height + half + 3, tz, "lightning_rod");
		}
	}

	private void sideTowers() {
		squareTower(cx - 23, cz, 3, 14, true);
		squareTower(cx + 23, cz, 3, 14, true);
		squareTower(cx, cz - 23, 3, 14, true);
	}

	private void gatehouse() {
		squareTower(cx - 5, cz + 23, 3, 16, false);
		squareTower(cx + 5, cz + 23, 3, 16, false);
		// The block over the gate, joining the two towers.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = 21; dz <= 25; dz++) {
				for (int y = base + 1; y <= base + 14; y++) {
					boolean passage = y <= base + 6 && !(y == base + 6 && Math.abs(dx) == 2);
					set(cx + dx, y, cz + dz, passage ? "air" : y == base + 14 ? "polished_andesite" : stone());
				}
				set(cx + dx, base, cz + dz, "cobblestone");
				if (((dx + dz) & 1) == 0 && (dz == 21 || dz == 25)) {
					set(cx + dx, base + 15, cz + dz, "stone_bricks");
				}
			}
			// The portcullis, raised most of the way.
			set(cx + dx, base + 5, cz + 25, "iron_bars");
		}
		// Banners down the gate towers' south faces.
		for (int side = -1; side <= 1; side += 2) {
			for (int y = base + 8; y <= base + 14; y++) {
				set(cx + 5 * side, y, cz + 27, "red_wool");
			}
			set(cx + 5 * side, base + 12, cz + 27, "yellow_wool");
		}
		// The gate's arch and torches.
		set(cx - 2, base + 4, cz + 26, "wall_torch[facing=south]");
		set(cx + 2, base + 4, cz + 26, "wall_torch[facing=south]");
	}

	// --- the keep --------------------------------------------------------------------------

	private void keep() {
		int x0 = cx - 10, x1 = cx + 10, z0 = cz - 17, z1 = cz - 3;
		int top = base + 16;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
				set(x, base, z, "cobblestone");
				for (int y = base + 1; y <= top; y++) {
					set(x, y, z, corner ? "polished_andesite" : edge ? stone() : "air");
				}
			}
		}
		// Tall windows across the south front, and the great door.
		for (int x = x0 + 2; x <= x1 - 2; x += 3) {
			if (Math.abs(x - cx) <= 1) {
				continue;
			}
			for (int y : new int[] {base + 4, base + 5, base + 9, base + 10, base + 13}) {
				set(x, y, z1, "glass_pane");
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int y = base + 1; y <= base + 4; y++) {
				set(cx + dx, y, z1, y == base + 4 && dx != 0 ? WALL : "air");
			}
		}
		set(cx - 2, base + 3, z1 + 1, "wall_torch[facing=south]");
		set(cx + 2, base + 3, z1 + 1, "wall_torch[facing=south]");
		// A gabled roof running east to west, with stairs on both slopes.
		int depth = z1 - z0 + 2;
		for (int k = 0; k <= depth / 2; k++) {
			int south = z1 + 1 - k, north = z0 - 1 + k;
			for (int x = x0 - 1; x <= x1 + 1; x++) {
				if (south == north) {
					set(x, top + 1 + k, south, roof);
					continue;
				}
				set(x, top + 1 + k, south, roofStairs + "[facing=north]");
				set(x, top + 1 + k, north, roofStairs + "[facing=south]");
				for (int z = north + 1; z < south; z++) {
					set(x, top + 1 + k, z, x == x0 - 1 || x == x1 + 1 ? roof : WALL);
				}
			}
		}
		// The donjon rises through the ridge; turrets stand on the front corners.
		roundTower(cx, cz - 10, 4.5, 34, 5.6, 14, roof);
		roundTower(x0, z1, 2.5, 22, 3.3, 8, roof);
		roundTower(x1, z1, 2.5, 22, 3.3, 8, roof);
		for (int y = base + 22; y <= base + 30; y++) {
			set(cx, y, cz - 5, "red_wool");
		}
		set(cx, base + 27, cz - 5, "yellow_wool");
	}

	// --- the courtyard ---------------------------------------------------------------------

	private void courtyard() {
		for (int dx = -21; dx <= 21; dx++) {
			for (int dz = -21; dz <= 21; dz++) {
				int x = cx + dx, z = cz + dz;
				boolean mainPath = Math.abs(dx) <= 1 && dz >= -3;
				boolean crossPath = Math.abs(dz - 8) <= 1;
				if (mainPath || crossPath) {
					set(x, base, z, random.nextFloat() < 0.3F ? "gravel" : "dirt_path");
				} else if (random.nextFloat() < 0.08F) {
					set(x, base + 1, z, random.nextBoolean() ? "short_grass" : "poppy");
				}
			}
		}
		// Lanterns on posts along the main path.
		for (int dz = 0; dz <= 20; dz += 5) {
			for (int side = -1; side <= 1; side += 2) {
				set(cx + 3 * side, base + 1, cz + dz, "spruce_fence");
				set(cx + 3 * side, base + 2, cz + dz, "lantern");
			}
		}
		well(cx + 10, cz + 14);
		stall(cx - 15, cz + 13, "red");
		stall(cx - 9, cz + 13, "blue");
		stall(cx - 15, cz + 3, "yellow");
		for (int[] p : new int[][] {{-6, 18}, {-5, 18}, {-6, 17}, {16, 3}, {17, 3}, {16, 4}}) {
			set(cx + p[0], base + 1, cz + p[1], "hay_block");
		}
		set(cx - 6, base + 2, cz + 18, "hay_block");
		set(cx + 16, base + 2, cz + 3, "hay_block");
		for (int[] p : new int[][] {{6, 19}, {7, 19}, {6, 18}, {-3, 0}}) {
			set(cx + p[0], base + 1, cz + p[1], "barrel[facing=up]");
		}
	}

	private void well(int x, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				set(x + dx, base, z + dz, rim ? "cobblestone" : "water");
				if (rim) {
					set(x + dx, base + 1, z + dz, "cobblestone_wall");
				}
			}
		}
		for (int dx = -2; dx <= 2; dx += 4) {
			for (int dz = -2; dz <= 2; dz += 4) {
				set(x + dx, base + 2, z + dz, "spruce_fence");
				set(x + dx, base + 3, z + dz, "spruce_fence");
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				set(x + dx, base + 4, z + dz, "spruce_slab");
			}
		}
		set(x, base + 5, z, "spruce_slab");
	}

	/** A market stall under a striped awning. */
	private void stall(int x, int z, String color) {
		for (int dx = 0; dx <= 4; dx++) {
			for (int dz = 0; dz <= 3; dz++) {
				boolean post = (dx == 0 || dx == 4) && (dz == 0 || dz == 3);
				if (post) {
					set(x + dx, base + 1, z + dz, "spruce_fence");
					set(x + dx, base + 2, z + dz, "spruce_fence");
				}
				set(x + dx, base + 3, z + dz, (dx & 1) == 0 ? color + "_wool" : "white_wool");
			}
			set(x + dx, base + 1, z + 3, dx == 2 ? "barrel[facing=up]" : "spruce_slab");
		}
	}

	/** Barracks and stables along the inside of the east and west walls. */
	private void outbuildings() {
		lean(cx - 21, cx - 17, cz - 12, cz + 16, true, "dark_oak");
		lean(cx + 17, cx + 21, cz - 12, cz - 1, false, "spruce");
		lean(cx + 17, cx + 21, cz + 7, cz + 16, false, "spruce");
	}

	/** A long low building with a roof that slopes down, away from the wall it stands against. */
	private void lean(int x0, int x1, int z0, int z1, boolean westWall, String wood) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				for (int y = base + 1; y <= base + 4; y++) {
					set(x, y, z, edge ? ((z - z0) % 4 == 0 ? wood + "_log" : wood + "_planks") : "air");
				}
			}
		}
		int width = x1 - x0 + 2;
		for (int k = 0; k < width; k++) {
			int x = westWall ? x1 + 1 - k : x0 - 1 + k;
			int y = base + 5 + k / 2;
			for (int z = z0 - 1; z <= z1 + 1; z++) {
				set(x, y, z, wood + "_stairs[facing=" + (westWall ? "west" : "east") + "]");
			}
		}
		for (int z = z0 + 2; z < z1; z += 4) {
			set(westWall ? x1 : x0, base + 2, z, "glass_pane");
		}
	}

	/** Ripe wheat on both sides of the road, on the land below the mound. */
	private void fields() {
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 3; i <= 16; i++) {
				for (int dz = 58; dz <= 76; dz++) {
					int x = cx + side * i, z = cz + dz;
					int y = surface(x, z);
					boolean rim = i == 3 || i == 16 || dz == 58 || dz == 76;
					if (i == 9 && !rim) {
						set(x, y, z, "water");
					} else if (rim) {
						set(x, y, z, "oak_log[axis=" + (i == 3 || i == 16 ? "z" : "x") + "]");
					} else {
						set(x, y, z, "farmland[moisture=7]");
						set(x, y + 1, z, "wheat[age=7]");
					}
				}
			}
		}
	}

	/** The top solid block of a column, ignoring leaves. */
	private int surface(int x, int z) {
		return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
	}

	/** A ring of oaks and birches around the moat, clear of the road, and two in the courtyard. */
	private void trees() {
		for (int i = 0; i < 26; i++) {
			double angle = i * 2 * Math.PI / 26 + 0.3;
			double r = 40 + random.nextInt(9);
			int x = cx + (int) Math.round(Math.cos(angle) * r), z = cz + (int) Math.round(Math.sin(angle) * r);
			if (z - cz > 30 && Math.abs(x - cx) < 8) {
				continue;
			}
			tree(x, surface(x, z) + 1, z, random.nextInt(4) == 0 ? "birch" : "oak", random.nextInt(3) == 0);
		}
		tree(cx - 15, base + 1, cz - 7, "oak", false);
		tree(cx + 13, base + 1, cz + 3, "birch", false);
	}

	/** A tree like the game grows: a trunk under two wide layers of leaves and two narrow ones. */
	private void tree(int x, int y, int z, String wood, boolean big) {
		int trunk = big ? 6 + random.nextInt(2) : 4 + random.nextInt(2);
		int spread = big ? 3 : 2;
		for (int k = 0; k < trunk; k++) {
			set(x, y + k, z, wood + "_log");
		}
		int top = y + trunk;
		for (int dy = -spread; dy <= 1; dy++) {
			int r = dy >= 0 ? 1 : spread;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					if (corner && (dy == 1 || random.nextBoolean())) {
						continue;
					}
					if (dx == 0 && dz == 0 && dy < 0) {
						continue;
					}
					set(x + dx, top + dy, z + dz, wood + "_leaves[persistent=true]");
				}
			}
		}
	}

	/** A dirt road from the drawbridge south across the land. */
	private void road() {
		for (int dz = 33; dz <= 110; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int x = cx + dx, z = cz + dz;
				int y = surface(x, z);
				String top = level.getBlockState(new BlockPos(x, y, z)).getBlock().getDescriptionId();
				if (top.contains("grass_block") || top.contains("dirt")) {
					set(x, y, z, "dirt_path");
				}
			}
		}
	}

	// --- blocks ----------------------------------------------------------------------------

	private void air(int x, int y, int z) {
		set(x, y, z, "air");
	}

	private void set(int x, int y, int z, String name) {
		level.setBlock(new BlockPos(x, y, z), state(name), Block.UPDATE_CLIENTS);
	}

	private BlockState state(String name) {
		return states.computeIfAbsent(name, n -> {
			try {
				return BlockStateParser.parseForBlock(blocks, "minecraft:" + n, false).blockState();
			} catch (CommandSyntaxException e) {
				throw new IllegalArgumentException("Unknown block " + n, e);
			}
		});
	}
}
