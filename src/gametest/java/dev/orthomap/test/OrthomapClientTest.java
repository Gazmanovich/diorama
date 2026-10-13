package dev.orthomap.test;

import dev.orthomap.MapScreen;
import dev.orthomap.MapSession;
import dev.orthomap.MapStyle;
import dev.orthomap.Projection;
import dev.orthomap.Settings;
import dev.orthomap.SettingsScreen;
import dev.orthomap.WorldKey;
import dev.orthomap.bake.TileKey;
import dev.orthomap.render.MapViewport;
import dev.orthomap.render.Radar;
import dev.orthomap.waypoint.Waypoint;
import dev.orthomap.waypoint.WaypointEditScreen;
import dev.orthomap.waypoint.WaypointListScreen;
import dev.orthomap.waypoint.WaypointOverlay;
import dev.orthomap.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
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
			if (tests.contains("waypoints")) {
				waypoints(context, world);
			}
			if (tests.contains("fog-of-war")) {
				fogOfWar(context, world);
			}
			if (tests.contains("settings")) {
				settings(context, world);
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

	/**
	 * Waypoints: one nearby and one far off, the in-world label, the map tooltip, adding one by
	 * right-clicking the map, the list, a death marker, and the file on disk.
	 */
	private static void waypoints(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			BlockPos at = mc.player.blockPosition();
			String dimension = mc.level.dimension().identifier().toString();
			int hx = at.getX() + 24;
			int hz = at.getZ() - 14;
			int hy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz);
			store.add(new Waypoint("Home", hx, hy, hz, dimension, Waypoint.PALETTE[5]));
			store.add(new Waypoint("Iron Mine", at.getX() + 400, at.getY(), at.getZ() + 120, dimension, Waypoint.PALETTE[1]));
		});
		context.waitTicks(10);
		context.takeScreenshot("waypoints-01-minimap");

		// Face Home: its label shows in the world with its name and distance.
		BlockPos home = context.computeOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				if (w.name.equals("Home")) {
					return new BlockPos(w.x, w.y, w.z);
				}
			}
			throw new AssertionError("Home is missing");
		});
		context.getInput().lookAt(home);
		context.waitTicks(10);
		context.takeScreenshot("waypoints-02-in-world");

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		settle(context, 30);
		float[] pin = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin p : ((MapScreen) mc.gui.screen()).pins()) {
				if (p.waypoint().name.equals("Home")) {
					return new float[] {p.x() * scale, p.y() * scale};
				}
			}
			throw new AssertionError("Home is not on the map");
		});
		context.getInput().setCursorPos(pin[0], pin[1]);
		context.waitTicks(5);
		context.takeScreenshot("waypoints-03-map");

		// A quick drag that starts on Home must pan, not open its editor, even when it's released
		// before the game dispatches any drag event.
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.getInput().moveCursor(80, 40);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		boolean dragOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (dragOpenedEditor) {
			throw new AssertionError("Dragging from a waypoint opened its editor");
		}
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		context.waitTicks(3);

		// Right-click a spot left of the player: the editor opens on the block shown there, which
		// is the first solid block on the camera ray through that point (a canopy can hide the
		// ground behind it).
		double[] click = context.computeOnClient(mc -> {
			MapScreen screen = (MapScreen) mc.gui.screen();
			MapViewport.View view = screen.view();
			double u = mc.player.getBlockX() - 15 + 0.5;
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			float scale = (float) mc.getWindow().getGuiScale();
			return new double[] {u, v, view.screenX(u) * scale, view.screenY(v) * scale};
		});
		context.getInput().setCursorPos(click[2], click[3]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		boolean editorOpen = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (!editorOpen) {
			throw new AssertionError("Right-clicking the map didn't open the waypoint editor");
		}
		context.waitTicks(5);
		context.takeScreenshot("waypoints-04-editor");
		context.clickScreenButton("orthomap.waypoint.save");
		context.waitTicks(5);
		int count = context.computeOnClient(mc -> WaypointStore.get().all().size());
		if (count != 3) {
			throw new AssertionError("Expected 3 waypoints after saving the picked one, found " + count);
		}
		Waypoint picked = context.computeOnClient(mc -> WaypointStore.get().all().get(2));
		// The hit block sits under the waypoint; the ray crosses its top or south face.
		int hitY = picked.y - 1;
		double vTop = Projection.v(hitY + 1, picked.z);
		double vBottom = Projection.v(hitY, picked.z + 1);
		boolean onRay = picked.x == (int) Math.floor(click[0]) && click[1] >= vTop - 1e-6 && click[1] <= vBottom + 1e-6;
		boolean solid = context.computeOnClient(mc -> !mc.level.getBlockState(new BlockPos(picked.x, hitY, picked.z)).isAir());
		if (!onRay || !solid) {
			throw new AssertionError("Picked " + picked.x + "," + picked.y + "," + picked.z + " is not the block shown at the click");
		}
		context.setScreen(() -> null);

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("waypoints-05-list");
		context.setScreen(() -> null);

		// Dying leaves a death marker.
		world.getServer().runCommand("gamerule show_death_messages false");
		world.getServer().runCommand("kill @a");
		context.waitTicks(20);
		boolean death = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH));
		if (!death) {
			throw new AssertionError("No death marker after dying");
		}
		context.clickScreenButton("deathScreen.respawn");
		context.waitFor(mc -> mc.player != null && mc.player.isAlive(), 200);
		context.waitTicks(40);
		context.takeScreenshot("waypoints-06-after-death");

		// With instant respawn there is no death screen, and the death marker must still appear.
		world.getServer().runCommand("gamerule immediate_respawn true");
		long deathsBefore = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("kill @a");
		context.waitTicks(40);
		long deathsAfter = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("gamerule immediate_respawn false");
		if (deathsAfter != deathsBefore + 1) {
			throw new AssertionError("Instant respawn left " + (deathsAfter - deathsBefore) + " new death markers instead of 1");
		}

		// Worlds whose names clean up the same must not share a folder.
		if (WorldKey.key("singleplayer", "My World").equals(WorldKey.key("singleplayer", "My_World"))) {
			throw new AssertionError("Two different worlds got the same key");
		}

		// A damaged file: one bad entry is skipped, a null field is filled in, a copy of the
		// original is kept, and saving doesn't drop the good entries.
		context.runOnClient(mc -> {
			String key = "test-damaged-file";
			java.nio.file.Path folder = WorldKey.folder(key);
			try {
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Files.writeString(folder.resolve("waypoints.json"), """
					[
					  {"id": "00000000-0000-0000-0000-000000000001", "name": "Home", "initials": null, "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
					  {"id": "not-a-uuid", "name": "Broken", "x": 4, "y": 5, "z": 6, "dimension": "minecraft:overworld"}
					]
					""");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			WaypointStore damaged = WaypointStore.open(key);
			if (damaged.all().size() != 1 || !damaged.all().get(0).label().equals("H")) {
				throw new AssertionError("Expected only Home, with initials H, from the damaged file; got " + damaged.all().size());
			}
			damaged.add(new Waypoint("New", 0, 0, 0, "minecraft:overworld", Waypoint.PALETTE[0]));
			WaypointStore reopened = WaypointStore.open(key);
			if (reopened.all().size() != 2) {
				throw new AssertionError("Saving after a damaged load kept " + reopened.all().size() + " waypoints instead of 2");
			}
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder)) {
				if (files.noneMatch(f -> f.getFileName().toString().startsWith("waypoints.json.unreadable-"))) {
					throw new AssertionError("No copy of the damaged file was kept");
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("waypoints.json is missing", e);
			}
		});
		if (!saved.contains("\"Home\"") || !saved.contains("\"Iron Mine\"") || !saved.contains("\"DEATH\"")) {
			throw new AssertionError("waypoints.json doesn't have every waypoint:\n" + saved);
		}
	}

	/**
	 * Settings: the default minimap with its compass and info lines, the settings screen, the
	 * minimap moved to another corner at another size, and the effect icons pushing it down.
	 */
	private static void settings(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitTicks(20);
		context.takeScreenshot("settings-01-default-minimap");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings-02-screen");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.BOTTOM_LEFT;
			s.minimapSize = Settings.Size.LARGE;
			s.minimapZoom = 1;
			s.save();
		});
		settle(context, 20);
		context.takeScreenshot("settings-03-bottom-left-large");
		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("orthomap").resolve("config.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("config.json is missing", e);
			}
		});
		if (!saved.contains("\"BOTTOM_LEFT\"") || !saved.contains("\"LARGE\"")) {
			throw new AssertionError("config.json doesn't have the new settings:\n" + saved);
		}

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = Settings.Size.MEDIUM;
			s.minimapZoom = 3;
			s.save();
		});
		world.getServer().runCommand("effect give @a speed 60 0");
		world.getServer().runCommand("effect give @a weakness 60 0");
		context.waitTicks(20);
		context.takeScreenshot("settings-04-below-effects");
		world.getServer().runCommand("effect clear @a");
	}

	/** Which scenarios to run: ORTHOMAP_TESTS=tour,radar,... (default: all but styles). */
	private static Set<String> selectedTests() {
		String env = System.getenv("ORTHOMAP_TESTS");
		if ("1".equals(System.getenv("ORTHOMAP_STYLES"))) {
			return Set.of("styles");
		}
		if (env == null || env.isBlank()) {
			return Set.of("tour", "radar", "waypoints", "fog-of-war", "settings");
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
		context.runOnClient(mc -> MapStyle.set(Settings.get().style()));
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
