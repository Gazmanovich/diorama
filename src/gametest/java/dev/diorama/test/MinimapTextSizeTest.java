package dev.diorama.test;

import dev.diorama.MinimapHud;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.render.TextSize;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The minimap's text size (opt-in: {@code minimap-text-size}): the compass letters and the lines
 * under the minimap at every size each GUI scale offers. The N is measured in the screenshot, so a
 * size that isn't drawn at whole screen pixels, or isn't drawn at all, fails.
 */
final class MinimapTextSizeTest {
	/** The N's own red, which nothing else on the minimap uses. */
	private static final int NORTH = 0xFF6B6B;
	/** The game time's colour once a bed would work. */
	private static final int BED_TIME = 0x9CC4FF;
	private static final int[][] SIZES = {
		{1, 100, 200, 300, 400},
		{2, 50, 100, 150, 200},
		{3, 33, 67, 100, 133, 167, 200},
		{4, 25, 50, 75, 100, 125, 150, 175, 200}
	};

	private MinimapTextSizeTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		// At night, when the game time is drawn in a blue nothing else on the screen has.
		world.getServer().runCommand("time set midnight");
		world.getServer().runCommand("weather clear");
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		Settings.GameTime gameTime = context.computeOnClient(mc -> Settings.get().gameTime);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			if (settings.minimapTextSize != 100) {
				throw new AssertionError("The text size should start at 100%, not " + settings.minimapTextSize);
			}
			settings.compass = true;
			settings.coordinates = true;
			settings.biome = true;
			settings.gameTime = Settings.GameTime.ONLY_24H;
			settings.minimapSize = 128;
		});
		// Before 1.21.2 the client only works out that it's dark when the minimap asks for the time.
		context.waitFor(mc -> mc.level.isDarkOutside(), 400);
		// Big enough for a GUI scale of 4, which a smaller window clamps to 3. Long enough for the commands' chat lines to fade.
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(220);
		for (int[] row : SIZES) {
			int gui = row[0];
			DioramaClientTest.setGuiScale(context, gui);
			int[] offered = context.computeOnClient(mc -> TextSize.sizes());
			if (!Arrays.equals(offered, Arrays.copyOfRange(row, 1, row.length))) {
				throw new AssertionError("At GUI scale " + gui + " the text sizes are " + Arrays.toString(offered));
			}
			for (int i = 1; i < row.length; i++) {
				int size = row[i];
				// How many screen pixels to a font pixel this size is.
				int pixels = Math.round(size * gui / 100.0F);
				context.runOnClient(mc -> {
					Settings.get().minimapTextSize = size;
					Settings.get().minimapShape = Settings.Shape.SQUARE;
				});
				context.waitTicks(4);
				BufferedImage shot = read(context.takeScreenshot("text-size-gui" + gui + "-" + size));
				int[] box = context.computeOnClient(mc -> MinimapHud.shown());
				int guiHeight = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
				if (box == null) {
					throw new AssertionError("No minimap at " + size + "% on GUI scale " + gui);
				}
				// Three lines, ten font pixels each.
				int lines = (int) Math.ceil(30.0 * pixels / gui);
				if (box[3] - box[2] != lines || box[1] + box[3] > guiHeight) {
					throw new AssertionError("At " + size + "% on GUI scale " + gui + " the minimap is " + box[2] + " with " + (box[3] - box[2]) + " under it at y " + box[1] + ", on a screen " + guiHeight + " high");
				}
				// The N is seven font pixels high and five wide, on the minimap's top edge.
				int[] north = extent(shot, NORTH, box[0] * gui, box[1] * gui, (box[0] + box[2]) * gui, (box[1] + box[2]) * gui);
				if (north[3] - north[1] + 1 != 7 * pixels || north[2] - north[0] + 1 != 5 * pixels || north[1] != box[1] * gui + pixels) {
					throw new AssertionError("At " + size + "% on GUI scale " + gui + " the N covers " + Arrays.toString(north) + ", which isn't " + pixels + " screen pixels to a pixel from the minimap's top at " + box[1] * gui);
				}
				// The time is the third line: digits seven font pixels high, two lines of ten below the first, which starts four GUI pixels under the minimap.
				int[] time = extent(shot, BED_TIME, 0, (box[1] + box[2]) * gui, shot.getWidth(), shot.getHeight());
				int top = (box[1] + box[2] + 4) * gui + 20 * pixels;
				if (time[1] != top || time[3] - time[1] + 1 != 7 * pixels || time[2] != (box[0] + box[2]) * gui - pixels - 1) {
					throw new AssertionError("At " + size + "% on GUI scale " + gui + " the time covers " + Arrays.toString(time) + ", which isn't " + pixels + " screen pixels to a pixel from " + top + " down, ending at the minimap's right edge at " + (box[0] + box[2]) * gui);
				}
			}
		}
		// The biggest text on a round minimap in the bottom left, where the lines grow up from the edge.
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.ROUND;
			Settings.get().minimapCorner = Settings.Corner.BOTTOM_LEFT;
		});
		context.waitTicks(4);
		context.takeScreenshot("text-size-round-bottom-left");
		int[] low = context.computeOnClient(mc -> new int[] {MinimapHud.shown()[0], MinimapHud.shown()[1], MinimapHud.shown()[3], mc.getWindow().getGuiScaledHeight()});
		if (low[0] != MinimapHud.MARGIN || low[1] + low[2] != low[3] - MinimapHud.MARGIN) {
			throw new AssertionError("In the bottom left with the biggest text the minimap is at " + low[0] + ", " + low[1] + " and " + low[2] + " high with its lines, on a screen " + low[3] + " high");
		}
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.SQUARE;
			Settings.get().minimapCorner = Settings.Corner.TOP_RIGHT;
			Settings.get().minimapTextSize = 100;
		});

		// The slider, in the Zoom and Opacity row: a real click at its right end picks the biggest.
		DioramaClientTest.setGuiScale(context, 3);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("text-size-settings");
		DioramaClientTest.clickAlong(context, "Text size: 100%", 0.98);
		if (context.computeOnClient(mc -> Settings.get().minimapTextSize) != 200) {
			throw new AssertionError("Clicking the end of Text size picked " + context.computeOnClient(mc -> Settings.get().minimapTextSize) + "%");
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("text-size-settings-200");
		context.setScreen(() -> null);
		// The settings at the two smallest GUIs the game makes: 427x240 and 320x240.
		for (int[] window : new int[][] {{1280, 720}, {640, 480}}) {
			context.getInput().resizeWindow(window[0], window[1]);
			context.waitTicks(10);
			context.setScreen(() -> new SettingsScreen(null));
			context.waitTicks(5);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			context.takeScreenshot("text-size-settings-" + window[0] + "x" + window[1]);
			context.setScreen(() -> null);
			context.waitTicks(3);
			context.takeScreenshot("text-size-hud-" + window[0] + "x" + window[1]);
		}

		world.getServer().runCommand("time set noon");
		DioramaClientTest.setGuiScale(context, guiScale);
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			settings.minimapTextSize = 100;
			settings.minimapSize = 96;
			settings.gameTime = gameTime;
			// The click saved its choice, and the next run starts from what's saved.
			settings.save();
		});
	}

	/** The box around every pixel of this colour inside a part of a screenshot: x0, y0, x1, y1. */
	private static int[] extent(BufferedImage shot, int rgb, int left, int top, int right, int bottom) {
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
		for (int y = Math.max(0, top); y < Math.min(shot.getHeight(), bottom); y++) {
			for (int x = Math.max(0, left); x < Math.min(shot.getWidth(), right); x++) {
				if (close(shot.getRGB(x, y), rgb)) {
					box[0] = Math.min(box[0], x);
					box[1] = Math.min(box[1], y);
					box[2] = Math.max(box[2], x);
					box[3] = Math.max(box[3], y);
				}
			}
		}
		return box;
	}

	/** Before 1.21.6 the game draws text a shade off the colour it's given. */
	private static boolean close(int argb, int rgb) {
		return Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 4 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 4 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 4;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
