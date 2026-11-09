package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TableLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VanillaTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;

/**
 * Makes the vanilla colour table the jar ships ({@link VanillaTable}) from this version's game,
 * with no resource pack, and checks the jar's copy still matches it. Writes the table to
 * {@code screenshots/vanilla-table/vanilla_looks.dat}: when the check fails, that file goes in
 * {@code src/main/resources/diorama/vanilla/<minecraft_version>.dat}.
 *
 * <p>Also bakes the tiles around the player with the client's own looks and with looks from the
 * table, which must come out the same to the pixel, and measures how far the shrunk colour maps
 * stray from the game's.
 */
final class VanillaTableTest {
	private VanillaTableTest() {
	}

	static void run(ClientGameTestContext context) {
		Path out = Path.of("screenshots", "vanilla-table");
		VanillaTable made = context.computeOnClient(mc -> VanillaTable.of(BlockLooks.get()));
		byte[] bytes;
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			made.write(buffer);
			bytes = buffer.toByteArray();
			Files.createDirectories(out);
			Files.write(out.resolve("vanilla_looks.dat"), bytes);
			// What a reader gets back is what was written.
			List<String> roundTrip = VanillaTable.read(new ByteArrayInputStream(bytes)).differences(made, 5);
			if (!roundTrip.isEmpty()) {
				throw new AssertionError("The table reads back differently: " + roundTrip);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Diorama.LOGGER.info("Vanilla colour table: {}, {} bytes", made.describe(), bytes.length);

		// The colour maps a server gets from the table, against the game's own.
		int[] worst = new int[3];
		double[] mean = new double[3];
		for (int which = 0; which < 3; which++) {
			int[] map = made.colourMap(which);
			if (map == null) {
				continue;
			}
			long sum = 0;
			int n = 0;
			for (int t = 0; t <= 100; t++) {
				for (int r = 0; r <= 100; r++) {
					double temp = t / 100.0;
					double rain = r / 100.0;
					int game = which == VanillaTable.GRASS ? GrassColor.get(temp, rain) : which == VanillaTable.FOLIAGE ? FoliageColor.get(temp, rain) : dryFoliage(temp, rain);
					int x = (int) ((1.0 - temp) * 255.0);
					int y = (int) ((1.0 - rain * temp) * 255.0);
					int ours = map[y << 8 | x];
					int d = 0;
					for (int shift = 0; shift <= 16; shift += 8) {
						d = Math.max(d, Math.abs((game >> shift & 0xFF) - (ours >> shift & 0xFF)));
					}
					worst[which] = Math.max(worst[which], d);
					sum += d;
					n++;
				}
			}
			mean[which] = (double) sum / n;
		}
		Diorama.LOGGER.info(String.format(Locale.ROOT, "Colour maps from the table vs the game's, worst/mean channel difference out of 255: grass %d/%.2f, foliage %d/%.2f, dry foliage %d/%.2f",
			worst[0], mean[0], worst[1], mean[1], worst[2], mean[2]));
		for (int which = 0; which < 3; which++) {
			if (worst[which] > 6) {
				throw new AssertionError("Colour map " + which + " from the table is " + worst[which] + "/255 off the game's");
			}
		}

		// A bake with the table's looks against one with the client's: the same, pixel for pixel.
		TableLooks table = new TableLooks(made);
		int[] at = context.computeOnClient(mc -> new int[] {
			(int) Math.floor(mc.player.getX() / Projection.TILE_UNITS),
			(int) Math.floor(Projection.v(mc.player.getY(), mc.player.getZ()) / Projection.TILE_UNITS)});
		int tiles = 0;
		int drawn = 0;
		for (int dv = -1; dv <= 1; dv++) {
			for (int du = -1; du <= 1; du++) {
				TileKey key = new TileKey(TileKey.SURFACE, at[0] + du, at[1] + dv);
				int[] client = TileBaker.reference(key, MapSession.get().map(), BlockLooks.get(), false, null, null);
				int[] fromTable = TileBaker.reference(key, MapSession.get().map(), table, false, null, null);
				int differ = 0;
				int known = 0;
				for (int i = 0; i < client.length; i++) {
					if (client[i] != fromTable[i]) {
						differ++;
					}
					if (client[i] >>> 24 != 0) {
						known++;
					}
				}
				Diorama.LOGGER.info("Tile {}: {} drawn pixels, {} differ between the client's looks and the table's", key, known, differ);
				if (differ != 0) {
					writeTile(out.resolve("client-" + tiles + ".png"), client);
					writeTile(out.resolve("table-" + tiles + ".png"), fromTable);
					throw new AssertionError("Tile " + key + ": " + differ + " pixels differ between the client's looks and the table's");
				}
				if (du == 0 && dv == 0) {
					writeTile(out.resolve("table-" + tiles + ".png"), fromTable);
				}
				drawn += known;
				tiles++;
			}
		}
		if (drawn < Projection.TILE_PIXELS * Projection.TILE_PIXELS) {
			throw new AssertionError("Only " + drawn + " drawn pixels in " + tiles + " tiles to compare");
		}

		VanillaTable shipped = VanillaTable.shipped();
		if (shipped == null) {
			throw new AssertionError("This jar ships no vanilla colour table: copy " + out.resolve("vanilla_looks.dat") + " into src/main/resources/diorama/vanilla/");
		}
		List<String> differences = shipped.differences(made, 20);
		if (!differences.isEmpty()) {
			throw new AssertionError("The jar's vanilla colour table doesn't match the game (" + String.join("; ", differences)
				+ "): copy " + out.resolve("vanilla_looks.dat") + " into src/main/resources/diorama/vanilla/");
		}
	}

	private static int dryFoliage(double temp, double rain) {
		//? if >=1.21.5 {
		return net.minecraft.world.level.DryFoliageColor.get(temp, rain);
		//?} else {
		/*return 0;
		*///?}
	}

	private static void writeTile(Path file, int[] px) {
		int size = Projection.TILE_PIXELS;
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < px.length; i++) {
			int p = px[i];
			img.setRGB(i % size, i / size, (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF));
		}
		try {
			javax.imageio.ImageIO.write(img, "png", file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
