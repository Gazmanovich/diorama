package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * A tile that loses its last light at night frees its light texture as the new bake goes up. The
 * frame that bake lands in may already have drawn the tile with that texture, and the game draws
 * the GUI at the end of the frame: it crashed there with "Texture view Sampler1 (diorama light) has
 * been closed!" on 26.3. This lights and darkens one tile over and over with the map at night, on
 * the minimap and then the full map, so each round frees the texture once.
 */
final class LightFlickerTest {
	/** A floating platform far from the other tests, high enough to be the ground the map shows. */
	private static final int X = 9400;
	private static final int Z = 40;
	private static final int Y = 140;
	private static final int HALF = 24;
	/** Rounds on each of the minimap and the full map; DIORAMA_LIGHT_ROUNDS changes it. */
	private static final int ROUNDS = rounds();

	private LightFlickerTest() {
	}

	private static int rounds() {
		String env = System.getenv("DIORAMA_LIGHT_ROUNDS");
		return env == null || env.isBlank() ? 6 : Integer.parseInt(env.trim());
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		double[] home = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		Settings.DayNight dayNight = context.computeOnClient(mc -> Settings.get().dayNight);
		boolean minimap = context.computeOnClient(mc -> Settings.get().minimap);
		try {
			flicker(context, world);
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				Settings.get().dayNight = dayNight;
				Settings.get().minimap = minimap;
			});
			world.getServer().runCommand("forceload remove " + (X - HALF) + " " + (Z - HALF) + " " + (X + HALF) + " " + (Z + HALF));
			world.getServer().runCommand("tp @a " + home[0] + " " + home[1] + " " + home[2]);
		}
		context.waitFor(mc -> mc.player.distanceToSqr(home[0], home[1], home[2]) < 4, 200);
	}

	private static void flicker(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("forceload add " + (X - HALF) + " " + (Z - HALF) + " " + (X + HALF) + " " + (Z + HALF));
		for (int i = 0; !world.getServer().computeOnServer(server -> loaded(server.overworld())); i++) {
			if (i > 2400) {
				throw new AssertionError("The platform's chunks never loaded");
			}
			context.waitTick();
		}
		world.getServer().runCommand("fill " + (X - HALF) + " " + Y + " " + (Z - HALF) + " " + (X + HALF) + " " + Y + " " + (Z + HALF) + " minecraft:stone");
		world.getServer().runCommand("fill " + (X - HALF) + " " + (Y + 1) + " " + (Z - HALF) + " " + (X + HALF) + " " + (Y + 8) + " " + (Z + HALF) + " minecraft:air");
		world.getServer().runCommand("tp @a " + X + " " + (Y + 2) + " " + (Z - 6));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunk(X >> 4, Z >> 4) != null, 1200);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().dayNight = Settings.DayNight.NIGHT;
			Settings.get().minimap = true;
		});
		// The map eases into the night over a second and a half.
		context.waitTicks(60);

		TileKey key = tileAt(X, Y + 1, Z);
		BlockPos light = new BlockPos(X, Y + 1, Z);
		int freed = 0;
		// The minimap first: it draws at night too, and before 1.21.2 the client only works out
		// light while it draws the world, which the full map stops.
		freed += rounds(context, world, key, light, "minimap");
		//? if >=1.21.2 {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(X, Z), Projection.v(X, Y, Z), 2.0, TileKey.SURFACE));
		DioramaClientTest.settle(context, 20);
		freed += rounds(context, world, key, light, "map");
		//?}
		dev.diorama.Diorama.LOGGER.info("Light flicker: freed the light texture {} times at night without a crash", freed);
	}

	/** Lights the tile and darkens it again, ROUNDS times; how often its light texture was freed. */
	private static int rounds(ClientGameTestContext context, TestSingleplayerContext world, TileKey key, BlockPos light, String on) {
		int freed = 0;
		for (int round = 0; round < ROUNDS; round++) {
			int r = round;
			world.getServer().runCommand("setblock " + light.getX() + " " + light.getY() + " " + light.getZ() + " minecraft:glowstone");
			waitForLight(context, key, light, true, on, r);
			// Frames that draw the tile with its light, so the frame that frees it may have drawn it too.
			context.waitTicks(2);
			world.getServer().runCommand("setblock " + light.getX() + " " + light.getY() + " " + light.getZ() + " minecraft:air");
			waitForLight(context, key, light, false, on, r);
			freed++;
			dev.diorama.Diorama.LOGGER.info("Light flicker: round {} on the {} freed its light texture", r, on);
		}
		return freed;
	}

	private static void waitForLight(ClientGameTestContext context, TileKey key, BlockPos light, boolean lit, String on, int round) {
		for (int i = 0; context.computeOnClient(mc -> {
			MapTile tile = MapSession.get().map().peek(key);
			// Darkened means the same tile went up again without light, not that it went away.
			return lit ? tile == null || tile.light() == null : tile == null || tile.texture() == null || tile.light() != null;
		}); i++) {
			if (i > 1200) {
				throw new AssertionError("On the " + on + ", round " + round + ": the tile's light texture never " + (lit ? "came" : "went")
					+ "; client light beside it " + context.computeOnClient(mc -> mc.level.getBrightness(LightLayer.BLOCK, light.east())));
			}
			context.waitTick();
		}
	}

	/** Whether every chunk of the platform is loaded on the server. */
	private static boolean loaded(net.minecraft.server.level.ServerLevel level) {
		for (int cx = X - HALF >> 4; cx <= X + HALF >> 4; cx++) {
			for (int cz = Z - HALF >> 4; cz <= Z + HALF >> 4; cz++) {
				if (level.getChunkSource().getChunkNow(cx, cz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static TileKey tileAt(int x, int y, int z) {
		return new TileKey(TileKey.SURFACE, Math.floorDiv((int) Math.floor(Projection.u(x, z)), Projection.TILE_UNITS), (int) Math.floor(Projection.v(x, y, z) / Projection.TILE_UNITS));
	}
}
