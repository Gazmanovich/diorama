package dev.diorama.test;

import dev.diorama.WorldKey;
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
 * clicks them, Hide and Show moving a row, the order saved with the world's folders, the search
 * keeping the order, another dimension's waypoint in the same folder, and the search row in the
 * smallest window and the game's default one. The waypoints go in folders of their own and only they
 * are removed afterwards: other sections leave waypoints to check after the world is left.
 */
final class WaypointSortTest {
	private static final String SORTING = "Sorting";
	private static final String TRIP = "Trip";

	private WaypointSortTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<Waypoint> added = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			List<Waypoint> made = new ArrayList<>();
			// Distance from the player, initials, icon, colour: each order puts them differently.
			store.addFolder(SORTING);
			made.add(add(store, at, here, SORTING, "Home", "H", "", 10, Waypoint.PALETTE[3]));
			made.add(add(store, at, here, SORTING, "beacon", "B", "", 40, 0xFF0090FF));
			made.add(add(store, at, here, SORTING, "Iron Farm", "IF", "", 25, Waypoint.PALETTE[0]));
			made.add(add(store, at, here, SORTING, "Zed's Tower", "ZT", "", 60, 0xFFFFFFFF));
			made.add(add(store, at, here, SORTING, "Apple Orchard", "AO", "diorama:farm", 5, Waypoint.PALETTE[1]));
			// Hidden and nearest of all: last in every order.
			Waypoint mine = add(store, at, here, SORTING, "Mine", "M", "", 1, Waypoint.PALETTE[2]);
			store.setVisible(mine, false);
			made.add(mine);
			// A Nether waypoint in with this dimension's, which has no distance to go by.
			store.addFolder(TRIP);
			made.add(add(store, at, here, TRIP, "Base", "BA", "", 20, Waypoint.PALETTE[4]));
			made.add(add(store, at, here, TRIP, "Camp", "CA", "", 30, Waypoint.PALETTE[5]));
			made.add(add(store, at, "minecraft:the_nether", TRIP, "Fortress", "F", "", 0, Waypoint.PALETTE[6]));
			return made;
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		showFolder(context, wrong, SORTING);
		expect(context, wrong, "distance", "Apple Orchard", "Home", "Iron Farm", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-01-distance");
		reverse(context);
		expect(context, wrong, "distance reversed", "Zed's Tower", "beacon", "Iron Farm", "Home", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-02-distance-reversed");
		// The next order keeps the direction.
		click(context, "Distance");
		expect(context, wrong, "name reversed", "Zed's Tower", "Iron Farm", "Home", "beacon", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-03-name-reversed");
		reverse(context);
		expect(context, wrong, "name", "Apple Orchard", "beacon", "Home", "Iron Farm", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-04-name");
		// Hiding a waypoint moves it down among the hidden ones, still selected; showing it puts it back.
		Waypoint home = added.get(0);
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(home));
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		clickOnRow(context, "Hide", home);
		expect(context, wrong, "name with Home hidden", "Apple Orchard", "beacon", "Iron Farm", "Zed's Tower", "Home", "Mine");
		if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selected()) != home) {
			wrong.add("Home wasn't selected any more after hiding it");
		}
		context.takeScreenshot("sort-04b-name-home-hidden");
		clickOnRow(context, "Show", home);
		expect(context, wrong, "name with Home shown again", "Apple Orchard", "beacon", "Home", "Iron Farm", "Zed's Tower", "Mine");
		click(context, "Name");
		expect(context, wrong, "initials", "beacon", "Home", "Iron Farm", "Zed's Tower", "Apple Orchard", "Mine");
		context.takeScreenshot("sort-05-initials");
		reverse(context);
		expect(context, wrong, "initials reversed", "Apple Orchard", "Zed's Tower", "Iron Farm", "Home", "beacon", "Mine");
		context.takeScreenshot("sort-06-initials-reversed");
		click(context, "Initials");
		expect(context, wrong, "colour reversed", "Zed's Tower", "beacon", "Home", "Apple Orchard", "Iron Farm", "Mine");
		context.takeScreenshot("sort-07-colour-reversed");
		reverse(context);
		expect(context, wrong, "colour", "Iron Farm", "Apple Orchard", "Home", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-08-colour");
		// Hovering either button says what the order is and what comes first.
		hover(context, "Colour");
		context.waitTicks(3);
		context.takeScreenshot("sort-09-tooltip");

		// The order is saved with the world's folders: the file read afresh has it.
		String saved = context.computeOnClient(mc -> {
			WaypointStore read = WaypointStore.open(WorldKey.current());
			return read.sort() + (read.sortReversed() ? " reversed" : "");
		});
		if (!saved.equals("COLOUR")) {
			wrong.add("folders.json read again has the order " + saved + ", not COLOUR");
		}
		// The search keeps the order.
		DioramaClientTest.typeInto(context, 0, "e");
		expect(context, wrong, "colour while searching e", "Apple Orchard", "Home", "beacon", "Zed's Tower", "Mine");
		context.takeScreenshot("sort-10-search");
		DioramaClientTest.typeInto(context, 0, "");

		// Another dimension's waypoint stays after this one's either way.
		showFolder(context, wrong, TRIP);
		click(context, "Colour");
		expect(context, wrong, "folder by distance", "Base", "Camp", "Fortress");
		reverse(context);
		expect(context, wrong, "folder by distance reversed", "Camp", "Base", "Fortress");
		context.takeScreenshot("sort-11-folder-reversed");
		reverse(context);
		showFolder(context, wrong, SORTING);

		// The search row fits without widgets on top of each other in the smallest window the game
		// allows (320 by 240 at its GUI scale) and in its default one.
		context.getInput().resizeWindow(640, 480);
		context.waitTicks(5);
		overlaps(context, wrong, "640x480");
		context.takeScreenshot("sort-12-small-window");
		context.getInput().resizeWindow(854, 480);
		context.waitTicks(5);
		overlaps(context, wrong, "854x480");
		context.takeScreenshot("sort-13-default-window");
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(5);

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			added.forEach(store::remove);
			store.deleteFolder(SORTING);
			store.deleteFolder(TRIP);
			store.setSort(WaypointSort.DISTANCE, false);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Waypoint list sorted wrong: " + wrong);
		}
	}

