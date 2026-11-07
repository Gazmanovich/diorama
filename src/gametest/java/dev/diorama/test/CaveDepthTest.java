package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Cave view scenes for comparing looks between builds (opt-in: {@code cave-depth}). A room cut in
 * solid stone with terraces up one side, a ledge behind a straight wall, a deep pit with a shelf
 * and lava, a sealed cave under the rock beside it and a staircase out; then a natural cavern and
 * the deepest open space nearby. Minimap and fullscreen map at each. Then jumps and steps in place,
 * logging every change of the minimap's slice.
 */
final class CaveDepthTest {
	private CaveDepthTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int x0 = 3200;
		int z0 = 3200;
		int f = 0;
		world.getServer().runCommand("time set noon");
		// No radar, so pictures of the same spot compare pixel for pixel between builds.
		context.runOnClient(mc -> {
			dev.diorama.Settings.get().radarOnMinimap = false;
			dev.diorama.Settings.get().radarOnMap = false;
		});
		world.getServer().runCommand("tp @a " + x0 + " 200 " + z0);
		context.waitFor(mc -> mc.player.blockPosition().getX() == x0, 200);
		world.getServer().runCommand("forceload add " + (x0 - 40) + " " + (z0 - 40) + " " + (x0 + 40) + " " + (z0 + 40));
		context.waitTicks(80);
		for (int y = -44; y < 28; y += 8) {
			cmd(world, "fill " + (x0 - 32) + " " + y + " " + (z0 - 32) + " " + (x0 + 31) + " " + (y + 7) + " " + (z0 + 31) + " stone");
		}
		// The room: 41 x 41, 5 tall.
		cmd(world, "fill " + (x0 - 20) + " " + f + " " + (z0 - 20) + " " + (x0 + 20) + " " + (f + 4) + " " + (z0 + 20) + " air");
		// Terraces east, each three wide and a block higher.
		for (int k = 1; k <= 6; k++) {
			int xs = x0 + 3 + 3 * k;
			int xe = xs + 2;
			cmd(world, "fill " + xs + " " + f + " " + (z0 - 20) + " " + xe + " " + (f + k - 1) + " " + (z0 - 7) + " stone");
			cmd(world, "fill " + xs + " " + (f + k) + " " + (z0 - 20) + " " + xe + " " + (f + k + 4) + " " + (z0 - 7) + " air");
			cmd(world, "setblock " + (xs + 1) + " " + (f + k) + " " + (z0 - 13) + " torch");
		}
		// A ledge west, six blocks up behind a straight wall.
		cmd(world, "fill " + (x0 - 30) + " " + (f + 6) + " " + (z0 - 20) + " " + (x0 - 21) + " " + (f + 10) + " " + (z0 - 7) + " air");
		cmd(world, "fill " + (x0 - 21) + " " + (f + 5) + " " + (z0 - 20) + " " + (x0 - 21) + " " + (f + 10) + " " + (z0 - 7) + " air");
		cmd(world, "setblock " + (x0 - 25) + " " + (f + 6) + " " + (z0 - 13) + " torch");
		// A pit south, 24 deep, lava at the bottom and a shelf halfway down at its west end.
		cmd(world, "fill " + (x0 - 15) + " " + (f - 24) + " " + (z0 + 8) + " " + (x0 + 15) + " " + (f - 1) + " " + (z0 + 13) + " air");
		cmd(world, "fill " + (x0 - 15) + " " + (f - 24) + " " + (z0 + 8) + " " + (x0 + 15) + " " + (f - 24) + " " + (z0 + 13) + " lava");
		cmd(world, "fill " + (x0 - 15) + " " + (f - 24) + " " + (z0 + 8) + " " + (x0 - 8) + " " + (f - 11) + " " + (z0 + 13) + " stone");
		cmd(world, "setblock " + (x0 - 12) + " " + (f - 10) + " " + (z0 + 10) + " torch");
		// A sealed cave north, under 9 blocks of rock below the room's level, lit.
		cmd(world, "fill " + (x0 - 15) + " " + (f - 8) + " " + (z0 - 31) + " " + (x0 + 15) + " " + (f - 5) + " " + (z0 - 25) + " air");
		for (int x = x0 - 12; x <= x0 + 12; x += 6) {
			cmd(world, "setblock " + x + " " + (f - 8) + " " + (z0 - 28) + " torch");
		}
		// A staircase north out of the room, a block up each step.
		for (int i = 1; i <= 8; i++) {
			int z = z0 - 20 - i;
			cmd(world, "fill " + (x0 - 5) + " " + (f + i - 1) + " " + z + " " + (x0 - 3) + " " + (f + i - 1) + " " + z + " stone");
			cmd(world, "fill " + (x0 - 5) + " " + (f + i) + " " + z + " " + (x0 - 3) + " " + (f + i + 3) + " " + z + " air");
		}
		for (int[] t : new int[][] {{-10, -10}, {10, 10}, {-10, 10}, {10, -2}, {0, 4}}) {
			cmd(world, "setblock " + (x0 + t[0]) + " " + f + " " + (z0 + t[1]) + " torch");
		}

