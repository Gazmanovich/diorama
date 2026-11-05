package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.BlockLight;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapTile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Night on the map (#204): a sky view tile keeps the block light on it, saved with it, and the map
 * draws it dimmed at night with a glow where the light is. A tile saved before light was kept
 * draws without any, and gets it when it's baked again.
 */
final class DayNightTest {
	/** A floating platform far from the other tests, high enough to be the ground the map shows. */
	private static final int X = 7000;
	private static final int Z = 40;
	private static final int Y = 140;
	private static final int HALF = 24;

	private DayNightTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		// The run pins the map to day for the other tests; a player starts on the world's time.
		Settings.DayNight fresh = context.computeOnClient(mc -> new Settings().dayNight);
		if (fresh != Settings.DayNight.FOLLOW) {
			throw new AssertionError("The map should follow the world's time at first, not " + fresh);
		}
		context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.DAY);
		// The tests after this one look around where the player stands: they get the player back.
		double[] home = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("forceload add " + (X - HALF) + " " + (Z - HALF) + " " + (X + HALF) + " " + (Z + HALF));
		world.getServer().runCommand("fill " + (X - HALF) + " " + Y + " " + (Z - HALF) + " " + (X + HALF) + " " + Y + " " + (Z + HALF) + " minecraft:stone");
		world.getServer().runCommand("setblock " + X + " " + (Y + 1) + " " + Z + " minecraft:glowstone");
		world.getServer().runCommand("setblock " + (X + 12) + " " + (Y + 1) + " " + (Z + 12) + " minecraft:torch");
		world.getServer().runCommand("tp @a " + X + " " + (Y + 2) + " " + (Z - 6));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunk(X >> 4, Z >> 4) != null, 1200);

		TileKey key = tileAt(X, Y + 1, Z);
		lookAt(context);
		// Beside the glowstone, on the platform's top, and far off in its corner, where nothing lights it.
		int[] lit = texel(key, X + 2, Y, Z);
		int[] dark = texel(key, X - HALF + 2, Y, Z - HALF + 2);
		context.waitFor(mc -> {
			MapTile tile = MapSession.get().map().peek(key);
			return tile != null && tile.blockLightAt(lit[0], lit[1]) > 0;
		}, 1200);
		int litValue = context.computeOnClient(mc -> MapSession.get().map().peek(key).blockLightAt(lit[0], lit[1]));
		int darkValue = context.computeOnClient(mc -> MapSession.get().map().peek(key).blockLightAt(dark[0], dark[1]));
		// Glowstone gives 15, one block on 13 or 14; a value is the level times 17.
		if (litValue < 12 * 17 || darkValue != 0) {
			throw new AssertionError("Expected light about level 13 beside the glowstone and none in the corner, got " + litValue / 17.0 + " and " + darkValue / 17.0);
		}
		if (context.computeOnClient(mc -> MapSession.get().map().peek(key).light() == null)) {
			throw new AssertionError("A tile with light should have its light on the GPU");
		}
		TileStore store = context.computeOnClient(mc -> MapSession.get().map().savedMap());
		context.waitFor(mc -> saved(store, key, lit) > 0, 400);

		// By day, at night, and following the world's clock at midnight.
		context.takeScreenshot("day-night-1-day");
		context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.NIGHT);
		context.waitTicks(5);
		context.takeScreenshot("day-night-2-night");
		context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.FOLLOW);
		world.getServer().runCommand("time set midnight");
		context.waitFor(mc -> mc.level.getSkyDarken() >= 10, 200);
		// The map eases into the night over a second and a half.
		context.waitTicks(60);
		context.takeScreenshot("day-night-3-midnight");
		zoomedOut(context);
		context.takeScreenshot("day-night-4-midnight-far");

		// A tile from before light was kept: drawn with none, and lit again once baked again.
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			store.forgetBlockLight(key);
			MapSession.get().map().colorsChanged();
		});
		lookAt(context);
		context.waitFor(mc -> saved(store, key, lit) > 0 && MapSession.get().map().peek(key) != null
			&& MapSession.get().map().peek(key).blockLightAt(lit[0], lit[1]) > 0, 1200);

		context.setScreen(() -> null);
		context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.DAY);
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("forceload remove " + (X - HALF) + " " + (Z - HALF) + " " + (X + HALF) + " " + (Z + HALF));
		world.getServer().runCommand("tp @a " + home[0] + " " + home[1] + " " + home[2]);
		context.waitFor(mc -> mc.player.distanceToSqr(home[0], home[1], home[2]) < 4, 200);
	}

	/** The saved light at a texel of a tile, -1 if it's saved without. */
	private static int saved(TileStore store, TileKey key, int[] texel) {
		TileStore.Saved saved = store.readSaved(key);
		if (saved == null || saved.blockLight() == null) {
			return -1;
		}
		return saved.blockLight()[texel[1] * BlockLight.EDGE + texel[0]] & 0xFF;
	}

	private static void lookAt(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(X, Z), Projection.v(X, Y, Z), 2.0, TileKey.SURFACE));
		DioramaClientTest.settle(context, 40);
	}

	private static void zoomedOut(ClientGameTestContext context) {
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(X, Z), Projection.v(X, Y, Z), 0.5, TileKey.SURFACE));
		DioramaClientTest.settle(context, 20);
	}

	private static TileKey tileAt(int x, int y, int z) {
		return new TileKey(TileKey.SURFACE, Math.floorDiv((int) Math.floor(Projection.u(x, z)), Projection.TILE_UNITS), (int) Math.floor(Projection.v(x, y, z) / Projection.TILE_UNITS));
	}

	/** The light texel showing the top of block (x, y, z): its middle's place on the tile, at a quarter of the pixels. */
	private static int[] texel(TileKey key, int x, int y, int z) {
		double u = Projection.u(x + 0.5, z + 0.5) - key.minU();
		double v = Projection.v(x + 0.5, y + 1, z + 0.5) - key.minV();
		int px = (int) Math.floor(u * Projection.PIXELS_PER_UNIT);
		int py = (int) Math.floor(v * Projection.PIXELS_PER_UNIT);
		return new int[] {px / BlockLight.SCALE, py / BlockLight.SCALE};
	}
}
