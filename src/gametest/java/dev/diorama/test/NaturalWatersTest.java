package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BiomeTints;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;

/**
 * Water coloured by a mod (opt-in: {@code natural-waters}, with Fuzs' Natural Waters installed):
 * six pools in biomes that mod colours differently from the game, and a row of water cauldrons,
 * on a stone stage in the air. The map, the minimap and the game itself looking down at them. The
 * map's water must follow the biome the way the game's does: by the mod's colour, not the game's.
 */
final class NaturalWatersTest {
	private static final String[] BIOMES = {"warm_ocean", "swamp", "cold_ocean", "jungle", "mangrove_swamp", "desert"};
	/** Each biome's water_surface_color in Natural Waters' biome files. */
	private static final int[] COLORS = {0x02B0E5, 0x4C6559, 0x2080C9, 0x14A2C5, 0x3A7A6A, 0x32A598};
	/** Each biome's square of the stage: wide enough that a pool's middle blends with no other biome. */
	private static final int CELL = 16;

	private NaturalWatersTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		if (BiomeTints.water() == BiomeColors.WATER_COLOR_RESOLVER) {
			throw new AssertionError("DIORAMA_TESTS=natural-waters needs Natural Waters installed, and water that asks for its colour");
		}
		int x0 = 1600;
		int z0 = 1600;
		int y = 120;
		int centerX = x0 + CELL * 3 / 2;
		int centerZ = z0 + CELL;
		// Go there first: fill does nothing where chunks aren't loaded.
		world.getServer().runCommand("tp @a " + centerX + " " + (y + 2) + " " + centerZ);
		context.waitFor(mc -> mc.player.blockPosition().getX() == centerX, 200);
		context.waitTicks(60);
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("fill " + x0 + " " + (y - 4) + " " + z0 + " " + (x0 + CELL * 3 - 1) + " " + (y - 1) + " " + (z0 + CELL * 2 - 1) + " stone");
		for (int i = 0; i < BIOMES.length; i++) {
			int px = x0 + (i % 3) * CELL;
			int pz = z0 + (i / 3) * CELL;
			world.getServer().runCommand("fillbiome " + px + " " + (y - 8) + " " + pz + " " + (px + CELL - 1) + " " + (y + 16) + " " + (pz + CELL - 1) + " minecraft:" + BIOMES[i]);
			world.getServer().runCommand("fill " + (px + 2) + " " + (y - 3) + " " + (pz + 2) + " " + (px + 13) + " " + (y - 1) + " " + (pz + 13) + " water");
			world.getServer().runCommand("fill " + (px + 2) + " " + (y - 1) + " " + (pz + 14) + " " + (px + 13) + " " + (y - 1) + " " + (pz + 14) + " water_cauldron[level=3]");
		}
		world.getServer().runCommand("tp @a " + centerX + " " + y + " " + centerZ);
		context.waitFor(mc -> mc.player.blockPosition().getX() == centerX && mc.player.onGround(), 600);
		settle(context, 60);

		context.takeScreenshot("natural-waters-01-minimap");
		context.setScreen(MapScreen::new);
		lookAt(context, centerX, y, centerZ, 6.0);
		settle(context, 80);
		context.takeScreenshot("natural-waters-02-map");
		String wrong = context.computeOnClient(mc -> {
			BlockLooks looks = BlockLooks.get();
			int water = looks.lookId(Blocks.WATER.defaultBlockState());
			int cauldron = looks.lookId(Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
			StringBuilder out = new StringBuilder();
			if (looks.tint[water] != BlockLooks.TINT_WATER || looks.tint[cauldron] != BlockLooks.TINT_WATER) {
				out.append(String.format(" water's tint is kind %d, a cauldron's %d;", looks.tint[water], looks.tint[cauldron]));
			}
			for (int i = 0; i < BIOMES.length; i++) {
				BlockPos pool = new BlockPos(x0 + (i % 3) * CELL + 7, y - 1, z0 + (i / 3) * CELL + 7);
				ChunkSnapshot snapshot = MapSession.get().map().chunk(pool.getX() >> 4, pool.getZ() >> 4);
				int ours = BiomeTints.of(snapshot).water()[((pool.getZ() & 15) << 4) | (pool.getX() & 15)];
				dev.diorama.Diorama.LOGGER.info("natural-waters: {} ours {}, the mod's {}", BIOMES[i], String.format("%06X", ours), String.format("%06X", COLORS[i]));
				if (ours != COLORS[i]) {
					out.append(String.format(" %s: ours %06X, the mod's %06X;", BIOMES[i], ours, COLORS[i]));
				}
			}
			return out.toString();
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Water on the map doesn't follow the mod's colour:" + wrong);
		}
		context.setScreen(() -> null);

		// The game itself, straight down, facing north as the map does.
		world.getServer().runCommand("gamemode spectator @a");
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = true);
		*///?}
		world.getServer().runCommand("tp @a " + centerX + " " + (y + 34) + " " + centerZ + " 180 90");
		context.waitTicks(100);
		context.takeScreenshot("natural-waters-03-world");
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = false);
		*///?}
		world.getServer().runCommand("gamemode creative @a");
	}

	private static void lookAt(ClientGameTestContext context, double x, double y, double z, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(x, Projection.v(y, z), zoom, TileKey.SURFACE);
			}
		});
	}

	/** Waits until no tile has been baking for a while. */
	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		try (WorldPause paused = WorldPause.pause(context)) {
			int quiet = 0;
			for (int i = 0; i < 1200 && quiet < 30; i++) {
				context.waitTick();
				boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
				quiet = baking ? 0 : quiet + 1;
			}
		}
	}
}
