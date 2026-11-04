package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Caves under a roof, and the open map following the player (opt-in: {@code roof-caves}). A
 * closed house keeps the surface view with Prefer the surface on, and goes to caves with it off,
 * as before. A natural cave shows caves. Climbing a mined staircase from that cave to the open
 * sky with the map open, the map ends up on the surface by itself.
 */
final class RoofCavesTest {
	private RoofCavesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int x0 = 2400;
		int z0 = 2400;
		context.runOnClient(mc -> Settings.get().preferSurface = true);

		// A closed 9x9 oak house, 6 tall, on cleared ground, nothing above its roof.
		world.getServer().runCommand("tp @a " + x0 + " 200 " + z0);
		context.waitFor(mc -> mc.player.blockPosition().getX() == x0, 200);
		context.waitTicks(80);
		int g = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0));
		world.getServer().runCommand("fill " + (x0 - 12) + " " + g + " " + (z0 - 12) + " " + (x0 + 12) + " " + (g + 40) + " " + (z0 + 12) + " air");
		world.getServer().runCommand("fill " + (x0 - 12) + " " + (g - 1) + " " + (z0 - 12) + " " + (x0 + 12) + " " + (g - 1) + " " + (z0 + 12) + " grass_block");
		world.getServer().runCommand("fill " + (x0 - 4) + " " + g + " " + (z0 - 4) + " " + (x0 + 4) + " " + (g + 5) + " " + (z0 + 4) + " oak_planks hollow");
		world.getServer().runCommand("setblock " + x0 + " " + (g + 4) + " " + (z0 + 3) + " wall_torch[facing=north]");
		world.getServer().runCommand("tp @a " + x0 + ".5 " + (g + 1) + " " + z0 + ".5 0 20");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getY() == g + 1, 400);
		DioramaClientTest.settle(context, 100);
		String house = log(context, "house");
		expect(house, "auto layer surface", "In a house with Prefer the surface on, the auto layer left the surface");
		context.takeScreenshot("roof-01-house-minimap");
		context.setScreen(MapScreen::new);
		DioramaClientTest.settle(context, 60);
		expect(log(context, "house, map open"), "map layer surface", "In a house with Prefer the surface on, the map opened on caves");
		context.takeScreenshot("roof-02-house-map");
		context.setScreen(() -> null);
		// Off, the house is underground again, as before the setting.
		context.runOnClient(mc -> Settings.get().preferSurface = false);
		context.waitTicks(5);
		expect(log(context, "house, prefer surface off"), "auto layer cave", "In a house with Prefer the surface off, the auto layer stayed on the surface");
		context.runOnClient(mc -> Settings.get().preferSurface = true);

		// A natural cave nearby: an air pocket with plenty of rock above.
		BlockPos cave = world.getServer().computeOnServer(server -> findCave(server.overworld(), x0, z0));
		if (cave == null) {
			throw new AssertionError("No cave found near " + x0 + "," + z0);
		}
		Diorama.LOGGER.info("roof-caves: cave at {}", cave);
		int cx = cave.getX();
		int cy = cave.getY();
		int cz = cave.getZ();
		// A staircase east from the cave up to the open sky, as a player would mine one.
		world.getServer().runCommand("forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 140) + " " + (cz + 16));
		context.waitTicks(40);
		int steps = 0;
		for (int i = 1; i < 140; i++) {
			int x = cx + i;
			int y = cy + i;
			int surface = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, cz));
			world.getServer().runCommand("fill " + x + " " + (y - 1) + " " + (cz - 1) + " " + x + " " + (y + 3) + " " + (cz + 1) + " stone replace water");
			world.getServer().runCommand("fill " + x + " " + (y - 1) + " " + (cz - 1) + " " + x + " " + (y + 3) + " " + (cz + 1) + " stone replace lava");
			world.getServer().runCommand("setblock " + x + " " + (y - 1) + " " + cz + " stone");
			world.getServer().runCommand("fill " + x + " " + y + " " + cz + " " + x + " " + (y + 2) + " " + cz + " air");
			steps = i;
			if (y > surface + 1) {
				break;
			}
		}
		int stairs = steps;
		Diorama.LOGGER.info("roof-caves: staircase of {} steps", stairs);

		world.getServer().runCommand("tp @a " + cx + ".5 " + cy + " " + cz + ".5 -90 20");
		context.waitFor(mc -> mc.player.blockPosition().getY() == cy, 400);
		DioramaClientTest.settle(context, 100);
		expect(log(context, "cave"), "auto layer cave", "Deep in a cave, the auto layer stayed on the surface");
		context.takeScreenshot("roof-03-cave-minimap");
		context.setScreen(MapScreen::new);
		DioramaClientTest.settle(context, 60);
		expect(log(context, "cave, map open"), "map layer cave", "Deep in a cave, the map opened on the surface");
		context.takeScreenshot("roof-04-cave-map");

		// Walk up the stairs with the map open, at walking pace: a step every 5 ticks.
		int[] shots = {stairs / 2, stairs - 10, stairs - 6, stairs - 3, stairs};
		int shot = 0;
		for (int i = 1; i <= stairs + 4; i++) {
			int y = cy + Math.min(i, stairs);
			double ex = cx + i + 0.5;
			world.getServer().runCommand("tp @a " + ex + " " + y + " " + cz + ".5 -90 20");
			context.waitTicks(5);
			log(context, "climb step " + i);
			if (shot < shots.length && i == shots[shot]) {
				context.waitTicks(20);
				log(context, "climb step " + i + " +20 ticks");
				context.takeScreenshot("roof-05-climb-" + (shot + 1) + "-step-" + i + "-of-" + stairs);
				shot++;
			}
		}
		context.waitTicks(80);
		expect(log(context, "out, map open"), "map layer surface", "Out of the cave with the map open, the map stayed on caves");
		context.takeScreenshot("roof-06-out-map");
		context.setScreen(() -> null);
		world.getServer().runCommand("forceload remove all");
	}

	private static void expect(String line, String part, String failure) {
		if (!line.contains(part)) {
			throw new AssertionError(failure + ": " + line);
		}
	}

	/** An air pocket at least 3 tall with a floor, 20+ blocks of rock above it and room around. */
	private static BlockPos findCave(ServerLevel level, int x0, int z0) {
		BlockPos best = null;
		int bestRoom = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = x0 - 48; x <= x0 + 48; x += 2) {
			for (int z = z0 - 48; z <= z0 + 48; z += 2) {
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = -50; y < Math.min(30, surface - 25); y++) {
					if (!air(level, p.set(x, y, z)) || !air(level, p.set(x, y + 1, z)) || !air(level, p.set(x, y + 2, z))
						|| !solid(level, p.set(x, y - 1, z))) {
						continue;
					}
					int rock = 0;
					for (int up = y + 3; up < surface; up++) {
						if (solid(level, p.set(x, up, z))) {
							rock++;
						}
					}
					if (rock < 20) {
						continue;
					}
					int room = 0;
					for (int dx = -4; dx <= 4; dx++) {
						for (int dz = -4; dz <= 4; dz++) {
							for (int dy = 0; dy <= 3; dy++) {
								if (air(level, p.set(x + dx, y + dy, z + dz))) {
									room++;
								}
							}
						}
					}
					if (room > bestRoom) {
						bestRoom = room;
						best = new BlockPos(x, y, z);
					}
				}
			}
		}
		Diorama.LOGGER.info("roof-caves: best cave {} with {} air of 324 around", best, bestRoom);
		return bestRoom >= 90 ? best : null;
	}

	private static boolean air(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir();
	}

	private static boolean solid(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && state.getFluidState().isEmpty();
	}

	/**
	 * One line with what decides the layer, the layer the minimap and map ask for (the minimap can
	 * keep the old one on screen for a moment), and the open map's layer; logged and returned.
	 */
	private static String log(ClientGameTestContext context, String where) {
		String line = context.computeOnClient(mc -> {
			MapSession session = MapSession.get();
			BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
			int sky = mc.level.getBrightness(LightLayer.SKY, eye);
			int auto = session.autoLayer(mc.player);
			String screen = mc.gui.screen() instanceof MapScreen map ? layerName(field(map, "layer")) : "closed";
			return String.format("%s: feet y %d, eye sky light %d, underground %s, auto layer %s, map layer %s",
				where, mc.player.blockPosition().getY(), sky, session.isUnderground(), layerName(auto), screen);
		});
		Diorama.LOGGER.info("roof-caves: {}", line);
		return line;
	}

	private static String layerName(Object layer) {
		if (!(layer instanceof Integer value)) {
			return String.valueOf(layer);
		}
		return value == TileKey.SURFACE ? "surface" : "cave " + value;
	}

	/** The open map's layer has no getter: read here only. */
	private static Object field(Object owner, String name) {
		try {
			java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(owner);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("MapScreen has no field " + name, e);
		}
	}
}
