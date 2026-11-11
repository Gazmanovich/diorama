package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Cave maps after coming back (opt-in: {@code cave-return}). The player maps a room under the
 * Nether's roof, a stretch on top of the roof and a room deep in the Overworld, each further than
 * the chunks they load from where they end up, then leaves the dimension and comes back, and
 * later quits and opens the world again. What they mapped has to be on the cave map each time.
 */
final class CaveReturnTest {
	private static final String NETHER = "minecraft:the_nether";
	private static final String OVERWORLD = "minecraft:overworld";
	/** Far enough apart that neither place is loaded from the other. */
	private static final int FAR = 640;
	private static final int ROOM_Y = 64;
	private static final int ROOF_Y = 128;
	private static final int DEEP_X = 2400;
	private static final int DEEP_Y = -20;

	private CaveReturnTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		// The Nether: a room under the roof at A, then the roof over A, then the same at B.
		room(context, world, NETHER, 0, ROOM_Y, 0, "netherrack");
		look(context, "01-nether-room-A-while-there", 0, ROOM_Y, 0, MapSession.caveLayerFor(ROOM_Y));
		go(context, world, NETHER, 0, ROOF_Y, 0);
		world.getServer().runCommand("execute in " + NETHER + " run fill -3 128 -3 3 128 3 gold_block outline");
		DioramaClientTest.settle(context, 100);
		look(context, "02-nether-roof-A-while-there", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y));
		room(context, world, NETHER, FAR, ROOM_Y, 0, "netherrack");
		go(context, world, NETHER, FAR, ROOF_Y, 0);
		DioramaClientTest.settle(context, 100);
		nether(context, "03-moved-away");

		// The Overworld: a room deep down at A, then one at B.
		room(context, world, OVERWORLD, DEEP_X, DEEP_Y, DEEP_X, "stone");
		look(context, "04-overworld-room-A-while-there", DEEP_X, DEEP_Y, DEEP_X, MapSession.caveLayerFor(DEEP_Y));
		room(context, world, OVERWORLD, DEEP_X + FAR, DEEP_Y, DEEP_X, "stone");
		overworld(context, "05-moved-away");

		// Leave each dimension and come back to B.
		go(context, world, NETHER, FAR, ROOF_Y, 0);
		DioramaClientTest.settle(context, 100);
		nether(context, "06-after-dimension-change");
		go(context, world, OVERWORLD, DEEP_X + FAR, DEEP_Y, DEEP_X);
		DioramaClientTest.settle(context, 100);
		overworld(context, "07-after-dimension-change");
		// Quit from the Nether's roof.
		go(context, world, NETHER, FAR, ROOF_Y, 0);
		DioramaClientTest.settle(context, 100);
	}

	static void reopened(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitFor(mc -> mc.player != null && MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		DioramaClientTest.settle(context, 100);
		nether(context, "08-after-rejoin");
		go(context, world, OVERWORLD, DEEP_X + FAR, DEEP_Y, DEEP_X);
		DioramaClientTest.settle(context, 100);
		overworld(context, "09-after-rejoin");
		diskUse(context, NETHER);
		diskUse(context, OVERWORLD);
	}

	/** Logs what each kind of view of a dimension's map takes on disk. */
	private static void diskUse(ClientGameTestContext context, String dimension) {
		java.nio.file.Path folder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(dimension, dev.diorama.Maps.MAIN));
		try (java.util.stream.Stream<java.nio.file.Path> views = java.nio.file.Files.list(folder)) {
			for (java.nio.file.Path view : views.filter(java.nio.file.Files::isDirectory).sorted().toList()) {
				try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(view)) {
					java.util.List<java.nio.file.Path> tiles = files.filter(file -> file.getFileName().toString().endsWith(".omt")).toList();
					long bytes = 0;
					for (java.nio.file.Path tile : tiles) {
						bytes += java.nio.file.Files.size(tile);
					}
					Diorama.LOGGER.info("cave-return: disk {} {}: {} tiles, {} KB", dimension, view.getFileName(), tiles.size(), bytes / 1024);
				}
			}
		} catch (java.io.IOException e) {
			throw new AssertionError("Couldn't measure " + folder, e);
		}
	}

	/** What the map has at A in the Nether, seen from B: the cave view of the room, the roof, and the sky view. */
	private static void nether(ClientGameTestContext context, String step) {
		expect(step + "-nether-room-A-caves", look(context, step + "-nether-room-A-caves", 0, ROOM_Y, 0, MapSession.caveLayerFor(ROOM_Y)));
		expect(step + "-nether-roof-A-caves", look(context, step + "-nether-roof-A-caves", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y)));
		expect(step + "-nether-A-surface", look(context, step + "-nether-A-surface", 0, ROOM_Y, 0, TileKey.SURFACE));
	}

	private static void overworld(ClientGameTestContext context, String step) {
		expect(step + "-overworld-room-A-caves", look(context, step + "-overworld-room-A-caves", DEEP_X, DEEP_Y, DEEP_X, MapSession.caveLayerFor(DEEP_Y)));
	}

	private static void expect(String what, boolean mapped) {
		if (!mapped) {
			throw new AssertionError("cave-return " + what + ": nothing on the map where the player mapped it");
		}
	}

	/** Carves a lit room with a floor around a spot and stands the player in it. */
	private static void room(ClientGameTestContext context, TestSingleplayerContext world, String dimension, int x, int y, int z, String floor) {
		go(context, world, dimension, x, y, z);
		String in = "execute in " + dimension + " run ";
		world.getServer().runCommand(in + "fill " + (x - 8) + " " + (y - 1) + " " + (z - 8) + " " + (x + 8) + " " + (y + 4) + " " + (z + 8) + " " + floor + " hollow");
		world.getServer().runCommand(in + "fill " + (x - 7) + " " + y + " " + (z - 7) + " " + (x + 7) + " " + (y + 3) + " " + (z + 7) + " air");
		world.getServer().runCommand(in + "fill " + (x - 3) + " " + (y - 1) + " " + (z - 3) + " " + (x + 3) + " " + (y - 1) + " " + (z + 3) + " gold_block outline");
		world.getServer().runCommand(in + "setblock " + (x + 5) + " " + y + " " + (z + 5) + " torch");
		world.getServer().runCommand(in + "tp @a " + x + ".5 " + y + " " + z + ".5 0 20");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getY() == y, 400);
		DioramaClientTest.settle(context, 100);
	}

	private static void go(ClientGameTestContext context, TestSingleplayerContext world, String dimension, int x, int y, int z) {
		world.getServer().runCommand("execute in " + dimension + " run tp @a " + x + ".5 " + y + " " + z + ".5 0 20");
		context.waitFor(mc -> mc.level != null && mc.player != null && mc.level.dimension().identifier().toString().equals(dimension)
			&& Math.abs(mc.player.getX() - x) < 2 && MapSession.get() != null, 600);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		context.waitTicks(60);
	}

	/**
	 * Opens the map on a spot and a layer, takes a picture, and says whether the map has anything
	 * drawn at the spot.
	 */
	private static boolean look(ClientGameTestContext context, String name, int x, int y, int z, int layer) {
		double u = Projection.u(x + 0.5, z + 0.5);
		double v = Projection.v(y, z + 0.5);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, 2.0, layer));
		context.waitTicks(40);
		DioramaClientTest.settle(context, 60);
		String found = context.computeOnClient(mc -> {
			TileKey key = new TileKey(layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
			MapTile tile = MapSession.get().map().peek(key);
			if (tile == null || tile.isEmpty()) {
				return "0 (" + (tile == null ? "no tile" : "empty tile") + ")";
			}
			int cells = Projection.COVERAGE_CELLS;
			int cellX = (int) ((u - key.minU()) * cells / Projection.TILE_UNITS);
			int cellY = (int) ((v - key.minV()) * cells / Projection.TILE_UNITS);
			return String.valueOf(tile.coverage(cellX, cellY));
		});
		Diorama.LOGGER.info("cave-return: {} coverage at the spot {}", name, found);
		context.takeScreenshot("cave-return-" + name);
		context.setScreen(() -> null);
		return !found.startsWith("0");
	}
}
