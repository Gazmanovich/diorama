package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
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
 * A map shown smaller than its tiles: each screen pixel is the average of the area of the tile
 * under it, read off a real frame and compared with the same average worked out from the saved
 * tiles. It fails on the GPU's own filter, which is what the tile shader replaces, so it also says
 * the shader runs here, on versions that fall back without it.
 */
final class TileFilterTest {
	/**
	 * A view to check: the GUI scale and zoom, the overview level it should draw, the mip level of
	 * that the shader should read, and the most median difference per colour channel, out of 255,
	 * once the light veil the fog leaves over mapped ground is taken out.
	 */
	private record Case(String name, int guiScale, double zoom, int lod, int mip, double mostError) {
	}

	/**
	 * Each limit sits about halfway between what the shader leaves and what the GPU's own filter does,
	 * on boat.dev's software renderer: 1.0 to 1.25 against 1.4, 1.9, 2.8 and 2.1.
	 */
	private static final List<Case> CASES = List.of(
		// The tile itself, shrunk by little and by more.
		new Case("detailed-1.6", 3, 4 / 1.6 / 3, 0, 0, 1.2),
		new Case("detailed-2.7", 3, 4 / 2.7 / 3, 0, 0, 1.6),
		// Shrunk eightfold, read from mip level 2.
		new Case("detailed-mip2", 1, 0.5, 0, 2, 1.9),
		// Far enough out for overviews: one level finer than the coarsest that would do, as it fits.
		new Case("overview", 3, 0.8 / 3, 1, 0, 1.55)
	);

	private TileFilterTest() {
	}