		// Jumps on each terrace step (feet y 0 to 3): one of them sits on a slice edge on any build.
		for (int k = 0; k <= 3; k++) {
			double sx = k == 0 ? x0 + 4.5 : x0 + 4 + 3 * k + 0.5;
			jumps(context, world, sx, f + k, z0 - 13.5, "step " + k);
		}
		stand(context, world, x0 + 0.5, f, z0 + 0.5, "room");
		shots(context, "room");
		stand(context, world, x0 + 4.5, f, z0 - 13.5, "terrace-foot");
		shots(context, "terrace-foot");
		stand(context, world, x0 + 2.5, f, z0 + 6.5, "pit-rim");
		shots(context, "pit-rim");

		stability(context, world, x0 + 0.5, f, z0 + 0.5);

		// Natural ground: a cavern and the deepest open space found near.
		int nx = x0 + 400;
		int nz = z0;
		world.getServer().runCommand("tp @a " + nx + " 200 " + nz);
		context.waitFor(mc -> mc.player.blockPosition().getX() == nx, 200);
		world.getServer().runCommand("forceload add " + (nx - 64) + " " + (nz - 64) + " " + (nx + 64) + " " + (nz + 64));
		context.waitTicks(200);
		BlockPos cavern = world.getServer().computeOnServer(server -> findCavern(server.overworld(), nx, nz));
		BlockPos deep = world.getServer().computeOnServer(server -> findDeep(server.overworld(), nx, nz));
		Diorama.LOGGER.info("cave-depth: cavern {}, deep {}", cavern, deep);
		if (cavern != null) {
			stand(context, world, cavern.getX() + 0.5, cavern.getY(), cavern.getZ() + 0.5, "cavern");
			shots(context, "cavern");
		}
		if (deep != null) {
			stand(context, world, deep.getX() + 0.5, deep.getY(), deep.getZ() + 0.5, "deep");
			shots(context, "deep");
		}
		world.getServer().runCommand("forceload remove all");
	}

	private static void cmd(TestSingleplayerContext world, String command) {
		world.getServer().runCommand(command);
	}

	private static void stand(ClientGameTestContext context, TestSingleplayerContext world, double x, int y, double z, String where) {
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + x + " " + y + " " + z + " 0 30");
		context.waitFor(mc -> mc.player.blockPosition().getY() == y && mc.player.onGround(), 400);
		DioramaClientTest.settle(context, 100);
		log(context, where);
	}

	private static void shots(ClientGameTestContext context, String where) {
		look(context, where, "");
		if (setLegible(context, true)) {
			DioramaClientTest.settle(context, 40);
			look(context, where, "-legible");
			setLegible(context, false);
			DioramaClientTest.settle(context, 40);
		}
	}

	/** Turns Legible cave maps on or off, where the build has the setting; false if it doesn't. */
	private static boolean setLegible(ClientGameTestContext context, boolean on) {
		return context.computeOnClient(mc -> {
			try {
				dev.diorama.Settings.class.getField("legibleCaves").setBoolean(dev.diorama.Settings.get(), on);
				return true;
			} catch (ReflectiveOperationException e) {
				return false;
			}
		});
	}

	private static void look(ClientGameTestContext context, String spot, String suffix) {
		String where = spot + suffix;
		context.takeScreenshot("cave-" + where + "-minimap");
		context.setScreen(MapScreen::new);
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("cave-" + where + "-map");
		int layer = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map ? (Integer) field(map, "layer") : TileKey.SURFACE);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 4.0, layer);
			}
		});
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("cave-" + where + "-map-close");
		context.setScreen(() -> null);
		context.waitTicks(10);
	}

	private static void jumps(ClientGameTestContext context, TestSingleplayerContext world, double x, int y, double z, String where) {
		stand(context, world, x, y, z, "jumps on " + where);
		int start = context.computeOnClient(mc -> MapSession.get().autoLayer(mc.player));
		int[] last = {start};
		int changes = 0;
		StringBuilder seen = new StringBuilder("" + start);
		for (int t = 0; t < 100; t++) {
			context.runOnClient(mc -> {
				if (mc.player.onGround()) {
					mc.player.setDeltaMovement(0, 0.42, 0);
				}
			});
			context.waitTick();
			int now = context.computeOnClient(mc -> MapSession.get().autoLayer(mc.player));
			if (now != last[0]) {
				changes++;
				seen.append(" -> ").append(now);
				last[0] = now;
			}
		}
		Diorama.LOGGER.info("cave-depth: jumping on {} (feet y {}), 100 ticks, slice changes {}: {}", where, y, changes, seen);
		// Standing still can leave the slice a step behind; a jump may catch it up once, no more.
		if (changes > 1) {
			throw new AssertionError("Jumping on " + where + " switched the cave slice " + changes + " times: " + seen);
		}
	}

	/** Jumps in place, then steps up a block at a time and back, logging the slice each tick. */
	private static void stability(ClientGameTestContext context, TestSingleplayerContext world, double x, int f, double z) {
		stand(context, world, x, f, z, "jumps");
		int[] changes = {0};
		int[] last = {context.computeOnClient(mc -> MapSession.get().autoLayer(mc.player))};
		StringBuilder seen = new StringBuilder("" + last[0]);
		for (int t = 0; t < 120; t++) {
			context.runOnClient(mc -> {
				if (mc.player.onGround()) {
					mc.player.setDeltaMovement(0, 0.42, 0);
				}
			});
			context.waitTick();
			int now = context.computeOnClient(mc -> MapSession.get().autoLayer(mc.player));
			if (now != last[0]) {
				changes[0]++;
				seen.append(" -> ").append(now);
				last[0] = now;
			}
		}
		Diorama.LOGGER.info("cave-depth: jumping 120 ticks, slice changes {}: {}", changes[0], seen);
		// Up the staircase north of the room and back down, a step every 5 ticks.
		changes[0] = 0;
		seen.setLength(0);
		seen.append(last[0]);
		int sx = (int) Math.floor(x) - 4;
		int z0 = (int) Math.floor(z);
		for (int pass = 0; pass < 2; pass++) {
			for (int s = 0; s <= 16; s++) {
				int i = pass == 0 ? Math.min(s, 8) : Math.max(0, 8 - s);
				double pz = i == 0 ? z0 - 19.5 : z0 - 20 - i + 0.5;
				world.getServer().runCommand("tp @a " + (sx + 0.5) + " " + (f + i) + " " + pz + " 0 30");
				context.waitTicks(5);
				int now = context.computeOnClient(mc -> MapSession.get().autoLayer(mc.player));
				if (now != last[0]) {
					changes[0]++;
					seen.append(" -> ").append(now).append("@").append(f + i);
					last[0] = now;
				}
			}
		}
		Diorama.LOGGER.info("cave-depth: stairs up and down, slice changes {}: {}", changes[0], seen);
	}

	private static void log(ClientGameTestContext context, String where) {
		String line = context.computeOnClient(mc -> {
			MapSession session = MapSession.get();
			return String.format("%s: feet %.1f %.1f %.1f, underground %s, auto layer %d, caveLayerFor %d",
				where, mc.player.getX(), mc.player.getY(), mc.player.getZ(), session.isUnderground(), session.autoLayer(mc.player), MapSession.caveLayerFor(mc.player.getY()));
		});
		Diorama.LOGGER.info("cave-depth: {}", line);
	}

	/** An air pocket 3 tall with a floor, 20+ blocks of rock above it and the most room around. */
	private static BlockPos findCavern(ServerLevel level, int x0, int z0) {
		BlockPos best = null;
		int bestRoom = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = x0 - 56; x <= x0 + 56; x += 4) {
			for (int z = z0 - 56; z <= z0 + 56; z += 4) {
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = -50; y < Math.min(30, surface - 25); y++) {
					if (!air(level, p.set(x, y, z)) || !air(level, p.set(x, y + 1, z)) || !air(level, p.set(x, y + 2, z)) || !solid(level, p.set(x, y - 1, z))) {
						continue;
					}
					int room = 0;
					for (int dx = -6; dx <= 6; dx += 2) {
						for (int dz = -6; dz <= 6; dz += 2) {
							for (int dy = -6; dy <= 4; dy += 2) {
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
		Diorama.LOGGER.info("cave-depth: cavern {} with room {}", best, bestRoom);
		return best;
	}

	/** A floor next to the tallest drop: a place to stand where open ground goes far below. */
	private static BlockPos findDeep(ServerLevel level, int x0, int z0) {
		BlockPos best = null;
		int bestDrop = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = x0 - 56; x <= x0 + 56; x += 2) {
			for (int z = z0 - 56; z <= z0 + 56; z += 2) {
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = -40; y < Math.min(40, surface - 20); y++) {
					if (!solid(level, p.set(x, y - 1, z)) || !air(level, p.set(x, y, z)) || !air(level, p.set(x, y + 1, z))) {
						continue;
					}
					// The drop just south of this floor.
					int drop = 0;
					while (drop < 40 && air(level, p.set(x, y - 1 - drop, z + 2))) {
						drop++;
					}
					if (drop > bestDrop) {
						bestDrop = drop;
						best = new BlockPos(x, y, z);
					}
				}
			}
		}
		Diorama.LOGGER.info("cave-depth: deep {} with drop {}", best, bestDrop);
		return best;
	}

	private static boolean air(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isAir();
	}

	private static boolean solid(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && state.getFluidState().isEmpty();
	}

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
