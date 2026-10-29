package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.ExportScreen;
import dev.diorama.MapExport;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
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

/**
 * Exporting the map as an image, through the screen as a player does: the Export button, the
 * Area box dragged bigger, panning beside it, a detail button, Save image, then Whole map. Each
 * saved PNG is opened and checked against what the screen said it would be.
 */
final class ImageExportTest {
	private ImageExportTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 1.0, TileKey.SURFACE));
		settle(context);

		// The Export button above the zoom buttons opens the export screen.
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
		double[] outside = {grown[2] + 20, grown[1] + 10};
		drag(context, outside[0], outside[1], -30, 0);
		double[] panned = box(context);
		if (!(Math.abs(panned[0] - (grown[0] - 30)) < 2 && Math.abs((panned[2] - panned[0]) - (grown[2] - grown[0])) < 1)) {
			throw new AssertionError("Dragging beside the box didn't pan the map under it");
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

		// Whole map: everything explored, boxed with clouds round it.
		click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), false);
		context.waitTicks(3);
		click(context, mc -> {
			int[] tab = ((ExportScreen) mc.gui.screen()).tabCenter(1);
			return new double[] {tab[0], tab[1]};
		}, false);
		context.waitFor(mc -> mc.gui.screen() instanceof ExportScreen screen && screen.plannedArea() != null, 600);
		settle(context);
		context.takeScreenshot("image-export-03-whole");
		ImageExport.Area whole = context.computeOnClient(mc -> ((ExportScreen) mc.gui.screen()).plannedArea());
		int explored = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
		// The test world has a few hundred chunks around spawn: the whole map is bigger than one screen.
		if (whole.width() < 256 || whole.height() < 128) {
			throw new AssertionError("Whole map is only " + whole + " with " + explored + " chunks mapped");
		}
		Path wholeFile = save(context);
		checkImage(wholeFile, whole);
		context.setScreen(() -> null);
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
		BufferedImage image;
		try {
			image = ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
