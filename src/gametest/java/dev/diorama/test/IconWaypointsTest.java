package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.PinArt;
import dev.diorama.render.Pins;
import dev.diorama.render.WaypointIcons;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;

/**
 * Icon waypoints, explored from RickE_26's mock: {@link ShowcaseScene}'s castle, the README's
 * map view of it, and pins with icons where the mock put them (a bed, a house, a villager, a
 * cow), photographed in each {@link PinArt.IconStyle}. The bed pin comes from sleeping in a bed.
 * Opt-in with {@code DIORAMA_TESTS=icon-waypoints}.
 */
final class IconWaypointsTest {
	private IconWaypointsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1920, 1080);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX();
		int cz = village.getZ() - 110;
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		int base = scene.base();
		world.getServer().runOnServer(server -> scene.castle("waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"));
		dev.diorama.Diorama.LOGGER.info("Icon waypoints: castle at {} {} {}", cx, base, cz);
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
		});
		context.getInput().setCursorPos(0, 0);

		// The bed pin: lie down in a bed in the keep and the map marks it.
		BlockPos head = new BlockPos(cx - 6, base + 1, cz - 16);
		world.getServer().runCommand("setblock " + (cx - 6) + " " + (base + 1) + " " + (cz - 15) + " red_bed[facing=north,part=foot]");
		world.getServer().runCommand("setblock " + head.getX() + " " + head.getY() + " " + head.getZ() + " red_bed[facing=north,part=head]");
		world.getServer().runCommand("time set midnight");
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().startSleeping(head));
		context.waitFor(mc -> mc.player != null && mc.player.isSleeping(), 200);
		context.waitTicks(10);
		String bed = context.computeOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				if (w.kind == Waypoint.Kind.BED) {
					return w.x + " " + w.y + " " + w.z + " " + w.icon;
				}
			}
			return "none";
		});
		String expected = head.getX() + " " + head.getY() + " " + head.getZ() + " diorama:bed";
		if (!bed.equals(expected)) {
			throw new AssertionError("Bed waypoint after sleeping: expected " + expected + ", got " + bed);
		}
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().stopSleeping());
		world.getServer().runCommand("time set noon");
		// Off the castle, on the road south, so the player's arrow stays out of the photos.
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 92.5) + " 180 -4");
		context.waitTicks(40);

		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			store.add(icon(new Waypoint("Keep", cx + 8, base + 1, cz - 8, overworld, Waypoint.PALETTE[1]), "diorama:home"));
			store.add(icon(new Waypoint("Trader", cx - 9, base + 1, cz, overworld, Waypoint.PALETTE[3]), "diorama:villagers"));
			// One without an icon, to see the two kinds side by side.
			store.add(new Waypoint("Gate", cx, base + 1, cz + 20, overworld, Waypoint.PALETTE[5]));
			store.add(icon(new Waypoint("Cows", cx + 8, base + 1, cz + 7, overworld, Waypoint.PALETTE[12]), "diorama:animals"));
			for (String id : WaypointIcons.OFFERED) {
				PinArt.Image image = WaypointIcons.image(id);
				dev.diorama.Diorama.LOGGER.info("Icon {}: {}", id, image == null ? "missing" : image.width + "x" + image.height);
			}
			dev.diorama.Diorama.LOGGER.info("Icons shipped: {}", WaypointIcons.shipped());
		});
		for (String id : new String[] {"diorama:bed", "diorama:home", "diorama:villagers", "diorama:animals"}) {
			if (!context.computeOnClient(mc -> WaypointIcons.image(id) != null)) {
				throw new AssertionError("No icon " + id);
			}
		}

		// The README's view (zoom 3.2) and the mock's closer crop of it, in each style.
		context.setScreen(MapScreen::new);
		// Over the title bar, so no hover box covers the map.
		context.getInput().setCursorPos(1300, 30);
		double v = Projection.v(base + 12, cz);
		// The first view bakes the castle: let it finish, so the title bar doesn't say "baking".
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 200);
		for (PinArt.IconStyle style : PinArt.IconStyle.values()) {
			context.runOnClient(mc -> Pins.iconStyle = style);
			for (double zoom : style == PinArt.IconStyle.STICKER ? new double[] {3.2, 1.6, 6} : new double[] {3.2}) {
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, zoom, TileKey.SURFACE));
				settle(context, 60);
				context.takeScreenshot("icon-wp-" + style.name().toLowerCase(java.util.Locale.ROOT) + "-z" + zoom);
			}
		}
		// Today's pins at the same spots, initials and all, to compare.
		context.runOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				w.icon = "";
			}
		});
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 30);
		context.takeScreenshot("icon-wp-initials-z3.2");
		context.runOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				w.icon = switch (w.name) {
					case "Bed" -> "diorama:bed";
					case "Keep" -> "diorama:home";
					case "Trader" -> "diorama:villagers";
					case "Cows" -> "diorama:animals";
					default -> "";
				};
			}
			Pins.iconStyle = PinArt.IconStyle.ROUND;
		});

		// Beside the radar: live villagers and cows in the courtyard, faces without a pointer.
		for (int[] spot : new int[][] {{-4, 6}, {-2, 9}, {3, 4}, {5, 11}, {10, 2}}) {
			String type = spot[0] < 0 ? "villager" : "cow";
			world.getServer().runCommand("summon " + type + " " + (cx + spot[0] + 0.5) + " " + (base + 1) + " " + (cz + spot[1] + 0.5) + " {NoAI:1b}");
		}
		context.runOnClient(mc -> {
			Settings.get().radarPassive = true;
			Settings.get().radarRange = 512;
		});
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 30.5) + " 180 -4");
		context.waitTicks(40);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 4.8, TileKey.SURFACE));
		settle(context, 40);
		context.takeScreenshot("icon-wp-with-radar-z4.8");

		// The editor's preview, and the pins on the minimap and in the world.
		context.runOnClient(mc -> {
			Waypoint keep = WaypointStore.get().all().stream().filter(w -> w.name.equals("Keep")).findFirst().orElseThrow();
			mc.gui.setScreen(new WaypointEditScreen(null, keep, false));
		});
		context.waitTicks(10);
		context.takeScreenshot("icon-wp-editor");
		context.runOnClient(mc -> {
			Waypoint keep = WaypointStore.get().all().stream().filter(w -> w.name.equals("Keep")).findFirst().orElseThrow();
			mc.gui.setScreen(new dev.diorama.waypoint.WaypointIconScreen(mc.gui.screen(), keep, keep.icon, id -> { }));
		});
		context.waitTicks(10);
		context.takeScreenshot("icon-wp-picker");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 8.5) + " 180 30");
		context.waitTicks(40);
		settle(context, 40);
		context.takeScreenshot("icon-wp-minimap");
		world.getServer().runCommand("tp @a " + (cx + 14.5) + " " + (base + 10) + " " + (cz + 60.5) + " 165 -2");
		context.waitTicks(40);
		settle(context, 40);
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		context.takeScreenshot("icon-wp-in-world");
	}

	private static Waypoint icon(Waypoint waypoint, String icon) {
		waypoint.icon = icon;
		return waypoint;
	}

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
