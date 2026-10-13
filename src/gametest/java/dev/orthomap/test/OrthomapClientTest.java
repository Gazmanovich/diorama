package dev.orthomap.test;

import dev.orthomap.MapScreen;
import dev.orthomap.MapSession;
import dev.orthomap.Projection;
import dev.orthomap.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;

/**
 * Not an assertion test: it drives the real game through the map's views and saves screenshots,
 * so a person can look at what the map actually draws.
 */
public class OrthomapClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		String seed = System.getProperty("orthomap.seed", "orthomap");
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(seed);
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			String tp = System.getProperty("orthomap.tp");
			if (tp != null) {
				world.getServer().runCommand("tp @a " + tp);
			}

			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			settle(context, 100);
			context.takeScreenshot("orthomap-01-minimap");

			context.setScreen(MapScreen::new);
			settle(context, 60);
			context.takeScreenshot("orthomap-02-map");

			lookAtPlayer(context, 5.0, TileKey.SURFACE);
			settle(context, 40);
			context.takeScreenshot("orthomap-03-map-close");

			lookAtPlayer(context, 0.75, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("orthomap-04-map-far");

			if ("1".equals(System.getenv("ORTHOMAP_GIF"))) {
				// Frames for a GIF: a slow pan east across the edge of the loaded area, so the fog's
				// drift and the zoomed-out map show in motion.
				for (int frame = 0; frame < 60; frame++) {
					double offset = 90 + frame * 1.5;
					context.runOnClient(mc -> {
						if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
							screen.lookAt(mc.player.getX() + offset, Projection.v(mc.player.getY(), mc.player.getZ()), 1.4, TileKey.SURFACE);
						}
					});
					context.waitTicks(3);
					context.takeScreenshot(String.format("orthomap-gif-%03d", frame));
				}
			}

			// A resource reload (F3+T) must rebuild block colours and re-bake what is on screen.
			context.runOnClient(Minecraft::reloadResourcePacks);
			context.waitTicks(60);
			settle(context, 40);
			context.takeScreenshot("orthomap-04b-after-reload");

			int caveY = Integer.getInteger("orthomap.caveY", 24);
			lookAtPlayer(context, 2.0, caveY);
			settle(context, 80);
			context.takeScreenshot("orthomap-05-cave");

			lookAtPlayer(context, 5.0, caveY);
			settle(context, 40);
			context.takeScreenshot("orthomap-06-cave-close");
			context.setScreen(() -> null);

			// Fog of war. Build a known scene deep underground: a straight corridor through solid
			// stone that turns north at its far end into a hidden room. From the corridor's start,
			// the corridor must be revealed and the room must not, because it is around a corner.
			world.getServer().runCommand("fill 100 -42 98 131 -34 120 minecraft:stone");
			world.getServer().runCommand("fill 102 -40 115 128 -38 117 minecraft:air");
			world.getServer().runCommand("fill 126 -40 110 128 -38 114 minecraft:air");
			world.getServer().runCommand("fill 119 -40 100 128 -38 109 minecraft:air");
			world.getServer().runCommand("tellraw @a \"\u00a7f\u00a7a\u00a7i\u00a7r\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
			world.getServer().runCommand("tp @a 103.5 -40 116.5 -90 0");
			settle(context, 100);
			context.takeScreenshot("orthomap-07-fog-of-war-minimap");
			assertSeen(context, 115, -39, 116, true, "corridor in front of the player");
			assertSeen(context, 123, -39, 104, false, "room around the corner");
			showScene(context);
			context.takeScreenshot("orthomap-08-fog-of-war-corridor");
			context.setScreen(() -> null);

			// Walk to the bend: now the room is in sight.
			world.getServer().runCommand("tp @a 127.5 -40 112.5 0 0");
			settle(context, 100);
			assertSeen(context, 123, -39, 104, true, "room once the player reaches the bend");
			showScene(context);
			context.takeScreenshot("orthomap-09-fog-of-war-room");
			context.setScreen(() -> null);
		}
	}

	private static void assertSeen(ClientGameTestContext context, int x, int y, int z, boolean expected, String what) {
		boolean seen = context.computeOnClient(mc -> MapSession.get().map().isSeen(x, y, z));
		if (seen != expected) {
			throw new AssertionError("Expected the " + what + " at " + x + "," + y + "," + z + (expected ? " to be seen" : " to stay hidden"));
		}
	}

	/** Opens the map on the test scene's cave slice. */
	private static void showScene(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(115, Projection.v(-40, 110), 3.5, MapSession.caveLayerFor(-40));
			}
		});
		settle(context, 60);
	}

	private static void lookAtPlayer(ClientGameTestContext context, double zoom, int layer) {
		context.runOnClient(mc -> {
			LocalPlayer player = mc.player;
			if (mc.gui.screen() instanceof MapScreen screen && player != null) {
				screen.lookAt(player.getX(), Projection.v(player.getY(), player.getZ()), zoom, layer);
			}
		});
	}

	/** Waits until no tile has been baking for a while. */
	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		int quiet = 0;
		for (int i = 0; i < 1200 && quiet < 30; i++) {
			context.waitTick();
			boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
			quiet = baking ? 0 : quiet + 1;
		}
	}
}
