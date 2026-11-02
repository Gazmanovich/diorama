package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapViewport;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A map shown smaller than its tiles: each screen pixel is the average of the tile pixels under it,
 * read off a real frame. It fails on the GPU's own filter, which is what the tile shader replaces,
 * so it also says the shader runs here, on versions that fall back without it.
 */
final class TileFilterTest {
	private static final int GUI_SCALE = 3;
	/** Mean difference per colour channel, out of 255, left by the fog's veil and rounding. */
	private static final double MOST_ERROR = 1.5;

	private TileFilterTest() {
	}

	static void run(ClientGameTestContext context) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		DioramaClientTest.setGuiScale(context, GUI_SCALE);
		context.setScreen(MapScreen::new);
		// Both read the tile itself, shrunk by little and by more.
		for (double span : new double[] {1.6, 2.7}) {
			double zoom = Projection.PIXELS_PER_UNIT / span / GUI_SCALE;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), zoom, TileKey.SURFACE));
			DioramaClientTest.settle(context, 20);
			MapViewport.View view = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view());
			TileStore store = context.computeOnClient(mc -> MapSession.get().map().savedMap());
			BufferedImage shot = read(context.takeScreenshot(String.format("tile-filter-%.1f", span)));
			double[] result = compare(shot, view, store, zoom);
			System.out.printf("TILE-FILTER span %.1f: mean error %.3f over %.0f pixels%n", span, result[0], result[1]);
			if (result[1] < 20_000) {
				throw new AssertionError("Only " + result[1] + " pixels of known ground to compare");
			}
			if (result[0] > MOST_ERROR) {
				throw new AssertionError("Tile pixels aren't averaged exactly at " + span + " per screen pixel: mean error " + result[0]);
			}
		}
		context.setScreen(() -> null);
		DioramaClientTest.setGuiScale(context, scale);
	}

	/** The mean difference from the exact average, over a stretch of map beside the player, and the pixels compared. */
	private static double[] compare(BufferedImage shot, MapViewport.View view, TileStore store, double zoom) {
		double perScreenPixel = Projection.PIXELS_PER_UNIT / (zoom * GUI_SCALE);
		double originX = view.minU() * Projection.PIXELS_PER_UNIT;
		double originY = view.minV() * Projection.PIXELS_PER_UNIT;
		Map<Long, int[]> tiles = new HashMap<>();
		double sum = 0;
		double compared = 0;
		int middleX = shot.getWidth() / 2;
		int middleY = shot.getHeight() / 2;
		for (int y = middleY - 150; y < middleY + 150; y++) {
			for (int x = middleX - 280; x < middleX - 60; x++) {
				double[] expected = average(originX + x * perScreenPixel, originY + y * perScreenPixel, perScreenPixel, tiles, store);
				if (expected == null) {
					continue;
				}
				int rgb = shot.getRGB(x, y);
				sum += Math.abs(expected[0] - (rgb & 0xFF)) + Math.abs(expected[1] - (rgb >> 8 & 0xFF)) + Math.abs(expected[2] - (rgb >> 16 & 0xFF));
				compared++;
			}
		}
		return new double[] {sum / 3 / Math.max(compared, 1), compared};
	}

	/** The exact average, blue first as a tile pixel holds it, or null unless it's all known ground. */
	private static double[] average(double x0, double y0, double span, Map<Long, int[]> tiles, TileStore store) {
		int size = Projection.TILE_PIXELS;
		double[] sum = new double[3];
		for (long py = (long) Math.floor(y0); py < y0 + span; py++) {
			double height = Math.min(py + 1, y0 + span) - Math.max(py, y0);
			for (long px = (long) Math.floor(x0); px < x0 + span; px++) {
				double width = Math.min(px + 1, x0 + span) - Math.max(px, x0);
				int tu = (int) Math.floorDiv(px, size);
				int tv = (int) Math.floorDiv(py, size);
				int[] tile = tiles.computeIfAbsent((long) tu << 32 | tv & 0xFFFFFFFFL, key -> {
					int[] saved = store.read(new TileKey(TileKey.SURFACE, tu, tv));
					return saved == null ? new int[0] : saved;
				});
				if (tile.length == 0) {
					return null;
				}
				int pixel = tile[(int) Math.floorMod(py, size) * size + (int) Math.floorMod(px, size)];
				if (pixel >>> 24 != 0xFF) {
					return null;
				}
				double weight = width * height / (span * span);
				sum[2] += (pixel & 0xFF) * weight;
				sum[1] += (pixel >> 8 & 0xFF) * weight;
				sum[0] += (pixel >> 16 & 0xFF) * weight;
			}
		}
		return sum;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