	private static Waypoint add(WaypointStore store, BlockPos at, String dimension, String folder, String name, String initials, String icon, int east, int colour) {
		Waypoint waypoint = new Waypoint(name, at.getX() + east, at.getY(), at.getZ(), dimension, colour);
		waypoint.initials = initials;
		waypoint.icon = icon;
		waypoint.folder = folder;
		store.add(waypoint);
		return waypoint;
	}

	/** Steps the folder bar with its right arrow, as a player does, until it shows this folder. */
	private static void showFolder(ClientGameTestContext context, List<String> wrong, String name) {
		for (int i = 0; i < 20; i++) {
			if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName()).equals(name)) {
				return;
			}
			click(context, ">");
		}
		wrong.add("the folder bar never got to " + name);
	}

	private static void expect(ClientGameTestContext context, List<String> wrong, String what, String... names) {
		// The folder's own rows: a search also lists what it finds in other folders, after these.
		List<String> shown = context.computeOnClient(mc -> {
			WaypointListScreen list = (WaypointListScreen) mc.gui.screen();
			return list.shown().stream().limit(list.shownInFolder()).map(w -> w.name).toList();
		});
		if (!shown.equals(List.of(names))) {
			wrong.add(what + " showed " + shown);
		}
	}

	private static void click(ClientGameTestContext context, String label) {
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), label)));
		context.waitTicks(2);
	}

	/** Clicks the sort direction's button, which has a caret rather than a label. */
	private static void reverse(ClientGameTestContext context) {
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).sortDirection()));
		context.waitTicks(2);
	}

	/** Clicks the selected row's button with this label, not the folder bar's of the same name. */
	private static void clickOnRow(ClientGameTestContext context, String label, Waypoint waypoint) {
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> {
			int[] row = ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint);
			for (var child : mc.gui.screen().children()) {
				if (child instanceof Button button && button.visible && button.getMessage().getString().equals(label) && row != null && Math.abs(button.getY() + 10 - row[1]) <= 4) {
					return button;
				}
			}
			throw new AssertionError("No " + label + " button on " + waypoint.name + "'s row");
		}));
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
