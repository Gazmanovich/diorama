package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.ExportScreen;
import dev.diorama.MapExport;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.data.TileStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Exporting the map as an image, through the screen as a player does: the Export button, the
 * Area box dragged bigger, panning beside it, a detail button, Save image, then Whole map. Each
 * saved PNG is opened and checked against what the screen said it would be.
 */
final class ImageExportTest {
	private ImageExportTest() {
	}

	/** A drawn line through the player and a note under it, in colours the test world's ground doesn't have. */
	private static final int LINE = 0xFF00FF;
	private static final int NOTE = 0xFFFF55;
	/** A note in Arabic, which the game shapes and reverses in a right-to-left language. */
	private static final int ARABIC = 0xFF55FF;
	/** A note in characters only Unifont has. */
	private static final int CJK = 0x55FFFF;

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<Drawing> made = new java.util.ArrayList<>();
		double[] drawn = context.computeOnClient(mc -> {
			double u = mc.player.getX();
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			String dimension = mc.level.dimension().identifier().toString();
			made.add(new Drawing(Drawing.Kind.STROKE, dimension, 0xFF000000 | LINE, new double[] {u - 20, v, u + 20, v}, ""));
			made.add(new Drawing(Drawing.Kind.NOTE, dimension, 0xFF000000 | NOTE, new double[] {u, v + 30}, "Hi"));
			made.add(new Drawing(Drawing.Kind.NOTE, dimension, 0xFF000000 | CJK, new double[] {u, v - 30}, "\u5730\u56fe"));
			made.forEach(DrawingStore.get()::add);
			return new double[] {u, v};
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 1.0, TileKey.SURFACE));
		settle(context);

