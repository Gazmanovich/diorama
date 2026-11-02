package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapViewport;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	/**
	 * Median difference per colour channel, out of 255, once the light veil the fog leaves over
	 * mapped ground is taken out. The shader leaves about 1.0 on boat.dev's software renderer, and the GPU's own filter 1.4 and 2.0 at the two sizes this compares.
	 */
	private static final double MOST_ERROR = Double.parseDouble(System.getProperty("diorama.tileFilterMostError", "1.25"));

	private TileFilterTest() {
	}

	static void run(ClientGameTestContext context) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		boolean radar = context.computeOnClient(mc -> Settings.get().radarOnMap);
		context.runOnClient(mc -> Settings.get().radarOnMap = false);
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
			System.out.printf("TILE-FILTER span %.1f: median error %.3f over %.0f pixels%n", span, result[0], result[1]);
			if (result[1] < 20_000) {
				throw new AssertionError("Only " + result[1] + " pixels of known ground to compare");
			}
			if (result[0] > MOST_ERROR) {
				throw new AssertionError("Tile pixels aren't averaged exactly at " + span + " per screen pixel: median error " + result[0]);
			}
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> Settings.get().radarOnMap = radar);
		DioramaClientTest.setGuiScale(context, scale);
	}

	/**
	 * The median difference from the exact average, over a stretch of map beside the player, and the
	 * pixels compared. The frame is a few percent lighter than the tiles under the fog's veil, so each
	 * channel of the average is first scaled and offset to fit the frame as well as it can.
	 */
	private static double[] compare(BufferedImage shot, MapViewport.View view, TileStore store, double zoom) {
		double perScreenPixel = Projection.PIXELS_PER_UNIT / (zoom * GUI_SCALE);
		double originX = view.minU() * Projection.PIXELS_PER_UNIT;
		double originY = view.minV() * Projection.PIXELS_PER_UNIT;
		Map<Long, int[]> tiles = new HashMap<>();
		List<double[]> pairs = new ArrayList<>();
		int middleX = shot.getWidth() / 2;
		int middleY = shot.getHeight() / 2;
		for (int y = middleY - 150; y < middleY + 150; y++) {
			for (int x = middleX - 280; x < middleX - 60; x++) {
				double[] expected = average(originX + x * perScreenPixel, originY + y * perScreenPixel, perScreenPixel, tiles, store);
				if (expected == null) {
					continue;
				}
				int rgb = shot.getRGB(x, y);
				pairs.add(new double[] {expected[0], expected[1], expected[2], rgb & 0xFF, rgb >> 8 & 0xFF, rgb >> 16 & 0xFF});
			}
		}
		double[][] fit = new double[3][];
		for (int c = 0; c < 3; c++) {
			fit[c] = fit(pairs, c);
		}
		List<Double> errors = new ArrayList<>();
		for (double[] pair : pairs) {
			double error = 0;
			for (int c = 0; c < 3; c++) {
				error += Math.abs(pair[c] * fit[c][0] + fit[c][1] - pair[c + 3]);
			}
			errors.add(error / 3);
		}
		errors.sort(null);
		return new double[] {errors.isEmpty() ? 0 : errors.get(errors.size() / 2), errors.size()};
	}

	/** Least squares gain and offset taking channel {@code c} of the averages to the frame's. */
	private static double[] fit(List<double[]> pairs, int c) {
		double n = pairs.size();
		double sx = 0;
		double sy = 0;
		double sxx = 0;
		double sxy = 0;
		for (double[] pair : pairs) {
			sx += pair[c];
			sy += pair[c + 3];
			sxx += pair[c] * pair[c];
			sxy += pair[c] * pair[c + 3];
		}
		double gain = (n * sxy - sx * sy) / Math.max(n * sxx - sx * sx, 1e-9);
		return new double[] {gain, (sy - gain * sx) / n};
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
