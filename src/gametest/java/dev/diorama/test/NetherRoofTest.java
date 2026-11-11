package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The map on top of the Nether's roof after coming back (opt-in: {@code nether-roof}). The player
 * maps the roof at one place, moves further off than the chunks they load, leaves the Nether and
 * comes back, and later quits and opens the world again: the roof they mapped has to be on the map
 * each time. On the way they look at a cave view under the roof and one in the Overworld, and
 * neither may be written to disk: only the views from on top of the roof are.
 */
final class NetherRoofTest {
	private static final String NETHER = "minecraft:the_nether";
	private static final String OVERWORLD = "minecraft:overworld";
	/** Far enough apart that neither place is loaded from the other. */
	private static final int FAR = 640;
	private static final int ROOM_Y = 64;
	private static final int ROOF_Y = 128;
	private static final int DEEP_X = 2400;
	private static final int DEEP_Y = -20;

	private NetherRoofTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		// A room under the roof at A, seen in the cave view, then the roof over it.
		room(context, world, NETHER, 0, ROOM_Y, 0, "netherrack");
		look(context, "01-room-under-the-roof-while-there", 0, ROOM_Y, 0, MapSession.caveLayerFor(ROOM_Y));
		go(context, world, NETHER, 0, ROOF_Y, 0);
		world.getServer().runCommand("execute in " + NETHER + " run fill -3 128 -3 3 128 3 gold_block");
		DioramaClientTest.settle(context, 100);
		expect("02-roof-while-there", look(context, "02-roof-while-there", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y)));
		go(context, world, NETHER, FAR, ROOF_Y, 0);
		DioramaClientTest.settle(context, 100);
		expect("03-roof-from-far-off", look(context, "03-roof-from-far-off", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y)));

		// Out to a room deep in the Overworld, seen in the cave view, and back to the roof.
		room(context, world, OVERWORLD, DEEP_X, DEEP_Y, DEEP_X, "stone");
		look(context, "04-overworld-cave-while-there", DEEP_X, DEEP_Y, DEEP_X, MapSession.caveLayerFor(DEEP_Y));
		go(context, world, NETHER, FAR, ROOF_Y, 0);
		DioramaClientTest.settle(context, 100);
		expect("05-roof-after-dimension-change", look(context, "05-roof-after-dimension-change", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y)));
		expect("06-surface-after-dimension-change", look(context, "06-surface-after-dimension-change", 0, ROOM_Y, 0, TileKey.SURFACE));
		// The world closes with the player on the roof.
	}

	static void reopened(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitFor(mc -> mc.player != null && MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		DioramaClientTest.settle(context, 100);
		expect("07-roof-after-rejoin", look(context, "07-roof-after-rejoin", 0, ROOF_Y, 0, MapSession.caveLayerFor(ROOF_Y)));
		// Leave once more, so everything the Nether's map saves is on disk.
		go(context, world, OVERWORLD, DEEP_X, 200, DEEP_X);
		context.waitTicks(40);
		onDisk(context);
	}

	/** Only the views from on top of the roof may be saved beside the sky views, and only in the Nether. */
	private static void onDisk(ClientGameTestContext context) {
		int roof = context.computeOnClient(mc -> MapSession.caveLayerFor(ROOF_Y));
		boolean roofSaved = false;
		for (String dimension : List.of(NETHER, OVERWORLD)) {
			Path folder = context.computeOnClient(mc -> Maps.get().tileFolder(dimension, Maps.MAIN));
			try (Stream<Path> views = Files.list(folder)) {
				for (Path view : views.filter(Files::isDirectory).sorted().toList()) {
					String name = view.getFileName().toString();
					long tiles = 0;
					long bytes = 0;
					try (Stream<Path> files = Files.walk(view)) {
						for (Path file : files.filter(file -> file.getFileName().toString().endsWith(".omt")).toList()) {
							tiles++;
							bytes += Files.size(file);
							String height = file.getParent().getFileName().toString();
							if (!name.startsWith("on-roof-")) {
								continue;
							}
							if (dimension.equals(OVERWORLD) || Integer.parseInt(height) < roof) {
								throw new AssertionError("A cave view that isn't from on top of the Nether's roof was saved: " + file);
							}
							roofSaved = true;
						}
					}
					if (!name.startsWith("on-roof-") && !name.startsWith("surface") && !name.startsWith("under-roof-")) {
						throw new AssertionError("The map saved a kind of view the test doesn't know: " + view);
					}
					Diorama.LOGGER.info("nether-roof: on disk, {} {}: {} tiles, {} KB", dimension, name, tiles, bytes / 1024);
				}
			} catch (IOException e) {
				throw new AssertionError("Couldn't look through " + folder, e);
			}
		}
		if (!roofSaved) {
			throw new AssertionError("Nothing was saved for the view from on top of the Nether's roof");
		}
	}

	private static void expect(String what, boolean mapped) {
		if (!mapped) {
			throw new AssertionError("nether-roof " + what + ": nothing on the map where the player mapped it");
		}
	}

	/** Carves a lit room with a floor around a spot and stands the player in it. */
	private static void room(ClientGameTestContext context, TestSingleplayerContext world, String dimension, int x, int y, int z, String floor) {
		go(context, world, dimension, x, y, z);
		String in = "execute in " + dimension + " run ";
		world.getServer().runCommand(in + "fill " + (x - 8) + " " + (y - 1) + " " + (z - 8) + " " + (x + 8) + " " + (y + 4) + " " + (z + 8) + " " + floor + " hollow");
		world.getServer().runCommand(in + "fill " + (x - 7) + " " + y + " " + (z - 7) + " " + (x + 7) + " " + (y + 3) + " " + (z + 7) + " air");
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
		Diorama.LOGGER.info("nether-roof: {} coverage at the spot {}", name, found);
		context.takeScreenshot("nether-roof-" + name);
		context.setScreen(() -> null);
		return !found.startsWith("0");
	}
}
