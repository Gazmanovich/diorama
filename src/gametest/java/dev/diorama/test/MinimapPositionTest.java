package dev.diorama.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.DioramaClient;
import dev.diorama.MinimapHud;
import dev.diorama.MinimapPositionScreen;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * The minimap put anywhere on screen (#54). First, a settings file from 0.9.0, with no position
 * but its corner, puts the minimap where 0.9.0 drew it in each corner, at two sizes and two window
 * sizes, enlarged and under status effect icons. Then the position screen, opened from the
 * settings, with real mouse drags: to the middle, to the left and bottom edges, C to center it, Esc
 * to put it back, Done to keep it. The place it's kept at survives a smaller window, GUI scales 1 to
 * 4, the enlarge key and the effect icons, on screen each time.
 */
final class MinimapPositionTest {
	private static final int MARGIN = 6;

	private MinimapPositionTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		Object[] saved = context.computeOnClient(mc -> {
			Settings s = Settings.get();
			return new Object[] {s.minimapCorner, s.minimapOffsetX, s.minimapOffsetY, s.minimapCentered, s.minimapSize, s.minimapEnlarged, s.minimap};
		});
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.getKey("key.keyboard.z"));
			net.minecraft.client.KeyMapping.resetMapping();
			Settings.get().minimap = true;
			Settings.get().minimapEnlarged = 200;
		});
		world.getServer().runCommand("effect clear @a");
		try {
			upgrade(context, world);
			screen(context);
			afterwards(context, world);
		} finally {
			context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
			world.getServer().runCommand("effect clear @a");
			context.getInput().resizeWindow(1600, 900);
			DioramaClientTest.setGuiScale(context, guiScale);
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				Settings s = Settings.get();
				s.minimapCorner = (Settings.Corner) saved[0];
				s.minimapOffsetX = (int) saved[1];
				s.minimapOffsetY = (int) saved[2];
				s.minimapCentered = (boolean) saved[3];
				s.minimapSize = (int) saved[4];
				s.minimapEnlarged = (int) saved[5];
				s.minimap = (boolean) saved[6];
				s.save();
				DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.UNKNOWN);
				net.minecraft.client.KeyMapping.resetMapping();
			});
		}
	}

	/** A 0.9.0 settings file in each corner puts the minimap exactly where 0.9.0 did. */
	private static void upgrade(ClientGameTestContext context, TestSingleplayerContext world) {
		int checked = 0;
		for (int[] window : new int[][] {{1600, 900}, {854, 480}}) {
			context.getInput().resizeWindow(window[0], window[1]);
			context.waitTicks(3);
			for (int size : new int[] {96, 224}) {
				for (Settings.Corner corner : Settings.Corner.values()) {
					loadOldFile(context, corner, size);
					context.waitTicks(3);
					expectOldPlace(context, corner, size, new int[] {0, 0}, "a 0.9.0 file in the " + corner + " corner at size " + size + " on a " + window[0] + "x" + window[1] + " window");
					if (size == 96 && window[0] == 1600) {
						context.takeScreenshot("minimap-position-01-upgrade-" + corner.name().toLowerCase(Locale.ROOT));
					}
					checked++;
				}
			}
		}
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(3);
		// Enlarged, and below the effect icons, as 0.9.0 drew it.
		context.getInput().holdKey(DioramaClient.ENLARGE_MINIMAP);
		for (Settings.Corner corner : Settings.Corner.values()) {
			loadOldFile(context, corner, 96);
			context.waitTicks(3);
			expectOldPlace(context, corner, 192, new int[] {0, 0}, "a 0.9.0 file in the " + corner + " corner, enlarged");
			checked++;
		}
		context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
		world.getServer().runCommand("effect give @a speed 600 0");
		world.getServer().runCommand("effect give @a weakness 600 0");
		context.waitTicks(10);
		for (Settings.Corner corner : Settings.Corner.values()) {
			loadOldFile(context, corner, 96);
			context.waitTicks(3);
			expectOldPlace(context, corner, 96, new int[] {1, 1}, "a 0.9.0 file in the " + corner + " corner with two status effects");
			checked++;
		}
		context.takeScreenshot("minimap-position-01-upgrade-effects-bottom_left");
		loadOldFile(context, Settings.Corner.TOP_RIGHT, 96);
		context.waitTicks(3);
		context.takeScreenshot("minimap-position-01-upgrade-effects-top_right");
		world.getServer().runCommand("effect clear @a");
		System.out.println("[diorama-test] minimap position: " + checked + " 0.9.0 placements matched");
	}

	/** The settings file as 0.9.0 wrote it: today's, less what came with the position screen. */
	private static void loadOldFile(ClientGameTestContext context, Settings.Corner corner, int size) {
		context.runOnClient(mc -> {
			Settings.get().save();
			Path file = mc.gameDirectory.toPath().resolve("diorama").resolve("config.json");
			try {
				JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				json.remove("minimapOffsetX");
				json.remove("minimapOffsetY");
				json.remove("minimapCentered");
				json.addProperty("minimapCorner", corner.name());
				json.addProperty("minimapSize", size);
				Files.writeString(file, json.toString());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			Settings s = Settings.reload();
			if (s.minimapCorner != corner || s.minimapOffsetX != MARGIN || s.minimapOffsetY != MARGIN || s.minimapCentered) {
				throw new AssertionError("A 0.9.0 file in the " + corner + " corner loaded as " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY + " centered " + s.minimapCentered);
			}
		});
	}

	/** Where 0.9.0's MinimapHud put the minimap: x, y, size. */
	private static int[] oldPlace(Settings.Corner corner, int wanted, int width, int height, int infoHeight, int[] effects) {
		boolean right = corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.BOTTOM_RIGHT;
		boolean top = corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.TOP_LEFT;
		int rows = top && right ? effects[1] > 0 ? 52 : effects[0] > 0 ? 26 : 0 : 0;
		int fitted = wanted;
		if (top) {
			fitted = Math.min(fitted, height - MARGIN - rows - infoHeight - MARGIN - 4);
			fitted = Math.min(fitted, width - 2 * MARGIN - 4);
		} else {
			fitted = Math.min(fitted, width / 2 - 91 - 2 * MARGIN - 4);
			fitted = Math.min(fitted, height - infoHeight - 2 * MARGIN);
		}
		int size = Math.max(64, Math.min(wanted, fitted));
		return new int[] {right ? width - size - MARGIN : MARGIN, top ? MARGIN + rows : height - size - MARGIN - infoHeight, size};
	}

	private static void expectOldPlace(ClientGameTestContext context, Settings.Corner corner, int wanted, int[] effects, String what) {
		context.runOnClient(mc -> {
			int[] box = shown(mc, what);
			int[] old = oldPlace(corner, wanted, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), box[3] - box[2], effects);
			if (box[0] != old[0] || box[1] != old[1] || box[2] != old[2]) {
				throw new AssertionError(what + " drew the minimap at " + Arrays.toString(box) + "; 0.9.0 drew it at x, y, size " + Arrays.toString(old));
			}
		});
	}

	private static int[] shown(Minecraft mc, String what) {
		int[] box = MinimapHud.shown();
		if (box == null) {
			throw new AssertionError("The minimap wasn't drawn: " + what);
		}
		return box;
	}

	private static int[] shown(ClientGameTestContext context, String what) {
		return context.computeOnClient(mc -> shown(mc, what));
	}

	/** The position screen, from the settings, driven with the mouse and keys. */
	private static void screen(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapOffsetX = MARGIN;
			s.minimapOffsetY = MARGIN;
			s.minimapCentered = false;
			s.minimapSize = 96;
			s.save();
		});
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("minimap-position-02-settings");
		DioramaClientTest.click(context, "Position: ");
		expectPositionScreen(context, "Clicking Position");
		DioramaClientTest.settle(context, 10);
		context.takeScreenshot("minimap-position-03-screen");

		// Dragged to the middle, then Esc: back in the top-right corner.
		int[] size = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		dragCentreTo(context, size[0] / 2.0, size[1] / 3.0);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(3);
		expectBack(context, true, "Esc");
		// The same with Cancel.
		DioramaClientTest.click(context, "Position: ");
		expectPositionScreen(context, "Clicking Position after Esc");
		dragCentreTo(context, size[0] / 2.0, size[1] / 3.0);
		DioramaClientTest.click(context, "Cancel");
		expectBack(context, true, "Cancel");
		// And when the screen goes some other way, as when the player dies while it's open.
		DioramaClientTest.click(context, "Position: ");
		expectPositionScreen(context, "Clicking Position after Cancel");
		dragCentreTo(context, size[0] / 2.0, size[1] / 3.0);
		context.setScreen(() -> null);
		expectBack(context, false, "Closing the screen without Done");
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		DioramaClientTest.click(context, "Position: ");
		expectPositionScreen(context, "Clicking Position again");

		// The middle of the screen.
		dragCentreTo(context, size[0] / 2.0, size[1] / 2.0);
		int[] middle = shown(context, "after the drag to the middle");
		double mx = middle[0] + middle[2] / 2.0;
		double my = middle[1] + middle[3] / 2.0;
		if (Math.abs(mx - size[0] / 2.0) > 2 || Math.abs(my - size[1] / 2.0) > 2) {
			throw new AssertionError("Dragged to the middle, the minimap's middle is at " + mx + "," + my + " on a " + size[0] + "x" + size[1] + " screen");
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("minimap-position-04-middle");

		// Past the left edge: it stops at the margin, kept by the left edge.
		dragCentreTo(context, -40, size[1] * 0.3);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			int[] box = shown(mc, "at the left edge");
			if (box[0] != MARGIN || s.minimapOffsetX != MARGIN || s.minimapCorner != Settings.Corner.TOP_LEFT) {
				throw new AssertionError("Dragged past the left edge, the minimap is at " + Arrays.toString(box) + ", kept as " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY);
			}
		});
		context.takeScreenshot("minimap-position-05-left-edge");

		// Down past the bottom edge, a third of the way across: kept by the bottom edge. It's
		// reaching over the hotbar there, as the player put it, so it keeps its size.
		dragCentreTo(context, size[0] * 0.3, size[1] + 40);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			int[] box = shown(mc, "at the bottom edge");
			int bottom = box[1] + box[3];
			if (bottom != size[1] - MARGIN || box[2] != 96 || s.minimapOffsetY != MARGIN || s.minimapCorner != Settings.Corner.BOTTOM_LEFT) {
				throw new AssertionError("Dragged past the bottom edge, the minimap is at " + Arrays.toString(box) + " (bottom " + bottom + "), kept as " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY);
			}
		});
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("minimap-position-06-bottom-edge");

		// C: midway across the bottom; C again: back by its side.
		context.getInput().pressKey(InputConstants.KEY_C);
		context.waitTicks(3);
		context.runOnClient(mc -> {
			int[] box = shown(mc, "centered");
			if (!Settings.get().minimapCentered || box[0] != (size[0] - box[2]) / 2) {
				throw new AssertionError("C should center the minimap, got " + Arrays.toString(box) + " centered " + Settings.get().minimapCentered);
			}
		});
		context.takeScreenshot("minimap-position-07-centered");
		context.getInput().pressKey(InputConstants.KEY_C);
		context.waitTicks(3);
		if (context.computeOnClient(mc -> Settings.get().minimapCentered || shown(mc, "uncentered")[0] > size[0] * 0.3)) {
			throw new AssertionError("C again should put the minimap back by the left edge");
		}

		// The Corner button: from a place of its own to the top-right corner, then on to the top left.
		DioramaClientTest.click(context, "Corner: Custom");
		expectCorner(context, Settings.Corner.TOP_RIGHT, "Corner: Top right");
		DioramaClientTest.click(context, "Corner: Top right");
		expectCorner(context, Settings.Corner.TOP_LEFT, "Corner: Top left");
		context.takeScreenshot("minimap-position-07-corner-top-left");

		// Its place for the rest: 40 in from the right edge, a third of the way down. Done keeps it.
		dragCentreTo(context, size[0] - 40 - 48, size[1] / 3.0);
		int[] placed = context.computeOnClient(mc -> new int[] {Settings.get().minimapOffsetX, Settings.get().minimapOffsetY, Settings.get().minimapCorner.ordinal()});
		if (placed[2] != Settings.Corner.TOP_RIGHT.ordinal() || Math.abs(placed[0] - 40) > 2) {
			throw new AssertionError("Dragged 40 in from the right, the minimap is kept as " + Arrays.toString(placed));
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("minimap-position-08-placed");
		DioramaClientTest.click(context, "Done");
		context.runOnClient(mc -> {
			Settings s = Settings.reload();
			if (!(mc.gui.screen() instanceof SettingsScreen) || s.minimapOffsetX != placed[0] || s.minimapOffsetY != placed[1] || s.minimapCorner != Settings.Corner.TOP_RIGHT) {
				throw new AssertionError("Done should save the place and go back to the settings, got " + mc.gui.screen() + " " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY);
			}
		});
		context.waitTicks(3);
		context.takeScreenshot("minimap-position-09-settings-custom");
	}

	/** Back in the top-right corner, where the screen opened with it, on the settings screen or in the game. */
	private static void expectBack(ClientGameTestContext context, boolean toSettings, String what) {
		context.waitTicks(3);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			boolean screen = toSettings ? mc.gui.screen() instanceof SettingsScreen : mc.gui.screen() == null;
			if (!screen || s.minimapCorner != Settings.Corner.TOP_RIGHT || s.minimapOffsetX != MARGIN || s.minimapOffsetY != MARGIN || s.minimapCentered) {
				throw new AssertionError(what + " should leave the minimap where it was, got " + mc.gui.screen() + " " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY);
			}
		});
	}

	private static void expectCorner(ClientGameTestContext context, Settings.Corner corner, String label) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			int[] box = shown(mc, "in the " + corner + " corner");
			boolean right = corner == Settings.Corner.TOP_RIGHT;
			int x = right ? mc.getWindow().getGuiScaledWidth() - MARGIN - box[2] : MARGIN;
			if (s.minimapCorner != corner || s.minimapOffsetX != MARGIN || s.minimapOffsetY != MARGIN || s.minimapCentered || box[0] != x || box[1] != MARGIN) {
				throw new AssertionError("The Corner button should put the minimap in the " + corner + " corner, got " + Arrays.toString(box) + " kept as " + s.minimapCorner + " " + s.minimapOffsetX + "," + s.minimapOffsetY);
			}
			boolean labelled = false;
			java.util.List<net.minecraft.client.gui.components.events.GuiEventListener> children = new java.util.ArrayList<>(mc.gui.screen().children());
			for (var child : children) {
				labelled |= child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.getMessage().getString().equals(label);
			}
			if (!labelled) {
				throw new AssertionError("The Corner button should say " + label);
			}
		});
	}

	private static void expectPositionScreen(ClientGameTestContext context, String what) {
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MinimapPositionScreen)) {
			throw new AssertionError(what + " didn't open the position screen");
		}
	}

	/** Holds the minimap, with its lines, by its middle and drags that to {@code x, y} in GUI pixels, as a player would. */
	private static void dragCentreTo(ClientGameTestContext context, double x, double y) {
		int[] box = shown(context, "before a drag");
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		double fromX = box[0] + box[2] / 2.0;
		double fromY = box[1] + box[3] / 2.0;
		context.getInput().setCursorPos(fromX * scale, fromY * scale);
		context.waitTicks(2);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		int steps = 10;
		for (int i = 0; i < steps; i++) {
			context.getInput().moveCursor((x - fromX) * scale / steps, (y - fromY) * scale / steps);
			context.waitTicks(1);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
	}

	/** In the game, with the place Done kept: a smaller window, GUI scales, the enlarge key and the effect icons. */
	private static void afterwards(ClientGameTestContext context, TestSingleplayerContext world) {
		context.setScreen(() -> null);
		DioramaClientTest.settle(context, 20);
		int[] kept = context.computeOnClient(mc -> new int[] {Settings.get().minimapOffsetX, Settings.get().minimapOffsetY});
		context.takeScreenshot("minimap-position-10-in-game");
		expectKept(context, kept, "in the game");

		context.getInput().resizeWindow(1000, 700);
		DioramaClientTest.settle(context, 10);
		expectKept(context, kept, "on a 1000x700 window");
		context.takeScreenshot("minimap-position-11-window-1000x700");
		context.getInput().resizeWindow(480, 360);
		context.waitTicks(10);
		expectOnScreen(context, "on a 480x360 window");
		context.takeScreenshot("minimap-position-12-window-480x360");
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(5);
		for (int scale = 1; scale <= 4; scale++) {
			DioramaClientTest.setGuiScale(context, scale);
			context.waitTicks(5);
			expectOnScreen(context, "at GUI scale " + scale);
			context.takeScreenshot("minimap-position-13-gui-scale-" + scale);
		}
		DioramaClientTest.setGuiScale(context, 3);
		context.waitTicks(5);
		expectKept(context, kept, "back at GUI scale 3");

		// Enlarged it grows away from the right and top edges it's kept by.
		context.getInput().holdKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(5);
		context.runOnClient(mc -> {
			int[] box = shown(mc, "enlarged");
			int width = mc.getWindow().getGuiScaledWidth();
			if (box[2] != 192 || box[0] + box[2] != width - kept[0] || box[1] != kept[1]) {
				throw new AssertionError("Enlarged, the minimap should grow left and down from its place, got " + Arrays.toString(box) + " on a " + width + " wide screen");
			}
		});
		expectOnScreen(context, "enlarged");
		context.takeScreenshot("minimap-position-14-enlarged");
		context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(3);

		// Effect icons: the minimap at the top moves below the rows it would be over, and only those.
		context.runOnClient(mc -> Settings.get().minimapOffsetY = MARGIN);
		world.getServer().runCommand("effect give @a speed 600 0");
		world.getServer().runCommand("effect give @a weakness 600 0");
		context.waitTicks(10);
		// One icon in each row reaches 25 in from the right; 40 in, the minimap is beside them.
		expectTop(context, MARGIN, "40 in from the right with one icon in each row");
		context.takeScreenshot("minimap-position-15-effects-beside");
		// In the corner it's under both rows, as in 0.9.0.
		context.runOnClient(mc -> Settings.get().minimapOffsetX = MARGIN);
		context.waitTicks(3);
		expectTop(context, MARGIN + 52, "in the corner with one icon in each row");
		context.takeScreenshot("minimap-position-16-effects-corner");
		// Three beneficial icons reach 75 in: under that row only.
		context.runOnClient(mc -> Settings.get().minimapOffsetX = kept[0]);
		world.getServer().runCommand("effect give @a haste 600 0");
		world.getServer().runCommand("effect give @a strength 600 0");
		context.waitTicks(10);
		expectTop(context, MARGIN + 26, "40 in from the right under three beneficial icons");
		context.takeScreenshot("minimap-position-17-effects-long-row");
		// Centered, far from them, it stays up, until the row reaches the middle of the screen.
		context.runOnClient(mc -> Settings.get().minimapCentered = true);
		context.waitTicks(3);
		expectTop(context, MARGIN, "centered, away from three beneficial icons");
		context.takeScreenshot("minimap-position-18-effects-centered");
		for (String effect : new String[] {"jump_boost", "regeneration", "resistance", "fire_resistance", "water_breathing", "night_vision", "absorption"}) {
			world.getServer().runCommand("effect give @a " + effect + " 600 0");
		}
		context.waitTicks(10);
		expectTop(context, MARGIN + 26, "centered under ten beneficial icons");
		context.takeScreenshot("minimap-position-19-effects-centered-reaching");
		// Kept by the bottom-right corner, 80 up, enlarged: it grows up only as far as the icons.
		world.getServer().runCommand("effect give @a weakness 600 0");
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCentered = false;
			s.minimapCorner = Settings.Corner.BOTTOM_RIGHT;
			s.minimapOffsetX = MARGIN;
			s.minimapOffsetY = 80;
		});
		context.getInput().holdKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(10);
		context.runOnClient(mc -> {
			int[] box = shown(mc, "enlarged from the bottom under effects");
			int height = mc.getWindow().getGuiScaledHeight();
			if (box[1] < MARGIN + 52 || box[1] + box[3] != height - 80 || box[2] <= 96) {
				throw new AssertionError("Enlarged from 80 above the bottom, the minimap should grow up to below the effect icons, got " + Arrays.toString(box));
			}
		});
		context.takeScreenshot("minimap-position-20-effects-enlarged-from-bottom");
		context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
		world.getServer().runCommand("effect clear @a");
		context.runOnClient(mc -> Settings.get().minimapCentered = false);
	}

	private static void expectTop(ClientGameTestContext context, int y, String what) {
		context.runOnClient(mc -> {
			int[] box = shown(mc, what);
			if (box[1] != y) {
				throw new AssertionError(what + ", the minimap's top should be at " + y + ", got " + Arrays.toString(box) + " on a " + mc.getWindow().getGuiScaledWidth() + " wide screen");
			}
		});
	}

	/** Still {@code kept} in from the right and top edges. */
	private static void expectKept(ClientGameTestContext context, int[] kept, String what) {
		context.runOnClient(mc -> {
			int[] box = shown(mc, what);
			int width = mc.getWindow().getGuiScaledWidth();
			if (box[0] + box[2] != width - kept[0] || box[1] != kept[1]) {
				throw new AssertionError(what + ", the minimap should stay " + kept[0] + " in from the right and " + kept[1] + " down, got " + Arrays.toString(box) + " on a " + width + " wide screen");
			}
		});
	}

	private static void expectOnScreen(ClientGameTestContext context, String what) {
		context.runOnClient(mc -> {
			int[] box = shown(mc, what);
			int width = mc.getWindow().getGuiScaledWidth();
			int height = mc.getWindow().getGuiScaledHeight();
			if (box[0] < 0 || box[1] < 0 || box[0] + box[2] > width || box[1] + box[3] > height) {
				throw new AssertionError(what + ", the minimap is off the " + width + "x" + height + " screen: " + Arrays.toString(box));
			}
		});
	}
}
