package dev.diorama.test;

import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointSort;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

/**
 * The waypoint list's search looking in every folder ({@code waypoint-search}, issue #244): farms
 * spread over the Overworld's folder, the Nether's, a folder of the player's own and a hidden one
 * are all found by typing "farm", this folder's first and the others' under a heading; a folder's
 * name finds everything in it; a row found in another folder is selected and hidden with the
 * mouse; and with nothing typed the list is the folder alone, as before. Only the waypoints and
 * folders made here are checked and removed: other sections leave waypoints of their own.
 */
final class WaypointSearchTest {
	private static final String ALEX = "Alex's base";
	private static final String SPARE = "Spare";

	private WaypointSearchTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<Waypoint> added = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			List<Waypoint> made = new ArrayList<>();
			made.add(add(store, at, here, "", "Iron Farm", 25, Waypoint.PALETTE[0]));
			made.add(add(store, at, here, "", "Creeper Farm", 310, Waypoint.PALETTE[3]));
			made.add(add(store, at, here, "", "Harbour", 80, Waypoint.PALETTE[5]));
			made.add(add(store, at, "minecraft:the_nether", "", "Gold Farm", 12, Waypoint.PALETTE[2]));
			store.addFolder(ALEX);
			made.add(add(store, at, here, ALEX, "Wheat Farm", 410, Waypoint.PALETTE[5]));
			made.add(add(store, at, here, ALEX, "Storage", 420, Waypoint.PALETTE[7]));
			// A hidden folder's waypoints are found too, dimmed.
			store.addFolder(SPARE);
			made.add(add(store, at, here, SPARE, "Slime Farm", 640, Waypoint.PALETTE[13]));
			store.setFolderHidden(WaypointStore.customKey(SPARE), true);
			// Enough farms that the list scrolls once they're all found.
			made.add(add(store, at, "minecraft:the_nether", "", "Blaze Farm", 13, Waypoint.PALETTE[1]));
			made.add(add(store, at, "minecraft:the_nether", "", "Wart Farm", 14, Waypoint.PALETTE[0]));
			made.add(add(store, at, "minecraft:the_nether", "", "Wither Farm", 15, Waypoint.PALETTE[11]));
			return made;
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		expect(context, wrong, added, "nothing typed", List.of("Iron Farm", "Harbour", "Creeper Farm"), List.of());
		context.takeScreenshot("search-01-nothing-typed");

		DioramaClientTest.typeInto(context, 0, "farm");
		expect(context, wrong, added, "farm", List.of("Iron Farm", "Creeper Farm"), List.of("Wheat Farm", "Slime Farm", "Blaze Farm", "Gold Farm", "Wart Farm", "Wither Farm"));
		context.takeScreenshot("search-02-farm");

		// A row under the heading is clicked where it's drawn, and its buttons act on it.
		Waypoint wheat = added.get(4);
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(wheat));
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		context.waitTicks(2);
		if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selected()) != wheat) {
			wrong.add("clicking Wheat Farm's row under the heading selected "
				+ context.computeOnClient(mc -> { Waypoint w = ((WaypointListScreen) mc.gui.screen()).selected(); return w == null ? "nothing" : w.name; }));
		}
		context.takeScreenshot("search-03-other-folder-selected");
		clickOnRow(context, "Hide", wheat);
		if (wheat.visible) {
			wrong.add("Hide on Wheat Farm's row didn't hide it");
		}
		context.takeScreenshot("search-04-other-folder-hidden");
		clickOnRow(context, "Show", wheat);
		if (!wheat.visible) {
			wrong.add("Show on Wheat Farm's row didn't show it again");
		}

		// The heading takes a line of its own: the first row under it is two rows below the last above.
		// Hiding Wheat Farm sent it to the end and the list after it, so back to the top first.
		scroll(context, 1);
		int[] above = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(added.get(1)));
		int[] below = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(wheat));
		if (above == null || below == null || below[1] - above[1] != 52) {
			wrong.add("Wheat Farm's row isn't two rows under Creeper Farm's, with the heading between");
		}
		// Scrolled to the end, past the heading, the last row is clicked where it's drawn too.
		Waypoint wither = added.get(9);
		if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(wither)) != null) {
			wrong.add("the farms all fit in the list, so scrolling wasn't checked");
		}
		scroll(context, -1);
		int[] last = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(wither));
		if (last == null) {
			wrong.add("Wither Farm's row wasn't in view after scrolling to the end");
		} else {
			DioramaClientTest.clickAtGui(context, last[0], last[1]);
			context.waitTicks(2);
			if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selected()) != wither) {
				wrong.add("clicking the last row after scrolling didn't select Wither Farm");
			}
			clickOnRow(context, "Hide", wither);
			if (wither.visible) {
				wrong.add("Hide on Wither Farm's row after scrolling didn't hide it");
			}
		}
		context.takeScreenshot("search-04b-scrolled");

		// A folder's name finds everything in it.
		DioramaClientTest.typeInto(context, 0, "ALEX");
		expect(context, wrong, added, "alex", List.of(), List.of("Wheat Farm", "Storage"));
		context.takeScreenshot("search-05-folder-name");

		// From one of the player's folders, with the search kept: its own first, the dimensions' after.
		DioramaClientTest.typeInto(context, 0, "farm");
		for (int i = 0; i < 20 && !context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName()).equals(ALEX); i++) {
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), ">")));
			context.waitTicks(2);
		}
		expect(context, wrong, added, "farm from Alex's base", List.of("Wheat Farm"), List.of("Iron Farm", "Creeper Farm", "Slime Farm", "Blaze Farm", "Gold Farm", "Wart Farm", "Wither Farm"));
		context.takeScreenshot("search-06-from-own-folder");

		DioramaClientTest.typeInto(context, 0, "zzzz");
		expect(context, wrong, added, "zzzz", List.of(), List.of());
		context.takeScreenshot("search-07-no-match");

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			added.forEach(store::remove);
			store.deleteFolder(ALEX);
			store.deleteFolder(SPARE);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Waypoint search went wrong: " + wrong);
		}
	}

	private static Waypoint add(WaypointStore store, BlockPos at, String dimension, String folder, String name, int east, int colour) {
		Waypoint waypoint = new Waypoint(name, at.getX() + east, at.getY(), at.getZ(), dimension, colour);
		waypoint.folder = folder;
		store.add(waypoint);
		return waypoint;
	}

	/** Of the waypoints made here, the ones listed as the folder's own and the ones found in other folders, in order. */
	private static void expect(ClientGameTestContext context, List<String> wrong, List<Waypoint> added, String what, List<String> own, List<String> others) {
		List<List<String>> shown = context.computeOnClient(mc -> {
			WaypointListScreen list = (WaypointListScreen) mc.gui.screen();
			List<Waypoint> rows = list.shown();
			int split = list.shownInFolder();
			List<String> first = rows.subList(0, split).stream().filter(added::contains).map(w -> w.name).toList();
			List<String> rest = rows.subList(split, rows.size()).stream().filter(added::contains).map(w -> w.name).toList();
			return List.of(first, rest);
		});
		if (!shown.get(0).equals(own) || !shown.get(1).equals(others)) {
			wrong.add(what + " showed " + shown.get(0) + " then " + shown.get(1));
		}
	}

	/** Turns the mouse wheel over the list until it's at one end: up for 1, down for -1. */
	private static void scroll(ClientGameTestContext context, int direction) {
		for (int i = 0; i < 12; i++) {
			context.runOnClient(mc -> {
				//? if >=1.21 {
				mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, 0, direction);
				//?} else {
				/*mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, direction);
				*///?}
			});
		}
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
}
