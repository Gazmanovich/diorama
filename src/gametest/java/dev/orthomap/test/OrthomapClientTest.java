package dev.orthomap.test;

import dev.orthomap.MapScreen;
import dev.orthomap.MapSession;
import dev.orthomap.MapStyle;
import dev.orthomap.Projection;
import dev.orthomap.bake.TileKey;
import dev.orthomap.render.Radar;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.authlib.GameProfile;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.levelgen.Heightmap;

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

			Set<String> tests = selectedTests();
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			settle(context, 100);
			if (tests.contains("styles")) {
				styleTour(context, world);
			}
			if (tests.contains("tour")) {
				tour(context, world);
			}
			if (tests.contains("radar")) {
				radar(context, world);
			}
			if (tests.contains("fog-of-war")) {
				fogOfWar(context, world);
			}
		}
	}

	/** The minimap, the fullscreen map at three zooms, a resource reload and a cave slice. */
	private static void tour(ClientGameTestContext context, TestSingleplayerContext world) {
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
	}

	/** Fog of war after the server's fair-play code, in a scene built for it. */
	private static void fogOfWar(ClientGameTestContext context, TestSingleplayerContext world) {
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
			world.getServer().runCommand("tellraw @a \"\u00a7r\u00a7e\u00a7s\u00a7e\u00a7t\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
	}

	/**
	 * Radar: mobs of each kind and another player around the viewer, one mob high above and one
	 * underground. Then the server's fair-play code must hide the radar, and its no-minimap code
	 * must hide the minimap.
	 */
	private static void radar(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set midnight");
		// Summon relative to where the player actually stands, not mid-fall after spawning, on a
		// cleared flat patch so no mob spawns inside a block and suffocates.
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 4) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
		world.getServer().runCommand("summon husk " + (x + 6) + " " + y + " " + (z - 4) + " " + still);
		world.getServer().runCommand("summon creeper " + (x - 7) + " " + y + " " + (z + 3) + " " + still);
		world.getServer().runCommand("summon wolf " + (x + 4) + " " + y + " " + (z + 6) + " " + still);
		world.getServer().runCommand("summon cow " + (x - 5) + " " + y + " " + (z - 7) + " " + still);
		world.getServer().runCommand("summon villager " + (x + 9) + " " + y + " " + (z + 1) + " " + still);
		world.getServer().runCommand("summon bat " + (x - 2) + " " + (y + 18) + " " + (z + 9) + " " + still);
		world.getServer().runCommand("fill " + (x + 2) + " " + (y - 14) + " " + (z - 9) + " " + (x + 4) + " " + (y - 12) + " " + (z - 7) + " air");
		world.getServer().runCommand("summon zombie " + (x + 3) + " " + (y - 14) + " " + (z - 8) + " " + still);
		// Tests run one real client, so the other player is a stand-in added on the client.
		context.runOnClient(mc -> {
			RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch"));
			other.setId(-424242);
			other.snapTo(x - 3.5, y, z + 8.5);
			mc.level.addEntity(other);
		});
		settle(context, 60);
		context.takeScreenshot("radar-01-minimap");

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 6.0, TileKey.SURFACE);
		settle(context, 40);
		// Hover the husk so its tooltip shows.
		float[] husk = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.entity().getType() == EntityTypes.HUSK) {
					return new float[] {blip.x() * scale, blip.y() * scale};
				}
			}
			StringBuilder found = new StringBuilder();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				found.append(' ').append(blip.entity().getType().toShortString()).append('@').append(blip.entity().blockPosition().toShortString());
			}
			StringBuilder husks = new StringBuilder();
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.HUSK) {
					husks.append(' ').append(entity.blockPosition().toShortString()).append(" alive=").append(entity.isAlive())
						.append(" dy=").append(Math.round(entity.getY() - mc.player.getY()));
				}
			}
			throw new AssertionError("The husk is not on the radar. Player at " + mc.player.blockPosition().toShortString()
				+ ", husks in the level:" + husks + ", radar has:" + found);
		});
		context.getInput().setCursorPos(husk[0], husk[1]);
		context.waitTicks(10);
		context.takeScreenshot("radar-02-map");
		context.setScreen(() -> null);

		world.getServer().runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-03-fair-play-hides-radar");

		world.getServer().runCommand("tellraw @a \"§n§o§m§i§n§i§m§a§p\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-04-no-minimap");
		world.getServer().runCommand("tellraw @a \"§r§e§s§e§t§x§a§e§r§o\"");
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitTicks(10);
	}

	/** Which scenarios to run: ORTHOMAP_TESTS=tour,radar,... (default tour, radar and fog-of-war). */
	private static Set<String> selectedTests() {
		String env = System.getenv("ORTHOMAP_TESTS");
		if ("1".equals(System.getenv("ORTHOMAP_STYLES"))) {
			return Set.of("styles");
		}
		if (env == null || env.isBlank()) {
			return Set.of("tour", "radar", "fog-of-war");
		}
		return Set.of(env.split(","));
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

	/**
	 * Renders the same village under every look in {@link MapStyle#tour()}, close up and zoomed
	 * out, so they can be compared side by side.
	 */
	private static void styleTour(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int surface = world.getServer().computeOnServer(server ->
			server.overworld().getChunk(village).getHeight(Heightmap.Types.MOTION_BLOCKING, village.getX() & 15, village.getZ() & 15));
		world.getServer().runCommand("tp @a " + village.getX() + " " + (surface + 1) + " " + village.getZ());
		context.waitTicks(200);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		settle(context, 100);
		for (MapStyle style : MapStyle.tour()) {
			context.runOnClient(mc -> MapStyle.set(style));
			String name = style.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
			context.setScreen(MapScreen::new);
			lookAtPlayer(context, 3.0, TileKey.SURFACE);
			settle(context, 60);
			context.takeScreenshot("style-" + name + "-near");
			lookAtPlayer(context, 0.8, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("style-" + name + "-far");
			context.setScreen(() -> null);
		}
		context.runOnClient(mc -> MapStyle.set(MapStyle.DEFAULT));
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
