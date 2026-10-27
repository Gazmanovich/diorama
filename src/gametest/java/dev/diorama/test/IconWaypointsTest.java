package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.WorldKey;
import dev.diorama.bake.TileKey;
import dev.diorama.render.PinArt;
import dev.diorama.render.WaypointIcons;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointIconScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;

/**
 * Icon waypoints. {@link #checks} runs by default ({@code icons}): every icon ships, sleeping in a
 * bed keeps one Bed pin there, and an icon survives saving. {@link #photos} is opt-in
 * ({@code icon-waypoints}): {@link ShowcaseScene}'s castle in the README's map view, with pins
 * where RickE_26's mock put them, for the PR's pictures; {@code DIORAMA_GIF=1} adds the frames of
 * picking an icon in the editor.
 */
final class IconWaypointsTest {
	private static final boolean GIF = "1".equals(System.getenv("DIORAMA_GIF"));
	private static double[] cursor = {0, 0};
	private static int frames;

	private IconWaypointsTest() {
	}

	static void checks(ClientGameTestContext context, TestSingleplayerContext world) {
		expectIcons(context, "on joining");
		// A resource reload (F3+T, or changing packs) drops the icons and the pins drawn with them;
		// they come back from the new resources.
		DioramaClientTest.reloadPacks(context, mc -> { });
		expectIcons(context, "after a resource reload");

		// Lying down in a bed puts the Bed pin there; another bed moves it rather than adding one.
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos first = at.offset(3, 0, 0);
		BlockPos second = at.offset(6, 0, 0);
		sleep(context, world, first);
		expectBed(context, first, "after sleeping in a bed");
		sleep(context, world, second);
		expectBed(context, second, "after sleeping in another bed");
		context.runOnClient(mc -> Settings.get().bedMarkers = false);
		sleep(context, world, first);
		expectBed(context, second, "after sleeping with bed pins off");
		context.runOnClient(mc -> Settings.get().bedMarkers = true);

		// An icon is saved with its waypoint.
		String saved = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			Waypoint farm = new Waypoint("Farm", at.getX(), at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[3]);
			farm.icon = "diorama:farm";
			store.add(farm);
			String read = WaypointStore.open(WorldKey.current()).all().stream().filter(w -> w.id.equals(farm.id)).map(w -> w.icon).findFirst().orElse("gone");
			store.remove(farm);
			store.all().stream().filter(w -> w.kind == Waypoint.Kind.BED).toList().forEach(store::remove);
			return read;
		});
		if (!saved.equals("diorama:farm")) {
			throw new AssertionError("A waypoint's icon after saving: expected diorama:farm, got " + saved);
		}
		for (BlockPos bed : new BlockPos[] {first, second}) {
			world.getServer().runCommand("setblock " + bed.getX() + " " + bed.getY() + " " + bed.getZ() + " air");
			world.getServer().runCommand("setblock " + bed.getX() + " " + bed.getY() + " " + (bed.getZ() + 1) + " air");
		}
	}

	/**
	 * Every icon Diorama offers loads as a full tile, with pixels in it; the bed's are mostly red,
	 * so the colours come through the right way round.
	 */
	private static void expectIcons(ClientGameTestContext context, String when) {
		for (String id : WaypointIcons.OFFERED) {
			String seen = context.computeOnClient(mc -> {
				PinArt.Image image = WaypointIcons.image(id);
				if (image == null) {
					return "missing";
				}
				int opaque = 0;
				int red = 0;
				for (int y = 0; y < image.height; y++) {
					for (int x = 0; x < image.width; x++) {
						int argb = image.argb(x, y);
						if (argb >>> 24 >= 128) {
							opaque++;
							red += ((argb >> 16) & 0xFF) > (argb & 0xFF) + 60 ? 1 : 0;
						}
					}
				}
				return image.width + "x" + image.height + (opaque < 30 ? " nearly empty" : "") + (id.equals(WaypointIcons.BED) && red < 10 ? " not red" : "");
			});
			if (!seen.equals(WaypointIcons.TILE + "x" + WaypointIcons.TILE)) {
				throw new AssertionError("Icon " + id + " " + when + ": expected a full " + WaypointIcons.TILE + "-pixel tile, got " + seen);
			}
		}
	}

	/** Puts a bed with its head at {@code head} and has the player lie down in it, then get up. */
	private static void sleep(ClientGameTestContext context, TestSingleplayerContext world, BlockPos head) {
		world.getServer().runCommand("setblock " + head.getX() + " " + head.getY() + " " + (head.getZ() + 1) + " red_bed[facing=north,part=foot]");
		world.getServer().runCommand("setblock " + head.getX() + " " + head.getY() + " " + head.getZ() + " red_bed[facing=north,part=head]");
		// The game wakes anyone asleep in daylight.
		world.getServer().runCommand("time set midnight");
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).startSleeping(head));
		context.waitFor(mc -> mc.player != null && mc.player.isSleeping(), 200);
		context.waitTicks(5);
		// As the game wakes a player, telling their client too.
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).stopSleepInBed(false, true));
		context.waitFor(mc -> mc.player != null && !mc.player.isSleeping(), 200);
		world.getServer().runCommand("time set noon");
	}

	private static void expectBed(ClientGameTestContext context, BlockPos head, String when) {
		String beds = context.computeOnClient(mc -> String.join("; ", WaypointStore.get().all().stream()
			.filter(w -> w.kind == Waypoint.Kind.BED).map(w -> w.x + " " + w.y + " " + w.z + " " + w.icon).toList()));
		String expected = head.getX() + " " + head.getY() + " " + head.getZ() + " " + WaypointIcons.BED;
		if (!beds.equals(expected)) {
			throw new AssertionError("Bed pins " + when + ": expected " + expected + ", got " + beds);
		}
	}

	static void photos(ClientGameTestContext context, TestSingleplayerContext world) {
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
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
		});

		// The bed pin, from lying down in a bed in the keep; then the player waits on the road south,
		// out of the pictures.
		BlockPos head = new BlockPos(cx - 6, base + 1, cz - 16);
		sleep(context, world, head);
		expectBed(context, head, "in the castle");
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 92.5) + " 180 -4");
		context.waitTicks(40);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			store.add(icon(new Waypoint("Keep", cx + 8, base + 1, cz - 8, overworld, Waypoint.PALETTE[1]), "diorama:home"));
			store.add(icon(new Waypoint("Trader", cx - 9, base + 1, cz, overworld, Waypoint.PALETTE[3]), "diorama:villagers"));
			store.add(icon(new Waypoint("Cows", cx + 8, base + 1, cz + 7, overworld, Waypoint.PALETTE[12]), "diorama:animals"));
			// One without an icon, to see the two kinds side by side.
			store.add(new Waypoint("Gate", cx, base + 1, cz + 20, overworld, Waypoint.PALETTE[5]));
			// Far off, so the minimap shows it at its edge.
			store.add(icon(new Waypoint("Mine", cx + 160, base - 20, cz + 40, overworld, Waypoint.PALETTE[6]), "diorama:mine"));
		});

		// The README's view, with icons and without (today's pins), then further out.
		context.setScreen(MapScreen::new);
		// Over the title bar, so no hover box covers the map.
		context.getInput().setCursorPos(1300, 30);
		double v = Projection.v(base + 12, cz);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 200);
		context.takeScreenshot("icon-wp-map");
		String[] icons = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.icon).toArray(String[]::new));
		context.runOnClient(mc -> WaypointStore.get().all().forEach(w -> w.icon = ""));
		settle(context, 5);
		context.takeScreenshot("icon-wp-map-before");
		context.runOnClient(mc -> {
			var all = WaypointStore.get().all();
			for (int i = 0; i < all.size(); i++) {
				all.get(i).icon = icons[i];
			}
		});
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 1.6, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("icon-wp-map-far");

		// Beside the radar: live villagers and cows in the courtyard.
		for (int[] spot : new int[][] {{-4, 6}, {-2, 9}, {3, 4}, {5, 11}, {10, 2}}) {
			String type = spot[0] < 0 ? "villager" : "cow";
			world.getServer().runCommand("summon " + type + " " + (cx + spot[0] + 0.5) + " " + (base + 1) + " " + (cz + spot[1] + 0.5) + " {NoAI:1b}");
		}
		context.runOnClient(mc -> Settings.get().radarPassive = true);
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 30.5) + " 180 -4");
		context.waitTicks(40);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 4.8, TileKey.SURFACE));
		settle(context, 40);
		context.takeScreenshot("icon-wp-with-radar");

		// Picking an icon: the editor's Icon button, the picker, Save, and the map again.
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
		settle(context, 20);
		cursor = new double[] {1300, 30};
		frame(context);
		hold(context, 6);
		context.runOnClient(mc -> {
			Waypoint keep = WaypointStore.get().all().stream().filter(w -> w.name.equals("Keep")).findFirst().orElseThrow();
			mc.gui.setScreen(new WaypointEditScreen(mc.gui.screen(), keep, false));
		});
		context.waitTicks(3);
		hold(context, 6);
		context.takeScreenshot("icon-wp-editor");
		double[] button = context.computeOnClient(mc -> {
			net.minecraft.client.gui.components.Button icon = mc.gui.screen().children().stream()
				.filter(child -> child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(WaypointIconScreen.name("diorama:home").getString()))
				.map(child -> (net.minecraft.client.gui.components.Button) child).findFirst().orElseThrow();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(icon.getX() + icon.getWidth() / 2.0) * scale, (icon.getY() + icon.getHeight() / 2.0) * scale};
		});
		click(context, button[0], button[1]);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointIconScreen)) {
			throw new AssertionError("The Icon button didn't open the icon picker");
		}
		hold(context, 4);
		context.takeScreenshot("icon-wp-picker");
		double[] farm = context.computeOnClient(mc -> {
			float[] at = ((WaypointIconScreen) mc.gui.screen()).cellCentre("diorama:farm");
			double scale = mc.getWindow().getGuiScale();
			return new double[] {at[0] * scale, at[1] * scale};
		});
		// Only a left click picks: a right click leaves the picker open.
		context.getInput().setCursorPos(farm[0], farm[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(2);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointIconScreen)) {
			throw new AssertionError("A right click picked an icon");
		}
		click(context, farm[0], farm[1]);
		hold(context, 6);
		double[] save = context.computeOnClient(mc -> {
			net.minecraft.client.gui.components.Button button1 = mc.gui.screen().children().stream()
				.filter(child -> child instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("diorama.waypoint.save").getString()))
				.map(child -> (net.minecraft.client.gui.components.Button) child).findFirst().orElseThrow();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(button1.getX() + button1.getWidth() / 2.0) * scale, (button1.getY() + button1.getHeight() / 2.0) * scale};
		});
		click(context, save[0], save[1]);
		moveCursor(context, 1300, 30);
		hold(context, 12);
		String picked = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.equals("Keep")).map(w -> w.icon).findFirst().orElse("gone"));
		if (!picked.equals("diorama:farm")) {
			throw new AssertionError("The Keep's icon after picking Farm and saving: " + picked);
		}

		// The waypoint list, the minimap with the Mine pin at its edge, and pins in the world.
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("icon-wp-list");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 8.5) + " 180 30");
		context.waitTicks(40);
		settle(context, 40);
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		context.takeScreenshot("icon-wp-minimap");
	}

	private static Waypoint icon(Waypoint waypoint, String icon) {
		waypoint.icon = icon;
		return waypoint;
	}

	private static void click(ClientGameTestContext context, double x, double y) {
		moveCursor(context, x, y);
		hold(context, 3);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		frame(context);
	}

	private static void moveCursor(ClientGameTestContext context, double x, double y) {
		int steps = GIF ? 10 : 1;
		double[] from = cursor;
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			double ease = t * t * (3 - 2 * t);
			cursor = new double[] {from[0] + (x - from[0]) * ease, from[1] + (y - from[1]) * ease};
			context.getInput().setCursorPos(cursor[0], cursor[1]);
			context.waitTick();
			frame(context);
		}
	}

	private static void hold(ClientGameTestContext context, int count) {
		for (int i = 0; i < count; i++) {
			context.waitTick();
			frame(context);
		}
	}

	/** A GIF frame, the cursor's position in its name: the game's screenshots leave the cursor out. */
	private static void frame(ClientGameTestContext context) {
		if (GIF) {
			context.takeScreenshot(String.format("icon-gif-%03d-%d-%d", frames++, Math.round(cursor[0]), Math.round(cursor[1])));
		}
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
