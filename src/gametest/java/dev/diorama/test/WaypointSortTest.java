package dev.diorama.test;

import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointSort;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

/**
 * Sorting the waypoint list ({@code waypoint-sort}): every order both ways, clicked as a player
 * clicks them, the order kept when the world's waypoints are read again, the search keeping the
 * order, a folder of the player's own with another dimension's waypoint in it, and the search row
 * at the smallest window and at a large GUI scale.
 */
final class WaypointSortTest {
	private static final String UP = "▲";
	private static final String DOWN = "▼";

	private WaypointSortTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			// Distance from the player, initials, icon, colour: each order puts them differently.
			add(store, at, here, "Home", "H", "", 10, Waypoint.PALETTE[3]);
			add(store, at, here, "beacon", "B", "", 40, 0xFF0090FF);
			add(store, at, here, "Iron Farm", "IF", "", 25, Waypoint.PALETTE[0]);
			add(store, at, here, "Zed's Tower", "ZT", "", 60, 0xFFFFFFFF);
			add(store, at, here, "Apple Orchard", "AO", "diorama:farm", 5, Waypoint.PALETTE[1]);
			// Hidden and nearest of all: last in every order.
			Waypoint mine = add(store, at, here, "Mine", "M", "", 1, Waypoint.PALETTE[2]);
			store.setVisible(mine, false);
			// A folder of the player's own with a Nether waypoint in it, which has no distance to go by.
			store.addFolder("Trip");
			add(store, at, here, "Base", "BA", "", 20, Waypoint.PALETTE[4]).folder = "Trip";
			add(store, at, here, "Camp", "CA", "", 30, Waypoint.PALETTE[5]).folder = "Trip";
			add(store, at, "minecraft:the_nether", "Fortress", "F", "", 0, Waypoint.PALETTE[6]).folder = "Trip";
			store.changed();
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		expect(context, wrong, "distance", "Apple Orchard", "Home", "Iron Farm", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-01-distance");
		click(context, UP);
		expect(context, wrong, "distance reversed", "Zed's Tower", "beacon", "Iron Farm", "Home", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-02-distance-reversed");
		// The next order keeps the direction.
		click(context, "Distance");
		expect(context, wrong, "name reversed", "Zed's Tower", "Iron Farm", "Home", "beacon", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-03-name-reversed");
		click(context, DOWN);
		expect(context, wrong, "name", "Apple Orchard", "beacon", "Home", "Iron Farm", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-04-name");
		click(context, "Name");
		expect(context, wrong, "initials", "beacon", "Home", "Iron Farm", "Zed's Tower", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-05-initials");
		click(context, UP);
		expect(context, wrong, "initials reversed", "Apple Orchard", "Zed's Tower", "Iron Farm", "Home", "beacon", "Mine");
		context.takeScreenshot("sort-06-initials-reversed");
		click(context, "Initials");
		expect(context, wrong, "colour reversed", "Zed's Tower", "beacon", "Home", "Apple Orchard", "Iron Farm", "Mine");
		context.takeScreenshot("sort-07-colour-reversed");
		click(context, DOWN);
		expect(context, wrong, "colour", "Iron Farm", "Apple Orchard", "Home", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-08-colour");
		// Hovering either button says what the order is and what comes first.
		hover(context, "Colour");
		context.waitTicks(3);
		context.takeScreenshot("sort-09-tooltip");

		// The order is the world's: read from disk again, the list opens in it.
		context.setScreen(() -> null);
		context.runOnClient(mc -> WaypointStore.onLeave());
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		expect(context, wrong, "colour after reading the waypoints again", "Iron Farm", "Apple Orchard", "Home", "beacon", "Zed's Tower", "Mine");
		// The search keeps the order.
		DioramaClientTest.typeInto(context, 0, "e");
		expect(context, wrong, "colour while searching e", "Apple Orchard", "Home", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-10-search");
		DioramaClientTest.typeInto(context, 0, "");

		// In the player's own folder, the Nether waypoint stays after the near ones either way.
		click(context, "<");
		String folder = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName());
		if (!folder.equals("Trip")) {
			wrong.add("the folder before the Overworld is " + folder + ", not Trip");
		}
		click(context, "Colour");
		expect(context, wrong, "folder by distance", "Base", "Camp", "Fortress");
		click(context, UP);
		expect(context, wrong, "folder by distance reversed", "Camp", "Base", "Fortress");
		context.takeScreenshot("sort-11-folder-reversed");
		click(context, ">");

		// The search row fits the list's width without buttons on top of each other, in the smallest
		// window the game allows and at a large GUI scale.
		context.getInput().resizeWindow(640, 480);
		context.waitTicks(5);
		overlaps(context, wrong, "640x480");
		context.takeScreenshot("sort-12-small-window");
		context.getInput().resizeWindow(1600, 900);
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		DioramaClientTest.setGuiScale(context, 4);
		overlaps(context, wrong, "GUI scale 4");
		context.takeScreenshot("sort-13-gui-scale-4");
		DioramaClientTest.setGuiScale(context, guiScale);

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			store.deleteFolder("Trip");
			store.setSort(WaypointSort.DISTANCE, false);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Waypoint list sorted wrong: " + wrong);
		}
	}

	private static Waypoint add(WaypointStore store, BlockPos at, String dimension, String name, String initials, String icon, int east, int colour) {
		Waypoint waypoint = new Waypoint(name, at.getX() + east, at.getY(), at.getZ(), dimension, colour);
		waypoint.initials = initials;
		waypoint.icon = icon;
		store.add(waypoint);
		return waypoint;
	}

	private static void expect(ClientGameTestContext context, List<String> wrong, String what, String... names) {
		List<String> shown = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().map(w -> w.name).toList());
		if (!shown.equals(List.of(names))) {
			wrong.add(what + " showed " + shown);
		}
	}

	private static void click(ClientGameTestContext context, String label) {
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), label)));
		context.waitTicks(2);
	}

	private static void hover(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			Button button = DioramaClientTest.buttonLabelled(mc.gui.screen(), label);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
	}

	/**
	 * Notes any two widgets showing on the screen that cover each other. The list is 340 wide, so at
	 * 320 it was already past the screen's edges before the sort buttons: the pictures show that.
	 */
	private static void overlaps(ClientGameTestContext context, List<String> wrong, String what) {
		List<String> found = context.computeOnClient(mc -> {
			List<AbstractWidget> shown = new ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget widget && widget.visible) {
					shown.add(widget);
				}
			}
			List<String> out = new ArrayList<>();
			for (int i = 0; i < shown.size(); i++) {
				AbstractWidget a = shown.get(i);
				for (int j = i + 1; j < shown.size(); j++) {
					AbstractWidget b = shown.get(j);
					if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
						out.add(a.getMessage().getString() + " covers " + b.getMessage().getString());
					}
				}
			}
			return out;
		});
		for (String overlap : found) {
			wrong.add(what + ": " + overlap);
		}
	}
}
