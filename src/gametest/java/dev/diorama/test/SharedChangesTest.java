package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.share.ShareClient;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Changes on the shared map: Alice, on the vanilla client, builds far from this player after
 * they've drawn her land, and once the server's autosave has written it, opening the map shows the
 * build with nothing pressed.
 */
final class SharedChangesTest {
	private static final int X = 320;
	private static final int Z = 8;
	/** A red platform high over Alice's land: from the sky it covers the ground under it. */
	private static final int BUILD_Y = 190;
	/** Vanilla's autosave is every 6,000 ticks; the test gives it twice that. */
	private static final int AUTOSAVE_TICKS = 6600;

	private SharedChangesTest() {
	}

	static void run(ClientGameTestContext context) {
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.command("gamerule advance_time false");
			server.command("weather clear");
			server.vanillaPlayerJoins("Alice");
			server.command("tp Alice " + X + " 200 " + Z);
			context.waitTicks(20 * 30);
			server.commandAndWait("save-all flush", "Saved the game", 600);

			server.join(context);
			context.waitFor(mc -> ShareClient.sharing() && MapSession.get() != null && MapSession.get().savedChunks() != null, 1200);
			context.setScreen(MapScreen::new);
			int ground = context.computeOnClient(mc -> mc.player.blockPosition().getY());
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(X, Z), Projection.v(X, ground, Z), 1, TileKey.SURFACE));
			context.waitFor(mc -> MapSession.get().savedChunks().drewChunk(X >> 4, Z >> 4) && MapSession.get().savedChunks().idle(), 36000);
			context.waitTicks(40);
			if (red(context)) {
				throw new AssertionError("The platform shows before Alice built it");
			}
			context.takeScreenshot("shared-changes-before");
			context.setScreen(() -> null);

			// Alice builds far from this player, and the server's own autosave writes it.
			server.command("fill " + (X - 8) + " " + BUILD_Y + " " + (Z - 8) + " " + (X + 7) + " " + BUILD_Y + " " + (Z + 7) + " minecraft:red_wool");
			long built = System.nanoTime();
			// The server's own autosave, as its region file shows: no command saves it.
			int before = server.savedAt(X >> 4, Z >> 4);
			for (int waited = 0; server.savedAt(X >> 4, Z >> 4) == before; waited += 20) {
				if (waited > AUTOSAVE_TICKS * 2) {
					throw new AssertionError("The server never saved the build's chunk");
				}
				context.waitTicks(20);
			}
			Diorama.LOGGER.info("The server's autosave wrote the build's chunk {} s after it was built", Math.round((System.nanoTime() - built) / 1e9));

			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(X, Z), Projection.v(X, BUILD_Y, Z), 1, TileKey.SURFACE));
			long opened = System.nanoTime();
			context.waitFor(mc -> red(), 12000);
			Diorama.LOGGER.info("Alice's build showed {} s after the map opened, {} s after she built it",
				Math.round((System.nanoTime() - opened) / 1e9), Math.round((System.nanoTime() - built) / 1e9));
			context.waitTicks(40);
			context.takeScreenshot("shared-changes-after");
			context.setScreen(() -> null);
			server.vanillaPlayerLeaves("Alice");
			RealServer.leave(context);
		}
	}

	private static boolean red(ClientGameTestContext context) {
		return context.computeOnClient(mc -> red());
	}

	/** Whether the saved map shows the red platform's middle. */
	private static boolean red() {
		double u = Projection.u(X, Z);
		double v = Projection.v(X, BUILD_Y, Z);
		TileKey tile = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		int[] pixels = MapSession.get().map().savedMap().read(tile);
		if (pixels == null) {
			return false;
		}
		int px = Math.floorMod((int) Math.floor(u * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
		int py = Math.floorMod((int) Math.floor(v * Projection.PIXELS_PER_UNIT), Projection.TILE_PIXELS);
		int pixel = pixels[py * Projection.TILE_PIXELS + px];
		// Tiles keep their pixels as ABGR.
		int r = pixel & 0xFF;
		int g = (pixel >> 8) & 0xFF;
		int b = (pixel >> 16) & 0xFF;
		return (pixel >>> 24) != 0 && r > 100 && r > 2 * g && r > 2 * b;
	}
}