	static void run(ClientGameTestContext context) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		boolean radar = context.computeOnClient(mc -> Settings.get().radarOnMap);
		context.runOnClient(mc -> Settings.get().radarOnMap = false);
		TileStore store = context.computeOnClient(mc -> MapSession.get().map().savedMap());
		// Overview tiles are rebuilt a few seconds after the tiles under them: bring them up to now.
		try {
			store.catchUp();
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
		for (Case check : CASES) {
			DioramaClientTest.setGuiScale(context, check.guiScale);
			context.setScreen(MapScreen::new);
			// The clouds reach into explored land as far as they're see-through (Mapped). Where the
			// player stands depends on the sections before, near the edge of what's explored or not:
			// look at the player or that far off it each way, whichever has the most ground to compare.
			double reach = dev.diorama.render.VanillaClouds.reachInto(dev.diorama.render.VanillaClouds.drawnCell(1 / (check.zoom * check.guiScale)));
			double[][] offsets = {{0, 0}, {-reach, 0}, {reach, 0}, {0, -reach}, {0, reach}};
			double[] best = offsets[0];
			int most = -1;
			for (double[] offset : offsets) {
				lookNear(context, offset, check.zoom);
				DioramaClientTest.settle(context, 40);
				MapViewport.View candidate = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view());
				Mapped around = context.computeOnClient(mc -> Mapped.around(MapSession.get().map(), candidate, check.lod, check.guiScale));
				int[] screen = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
				int count = comparable(candidate, check, around, screen[0], screen[1]);
				if (count > most) {
					most = count;
					best = offset;
				}
			}
			lookNear(context, best, check.zoom);
			DioramaClientTest.settle(context, 40);
			MapViewport.View view = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view());
			int lod = context.computeOnClient(mc -> MapViewport.lodFor(MapSession.get().map(), view));
			if (lod != check.lod) {
				throw new AssertionError(check.name + " draws overview level " + lod + ", not " + check.lod);
			}
			BufferedImage shot = read(context.takeScreenshot("tile-filter-" + check.name));
			Mapped mapped = context.computeOnClient(mc -> Mapped.around(MapSession.get().map(), view, check.lod, check.guiScale));
			double[] result = compare(shot, view, store, check, mapped);
			System.out.printf("TILE-FILTER %s: median error %.3f over %.0f pixels, colour scale %.3f%n", check.name, result[0], result[1], result[2]);
			// The shrunk-eightfold case compares a strip of about 8,000 screen pixels, of which only
			// those with mapped ground all round, past the see-through clouds, count: some 2,500 near
			// the spawn's edge. Enough for a median either way.
			if (result[1] < 2_000) {
				throw new AssertionError(check.name + ": only " + result[1] + " pixels of known ground to compare");
			}
			if (result[2] < 0.9 || result[2] > 1.15) {
				throw new AssertionError(check.name + ": the frame's colours scale to the tiles' by " + result[2] + ", not about 1: is the map drawn at all?");
			}
			if (result[0] > check.mostError) {
				throw new AssertionError(check.name + ": tile pixels aren't averaged as they should be: median error " + result[0]);
			}
			context.setScreen(() -> null);
		}
		context.runOnClient(mc -> Settings.get().radarOnMap = radar);
		DioramaClientTest.setGuiScale(context, scale);
	}

	/**
	 * The median difference from the average, over a stretch of map just west of the player, the
	 * pixels compared, and the smallest scale fitted to a colour channel. The frame is a few percent
	 * lighter than the tiles under the fog's veil, so each channel of the average is first scaled and
	 * offset to fit the frame as well as it can.
	 */
	private static void lookNear(ClientGameTestContext context, double[] offset, double zoom) {
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX() + offset[0], Projection.v(mc.player.getY(), mc.player.getZ() + offset[1]), zoom, TileKey.SURFACE));
	}

	/** How many pixels of the strip {@link #compare} reads have mapped ground all around, every fourth each way. */
	private static int comparable(MapViewport.View view, Case check, Mapped mapped, int screenWidth, int screenHeight) {
		double perUnit = (double) Projection.TILE_PIXELS / (Projection.TILE_UNITS << check.lod) / (1 << check.mip);
		double perScreenPixel = perUnit / (view.zoom() * check.guiScale);
		int[] strip = strip(view, check, screenWidth, screenHeight);
		int count = 0;
		for (int y = strip[2]; y < strip[3]; y += 4) {
			for (int x = strip[0]; x < strip[1]; x += 4) {
				if (mapped.allAround(view.minU() + x * perScreenPixel / perUnit, view.minV() + y * perScreenPixel / perUnit)) {
					count++;
				}
			}
		}
		return count;
	}

	/** The screen pixels compared: {first x, last x + 1, first y, last y + 1}. */
	private static int[] strip(MapViewport.View view, Case check, int screenWidth, int screenHeight) {
		int middleX = screenWidth / 2;
		int middleY = screenHeight / 2;
		int reach = (int) Math.min(280, 170 * view.zoom() * check.guiScale);
		return new int[] {middleX - reach, middleX - 12, middleY - reach * 2 / 3, middleY + reach * 2 / 3};
	}

	private static double[] compare(BufferedImage shot, MapViewport.View view, TileStore store, Case check, Mapped mapped) {
		// Pixels of the mip level read, per map unit.
		double perUnit = (double) Projection.TILE_PIXELS / (Projection.TILE_UNITS << check.lod) / (1 << check.mip);
		double perScreenPixel = perUnit / (view.zoom() * check.guiScale);
		double originX = view.minU() * perUnit;
		double originY = view.minV() * perUnit;
		Map<Long, int[]> tiles = new HashMap<>();
		List<double[]> pairs = new ArrayList<>();
		// Within about 170 blocks of the middle, left of it.
		int[] strip = strip(view, check, shot.getWidth(), shot.getHeight());
		for (int y = strip[2]; y < strip[3]; y++) {
			for (int x = strip[0]; x < strip[1]; x++) {
				if (!mapped.allAround(view.minU() + x * perScreenPixel / perUnit, view.minV() + y * perScreenPixel / perUnit)) {
					continue;
				}
				double[] expected = average(originX + x * perScreenPixel, originY + y * perScreenPixel, perScreenPixel, tiles, store, check);
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
		double scale = Math.min(fit[0][0], Math.min(fit[1][0], fit[2][0]));
		return new double[] {errors.isEmpty() ? 0 : errors.get(errors.size() / 2), errors.size(), scale};
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

	/**
	 * The average over a square of the case's mip level, blue first as a tile pixel holds it, or
	 * null unless it's all known ground.
	 */
	private static double[] average(double x0, double y0, double span, Map<Long, int[]> tiles, TileStore store, Case check) {
		int size = Projection.TILE_PIXELS >> check.mip;
		double[] sum = new double[3];
		for (long py = (long) Math.floor(y0); py < y0 + span; py++) {
			double height = Math.min(py + 1, y0 + span) - Math.max(py, y0);
			for (long px = (long) Math.floor(x0); px < x0 + span; px++) {
				double width = Math.min(px + 1, x0 + span) - Math.max(px, x0);
				int tu = (int) Math.floorDiv(px, size);
				int tv = (int) Math.floorDiv(py, size);
				int[] tile = tiles.computeIfAbsent((long) tu << 32 | tv & 0xFFFFFFFFL, key -> {
					int[] saved = store.read(new TileKey(TileKey.SURFACE, tu, tv, check.lod));
					return saved == null ? new int[0] : Mipmaps.build(saved, Projection.TILE_PIXELS)[check.mip];
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

	/**
	 * Which coverage cells around the view the live map has fully mapped. The see-through clouds,
	 * their shadows and their lean reach onto mapped ground near its edge, which is the fog's
	 * business, not the filter's: a pixel is compared only with mapped ground all around it as far
	 * as the clouds the view draws can reach (VanillaClouds.reachInto), further to the south, which
	 * the clouds lean over by up to a cell.
	 */
	private record Mapped(double u0, double v0, double cell, int width, int height, int[] unmappedSums, double around, double south) {
		/** From the tiles of overview level {@code lod}, the ones the view draws and so the map holds. */
		static Mapped around(MapLevel map, MapViewport.View view, int lod, int guiScale) {
			double drawn = dev.diorama.render.VanillaClouds.drawnCell(1 / (view.zoom() * guiScale));
			double around = dev.diorama.render.VanillaClouds.reachInto(drawn);
			double south = around + 1.2 * drawn;
			double tileUnits = (double) Projection.TILE_UNITS * (1 << lod);
			double cell = tileUnits / Projection.COVERAGE_CELLS;
			double u0 = Math.floor((view.minU() - around) / cell) * cell;
			double v0 = Math.floor((view.minV() - around) / cell) * cell;
			int width = (int) Math.ceil((view.width() / view.zoom() + 2 * around) / cell) + 1;
			int height = (int) Math.ceil((view.height() / view.zoom() + around + south) / cell) + 1;
			int[] sums = new int[(width + 1) * (height + 1)];
			for (int j = 0; j < height; j++) {
				int line = 0;
				for (int i = 0; i < width; i++) {
					double u = u0 + (i + 0.5) * cell;
					double v = v0 + (j + 0.5) * cell;
					int tu = (int) Math.floor(u / tileUnits);
					int tv = (int) Math.floor(v / tileUnits);
					MapTile tile = map.peek(new TileKey(TileKey.SURFACE, tu, tv, lod));
					boolean full = tile != null && tile.hasCoverage()
						&& tile.coverage((int) Math.floor((u - tu * tileUnits) / cell), (int) Math.floor((v - tv * tileUnits) / cell)) >= 0.999F;
					line += full ? 0 : 1;
					sums[(j + 1) * (width + 1) + i + 1] = sums[j * (width + 1) + i + 1] + line;
				}
			}
			return new Mapped(u0, v0, cell, width, height, sums, around, south);
		}

		boolean allAround(double u, double v) {
			int i0 = (int) Math.floor((u - this.around - this.u0) / this.cell);
			int i1 = (int) Math.floor((u + this.around - this.u0) / this.cell) + 1;
			int j0 = (int) Math.floor((v - this.around - this.v0) / this.cell);
			int j1 = (int) Math.floor((v + this.south - this.v0) / this.cell) + 1;
			if (i0 < 0 || j0 < 0 || i1 > this.width || j1 > this.height) {
				return false;
			}
			int row = this.width + 1;
			int[] s = this.unmappedSums;
			return s[j1 * row + i1] - s[j0 * row + i1] - s[j1 * row + i0] + s[j0 * row + i0] == 0;
		}
	}

}
