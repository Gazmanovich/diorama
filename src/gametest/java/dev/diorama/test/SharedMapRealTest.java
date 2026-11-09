package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.SavedChunkImport;
import dev.diorama.share.ShareClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Two players on a real server (#289): Alice, on the vanilla client without Diorama, walks east
 * from spawn; then this player joins at spawn and finds the land she walked on their own map, drawn
 * in their own resource pack (the pack in {@code DIORAMA_PACK}, if set), without going there. Then
 * with the map turned east, and at night.
 */
final class SharedMapRealTest {
	/** Where Alice stands, each for a while: simulation distance counts her time in the chunks around. */
	private static final int[] ALICE_X = {96, 224, 352, 480, 608};
	private static final int ALICE_SECONDS = 30;
	private static final Pattern SHARED = Pattern.compile("Shared map with (\\S+): (\\d+) chunks, (\\d+) KB \\((\\d+) bytes a chunk\\) and (\\d+) KB of region tables, (\\d+) not shared, ([\\d.]+) ms");

	private SharedMapRealTest() {
	}

	static void run(ClientGameTestContext context) {
		String pack = System.getenv("DIORAMA_PACK");
		if (pack != null) {
			Path from = Path.of(pack);
			String id = "file/" + from.getFileName();
			context.runOnClient(mc -> {
				try {
					Files.copy(from, mc.getResourcePackDirectory().resolve(from.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			DioramaClientTest.reloadPacks(context, mc -> {
				mc.getResourcePackRepository().reload();
				if (!mc.getResourcePackRepository().addPack(id)) {
					throw new AssertionError("Pack " + id + " didn't load");
				}
			});
		}
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.command("gamerule advance_time false");
			server.command("weather clear");
			server.vanillaPlayerJoins("Alice");
			for (int x : ALICE_X) {
				server.command("tp Alice " + x + " 200 8");
				context.waitTicks(20 * ALICE_SECONDS);
			}
			server.vanillaPlayerLeaves("Alice");
			server.commandAndWait("save-all flush", "Saved the game", 600);

			long joined = System.nanoTime();
			server.join(context);
			context.waitFor(mc -> ShareClient.sharing() && MapSession.get() != null && MapSession.get().savedChunks() != null, 1200);
			int ground = context.computeOnClient(mc -> mc.player.blockPosition().getY());
			int far = ALICE_X[ALICE_X.length - 1];
			if (context.computeOnClient(mc -> MapSession.get().map().chunk(far >> 4, 0) != null)) {
				throw new AssertionError("This player loaded Alice's land itself: this wouldn't show sharing");
			}
			context.setScreen(MapScreen::new);
			// From this player's own land at spawn out over Alice's.
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(300, 8), Projection.v(300, ground, 8), 1, TileKey.SURFACE));
			context.waitFor(mc -> job().drewChunk(far >> 4, 0), 36000);
			long drawn = System.nanoTime();
			Diorama.LOGGER.info("Alice's land drawn on this player's map {} s after joining", Math.round((drawn - joined) / 1e9));
			context.waitFor(mc -> job().idle(), 12000);
			Diorama.LOGGER.info("All shared land drawn {} s after joining", Math.round((System.nanoTime() - joined) / 1e9));
			DioramaClientTest.settle(context, 200);
			context.takeScreenshot("shared-real-north");
			// Up close where this player's own land meets Alice's.
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(120, 8), Projection.v(120, ground, 8), 3, TileKey.SURFACE));
			DioramaClientTest.settle(context, 100);
			context.takeScreenshot("shared-real-seam");

			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(300, 8), Projection.v(300, ground, 8), 1, TileKey.SURFACE));
			context.waitTicks(40);
			context.waitFor(mc -> job().drewChunk(far >> 4, 0) && job().idle(), 36000);
			DioramaClientTest.settle(context, 200);
			context.takeScreenshot("shared-real-east");

			// The test run pins the map to day; a player's map follows the world's time.
			context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.FOLLOW);
			server.command("time set midnight");
			context.waitTicks(200);
			context.takeScreenshot("shared-real-east-night");
			context.runOnClient(mc -> Settings.get().dayNight = Settings.DayNight.DAY);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(3));
			context.waitTicks(40);
			context.setScreen(() -> null);
			RealServer.leave(context);
			context.waitTicks(40);
			String console = server.console();
			Matcher found = SHARED.matcher(console);
			if (!found.find()) {
				throw new AssertionError("The server logged nothing about what it shared");
			}
			Diorama.LOGGER.info("Real server: {}", found.group());
		}
	}

	private static SavedChunkImport job() {
		return MapSession.get().savedChunks();
	}
}