		// The Export button above the draw and zoom buttons, with its tooltip, opens the export screen.
		double[] button = context.computeOnClient(mc -> {
			double[] c = centre(((MapScreen) mc.gui.screen()).exportButton());
			double scale = mc.getWindow().getGuiScale();
			return new double[] {c[0] * scale, c[1] * scale};
		});
		context.getInput().setCursorPos(button[0], button[1]);
		context.waitTicks(10);
		context.takeScreenshot("image-export-00-button");
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof ExportScreen)) {
			throw new AssertionError("The Export button didn't open the export screen");
		}
		settle(context);
		context.takeScreenshot("image-export-01-area");

		// Dragging the box's bottom-right corner makes it bigger; dragging outside it pans and leaves it alone.
		double[] before = box(context);
		drag(context, before[2], before[3], 40, 30);
		double[] grown = box(context);
		if (!(grown[2] > before[2] + 30 && grown[3] > before[3] + 20 && Math.abs(grown[0] - before[0]) < 1)) {
			throw new AssertionError("Dragging the box's corner didn't resize it: " + List.of(before[2], before[3]) + " -> " + List.of(grown[2], grown[3]));
		}
		ImageExport.Area beforePan = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		double[] outside = {grown[2] + 20, grown[1] + 10};
		drag(context, outside[0], outside[1], -30, 0);
		double[] panned = box(context);
		ImageExport.Area afterPan = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		if (!(Math.abs(panned[0] - (grown[0] - 30)) < 2)) {
			throw new AssertionError("Dragging beside the box didn't pan the map under it");
		}
		if (!beforePan.equals(afterPan)) {
			// Moving the box would also shift it on screen; panning leaves the ground it covers alone.
			throw new AssertionError("Dragging beside the box moved what it saves: " + beforePan + " -> " + afterPan);
		}

		// 1 px per block, then Save image.
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).detailButton(2)), false);
		ImageExport.Area area = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		if (area == null || area.lod() != 2) {
			throw new AssertionError("The 1 px per block button didn't choose that detail: " + area);
		}
		context.takeScreenshot("image-export-02-area-resized");
		Path areaFile = save(context);
		checkImage(areaFile, area);
		// The drawings are in, ticked by default: the line through the player, the note's text under it.
		if (!lineAt(areaFile, area, drawn)) {
			throw new AssertionError("The drawn line isn't in " + areaFile.getFileName());
		}
		// Its text in its colour, and the game's shadow, a quarter of it, behind.
		if (countNear(areaFile, area, drawn[0], drawn[1] + 30, NOTE) < 40 || countNear(areaFile, area, drawn[0], drawn[1] + 30, (NOTE & 0xFCFCFC) >> 2) < 10) {
			throw new AssertionError("The note isn't in " + areaFile.getFileName());
		}
		// Two Unifont characters are 8 GUI pixels across each; the game's box for a missing one is 5,
		// so two boxes span 22 export pixels and the real glyphs about 31.
		int[] cjk = spanNear(areaFile, area, drawn[0], drawn[1] - 30, CJK);
		if (cjk[0] < 30 || cjk[1] < 28) {
			throw new AssertionError("The note in Unifont characters isn't in " + areaFile.getFileName() + ": " + cjk[0] + " pixels over " + cjk[1]);
		}

		// Whole map: everything explored, boxed with clouds round it.
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		click(context, mc -> {
			int[] tab = ((ExportScreen) mc.gui.screen()).tabCenter(1);
			return new double[] {tab[0], tab[1]};
		}, false);
		context.waitFor(mc -> mc.gui.screen() instanceof ExportScreen screen && screen.plannedArea() != null, 600);
		// Untick Drawings: this one is the ground alone.
		click(context, mc -> new double[] {16, 114}, false);
		settle(context);
		context.takeScreenshot("image-export-03-whole");
		ImageExport.Area whole = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		coversMappedChunks(context, whole);
		Path wholeFile = save(context);
		checkImage(wholeFile, whole);
		matchesTiles(context, wholeFile, whole);
		if (lineAt(wholeFile, whole, drawn)) {
			throw new AssertionError("Drawings were unticked, but the line is in " + wholeFile.getFileName());
		}
		// Only this test's drawings: other sections' stay as they left them.
		context.runOnClient(mc -> DrawingStore.get().batch(() -> made.forEach(DrawingStore.get()::remove)));
		noteMatchesMap(context, drawn);
		context.setScreen(() -> null);
	}

	/**
	 * A note in the image looks as it does on the map: the same glyphs in the same order, in a
	 * right-to-left language too, where the game shapes Arabic and reverses the line. At GUI scale 2
	 * a GUI pixel is 2 screen pixels, as it's 2 pixels in an export, so the note's text in a
	 * screenshot of the map and in the image are compared pixel for pixel.
	 */
	private static void noteMatchesMap(ClientGameTestContext context, double[] at) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		String language = context.computeOnClient(mc -> mc.options.languageCode);
		DioramaClientTest.setGuiScale(context, 2);
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.options.languageCode = "ar_sa";
			mc.getLanguageManager().setSelected("ar_sa");
		});
		double u = at[0];
		double v = at[1] + 60;
		Drawing note = context.computeOnClient(mc -> {
			Drawing made = new Drawing(Drawing.Kind.NOTE, mc.level.dimension().identifier().toString(), 0xFF000000 | ARABIC, new double[] {u, v},
				"\u0645\u0631\u062d\u0628\u0627 Diorama");
			DrawingStore.get().add(made);
			return made;
		});
		// Mob markers mustn't cover the note on the map.
		boolean radar = context.computeOnClient(mc -> Settings.get().radarOnMap);
		context.runOnClient(mc -> Settings.get().radarOnMap = false);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, 1.0, TileKey.SURFACE));
		context.getInput().setCursorPos(0, 0);
		settle(context);
		double[] onScreen = context.computeOnClient(mc -> {
			var view = ((MapScreen) mc.gui.screen()).view();
			double g = mc.getWindow().getGuiScale();
			return new double[] {view.screenX(u) * g, view.screenY(v) * g};
		});
		BufferedImage shot = read(context.takeScreenshot("image-export-04-arabic-note"));
		boolean[][] mapText = mask(shot, (int) onScreen[0], (int) onScreen[1], ARABIC);

		Set<Path> old = new HashSet<>(exports(context));
		ImageExport.Area area = ImageExport.Area.ofUnits(2, u - 80, v - 30, u + 80, v + 30);
		boolean started = context.computeOnClient(mc -> MapExport.start(MapSession.get().map().savedMap(), area, false, List.of(note)));
		if (!started) {
			throw new AssertionError("Another export was still running");
		}
		context.waitFor(mc -> MapExport.running() == null, 2400);
		Path file = exports(context).stream().filter(p -> !old.contains(p)).findFirst().orElseThrow();
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		boolean[][] imageText = mask(read(file), (int) Math.floor(u * perUnit - area.x()), (int) Math.floor(v * perUnit - area.y()), ARABIC);

		context.runOnClient(mc -> DrawingStore.get().remove(note));
		context.runOnClient(mc -> Settings.get().radarOnMap = radar);
		context.setScreen(() -> null);
		DioramaClientTest.reloadPacks(context, mc -> {
			mc.options.languageCode = language;
			mc.getLanguageManager().setSelected(language);
		});
		DioramaClientTest.setGuiScale(context, scale);

		int differ = 0;
		int ink = 0;
		if (mapText.length != imageText.length || mapText.length == 0 || mapText[0].length != imageText[0].length) {
			throw new AssertionError("The Arabic note's text is " + size(imageText) + " in the image but " + size(mapText) + " on the map");
		}
		for (int y = 0; y < mapText.length; y++) {
			for (int x = 0; x < mapText[0].length; x++) {
				ink += mapText[y][x] ? 1 : 0;
				differ += mapText[y][x] != imageText[y][x] ? 1 : 0;
			}
		}
		if (ink < 60 || differ > ink / 20) {
			throw new AssertionError("The Arabic note's text differs from the map's in " + differ + " of " + ink + " pixels");
		}
	}

	/**
	 * Pixels of a colour around a point, cut to the box they fill: a note's text. Within a few
	 * levels: some versions draw text a shade off its colour (1.21.1 draws 0xFF55FF as 0xFC54FC).
	 */
	private static boolean[][] mask(BufferedImage image, int cx, int cy, int colour) {
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		for (int y = Math.max(0, cy - 24); y < Math.min(image.getHeight(), cy + 24); y++) {
			for (int x = Math.max(0, cx - 120); x < Math.min(image.getWidth(), cx + 120); x++) {
				if (close(image.getRGB(x, y) & 0xFFFFFF, colour, 8)) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0) {
			return new boolean[0][0];
		}
		boolean[][] out = new boolean[y1 - y0 + 1][x1 - x0 + 1];
		for (int y = y0; y <= y1; y++) {
			for (int x = x0; x <= x1; x++) {
				out[y - y0][x - x0] = close(image.getRGB(x, y) & 0xFFFFFF, colour, 8);
			}
		}
		return out;
	}

	private static String size(boolean[][] mask) {
		return mask.length == 0 ? "missing" : mask[0].length + "x" + mask.length;
	}

	/** Presses Save image, waits for the file, and returns it. */
	private static Path save(ClientGameTestContext context) {
		Set<Path> old = new HashSet<>(exports(context));
		click(context, mc -> centre(((ExportScreen) mc.gui.screen()).saveButton()), false);
		context.waitTicks(2);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen)) {
			throw new AssertionError("Save image didn't go back to the map");
		}
		context.waitFor(mc -> MapExport.running() == null, 2400);
		List<Path> made = exports(context).stream().filter(p -> !old.contains(p)).toList();
		if (made.size() != 1) {
			throw new AssertionError("Expected one new export, found " + made);
		}
		return made.get(0);
	}

	private static List<Path> exports(ClientGameTestContext context) {
		Path folder = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots"));
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().startsWith("diorama_") && p.toString().endsWith(".png"))
				.sorted(Comparator.naturalOrder()).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The PNG is the size the screen said, fully opaque (clouds where nothing is mapped), and not all cloud. */
	private static void checkImage(Path file, ImageExport.Area area) {
		BufferedImage image = read(file);
		if (image == null || image.getWidth() != area.width() || image.getHeight() != area.height()) {
			throw new AssertionError(file.getFileName() + " is " + (image == null ? "unreadable" : image.getWidth() + "x" + image.getHeight())
				+ ", but the screen said " + area.width() + "x" + area.height());
		}
		int seeThrough = 0;
		Set<Integer> colours = new HashSet<>();
		for (int y = 0; y < image.getHeight(); y += 3) {
			for (int x = 0; x < image.getWidth(); x += 3) {
				int argb = image.getRGB(x, y);
				if ((argb >>> 24) != 0xFF) {
					seeThrough++;
				}
				colours.add(argb & 0xF0F0F0);
			}
		}
		if (seeThrough > 0) {
			throw new AssertionError(file.getFileName() + " has " + seeThrough + " see-through pixels; unmapped ground should be clouds");
		}
		if (colours.size() < 200) {
			throw new AssertionError(file.getFileName() + " has only " + colours.size() + " colours: no map in it?");
		}
		System.out.println("IMAGE-EXPORT " + file.getFileName() + " " + image.getWidth() + "x" + image.getHeight() + " " + colours.size() + " colours");
	}

	/**
	 * Around the player, the image shows the saved tiles: their pixels, found here from the map
	 * coordinates alone, match the image's. The fog leaves only a faint veil over mapped ground.
	 */
	private static void matchesTiles(ClientGameTestContext context, Path file, ImageExport.Area area) {
		BufferedImage image = read(file);
		int[] diff = context.computeOnClient(mc -> {
			double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
			long gx = (long) Math.floor(mc.player.getX() * perUnit);
			long gy = (long) Math.floor(Projection.v(mc.player.getY(), mc.player.getZ()) * perUnit);
			TileStore store = MapSession.get().map().savedMap();
			int edge = Projection.TILE_PIXELS;
			int worst = 0;
			int compared = 0;
			for (long y = gy - 6; y <= gy + 6; y += 2) {
				for (long x = gx - 6; x <= gx + 6; x += 2) {
					int[] tile = store.read(new TileKey(TileKey.SURFACE, (int) Math.floorDiv(x, edge), (int) Math.floorDiv(y, edge), area.lod()));
					int ix = (int) (x - area.x());
					int iy = (int) (y - area.y());
					if (tile == null || ix < 0 || iy < 0 || ix >= image.getWidth() || iy >= image.getHeight()) {
						continue;
					}
					int abgr = tile[(int) Math.floorMod(y, edge) * edge + (int) Math.floorMod(x, edge)];
					if ((abgr >>> 24) != 0xFF) {
						continue;
					}
					int argb = image.getRGB(ix, iy);
					worst = Math.max(worst, Math.abs((abgr & 0xFF) - ((argb >> 16) & 0xFF)));
					worst = Math.max(worst, Math.abs(((abgr >> 8) & 0xFF) - ((argb >> 8) & 0xFF)));
					worst = Math.max(worst, Math.abs(((abgr >> 16) & 0xFF) - (argb & 0xFF)));
					compared++;
				}
			}
			return new int[] {worst, compared};
		});
		if (diff[1] < 20) {
			throw new AssertionError(file.getFileName() + ": only " + diff[1] + " mapped pixels around the player to compare");
		}
		if (diff[0] > 16) {
			throw new AssertionError(file.getFileName() + " doesn't show the saved map around the player: off by up to " + diff[0] + "/255");
		}
	}

	/** Whole map's box holds the middle of every chunk the map has near the player, on its ground. */
	private static void coversMappedChunks(ClientGameTestContext context, ImageExport.Area whole) {
		String missing = context.computeOnClient(mc -> {
			double[] units = whole.units();
			int cx0 = mc.player.getBlockX() >> 4;
			int cz0 = mc.player.getBlockZ() >> 4;
			for (int cz = cz0 - 12; cz <= cz0 + 12; cz++) {
				for (int cx = cx0 - 12; cx <= cx0 + 12; cx++) {
					if (MapSession.get().map().chunk(cx, cz) == null) {
						continue;
					}
					int x = cx * 16 + 8;
					int z = cz * 16 + 8;
					double v = Projection.v(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
					if (x < units[0] || x > units[2] || v < units[1] || v > units[3]) {
						return "chunk " + cx + "," + cz;
					}
				}
			}
			return null;
		});
		if (missing != null) {
			throw new AssertionError("Whole map " + whole + " leaves out the mapped " + missing);
		}
	}

	/** Whether the image is the line's colour where the line crosses the player, found from map units alone. */
	private static boolean lineAt(Path file, ImageExport.Area area, double[] at) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int x = (int) Math.floor(at[0] * perUnit - area.x());
		int y = (int) Math.floor(at[1] * perUnit - area.y());
		int rgb = image.getRGB(x, y) & 0xFFFFFF;
		return close(rgb, LINE, 24);
	}

	/** Pixels near a point about a colour, and how wide a span they cover: {count, width}. */
	private static int[] spanNear(Path file, ImageExport.Area area, double u, double v, int colour) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int cx = (int) Math.floor(u * perUnit - area.x());
		int cy = (int) Math.floor(v * perUnit - area.y());
		int count = 0;
		int left = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		for (int y = cy - 16; y <= cy + 16; y++) {
			for (int x = cx - 30; x <= cx + 30; x++) {
				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && close(image.getRGB(x, y) & 0xFFFFFF, colour, 12)) {
					count++;
					left = Math.min(left, x);
					right = Math.max(right, x);
				}
			}
		}
		return new int[] {count, count == 0 ? 0 : right - left + 1};
	}

	/** How many pixels near a point are about a colour: a note's text, or its shadow. */
	private static int countNear(Path file, ImageExport.Area area, double u, double v, int colour) {
		BufferedImage image = read(file);
		double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << area.lod());
		int cx = (int) Math.floor(u * perUnit - area.x());
		int cy = (int) Math.floor(v * perUnit - area.y());
		int count = 0;
		for (int y = cy - 16; y <= cy + 16; y++) {
			for (int x = cx - 24; x <= cx + 24; x++) {
				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && close(image.getRGB(x, y) & 0xFFFFFF, colour, 12)) {
					count++;
				}
			}
		}
		return count;
	}

	private static boolean close(int rgb, int want, int within) {
		return Math.abs((rgb >> 16 & 0xFF) - (want >> 16 & 0xFF)) <= within && Math.abs((rgb >> 8 & 0xFF) - (want >> 8 & 0xFF)) <= within
			&& Math.abs((rgb & 0xFF) - (want & 0xFF)) <= within;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static double[] box(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).boxOnScreen());
	}

	private static double[] centre(Button button) {
		return new double[] {button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0};
	}

	/** Clicks a point given in GUI pixels. */
	private static void click(ClientGameTestContext context, java.util.function.Function<net.minecraft.client.Minecraft, double[]> where, boolean right) {
		double[] p = context.computeOnClient(mc -> {
			double[] gui = where.apply(mc);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {gui[0] * scale, gui[1] * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(right ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Drags from a point by an offset, all in GUI pixels, in a few steps so the screen sees the moves. */
	private static void drag(ClientGameTestContext context, double x, double y, double dx, double dy) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTicks(2);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 0; i < 5; i++) {
			context.getInput().moveCursor(dx * scale / 5, dy * scale / 5);
			context.waitTicks(1);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void settle(ClientGameTestContext context) {
		context.waitTicks(40);
		int quiet = 0;
		for (int i = 0; i < 1200 && quiet < 30; i++) {
			context.waitTick();
			boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
			quiet = baking ? 0 : quiet + 1;
		}
	}
}
