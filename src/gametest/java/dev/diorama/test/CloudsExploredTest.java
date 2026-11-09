package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The clouds never lie over explored land. Patchy exploration, the kind a walk or a server gives
 * (squares of chunks with gaps and holes between them), then the map at four zooms, Fancy and Fast,
 * and the minimap, each shot twice: with the fog and without it. Explored pixels (land drawn, a few
 * pixels from any unexplored ground) must look the same in both.
 */
final class CloudsExploredTest {
	/** Away from spawn, where the warm-up explored everything around. */
	private static final int AWAY = 4000;
	/** Chunks the client loads around the player: each stop explores a square 9 chunks across. */
	private static final int VIEW = 4;
	/** Stops 11 chunks apart: two-chunk gaps between the squares, and a whole square left out here and there. */
	private static final int STEP = 176;
	private static final int[][] STOPS = {
		{0, 0}, {1, 0}, {2, 0}, {3, 0},
		{0, 1}, {3, 1},
		{0, 2}, {1, 2}, {2, 2},
	};
	/** Pixels this close to unexplored ground can be under the edge's soft blend. */
	private static final int EDGE = 6;
	/** A pixel the clouds change by more than this (summed over the channels, out of 765) is covered. */
	private static final int CHANGED = 36;
	/** The most explored pixels the clouds may change: under one in a hundred. */
	private static final double MOST_COVERED = 0.01;
	private static final int BACKDROP = 0x8C99AB;

	private CloudsExploredTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int distance = context.computeOnClient(mc -> mc.options.renderDistance().get());
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		boolean saved = context.computeOnClient(mc -> Settings.get().mapSavedChunks);
		Settings.Quality quality = context.computeOnClient(mc -> Settings.get().quality);
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(VIEW);
			// Chunks filled from the save would close the gaps.
			Settings.get().mapSavedChunks = false;
			Settings.get().apply();
		});
		context.getInput().resizeWindow(1920, 1080);
		DioramaClientTest.setGuiScale(context, 3);
		int[] spawn = context.computeOnClient(mc -> new int[] {mc.player.getBlockX() + AWAY, mc.player.getBlockZ()});
		world.getServer().runCommand("gamemode spectator @a");
		for (int[] stop : STOPS) {
			world.getServer().runCommand("tp @a " + (spawn[0] + stop[0] * STEP) + " 160 " + (spawn[1] + stop[1] * STEP));
			int last = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
			int still = 0;
			for (int i = 0; i < 1200 && still < 60; i++) {
				context.waitTick();
				int now = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
				still = now > last ? 0 : still + 1;
				last = now;
			}
		}
		// The player beside a left-out square, so the minimap shows a hole.
		world.getServer().runCommand("tp @a " + (spawn[0] + STEP) + " 160 " + (spawn[1] + STEP / 2));
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("tick freeze");
		DioramaClientTest.settle(context, 40);

		double cx = spawn[0] + 1.5 * STEP;
		double cz = spawn[1] + STEP;
		StringBuilder failures = new StringBuilder();
		for (Settings.Quality q : new Settings.Quality[] {Settings.Quality.FANCY, Settings.Quality.FAST}) {
			context.runOnClient(mc -> {
				Settings.get().quality = q;
				Settings.get().apply();
			});
			String name = q.name().toLowerCase(Locale.ROOT);
			context.setScreen(MapScreen::new);
			// A gap up close, the whole patch, and further out.
			check(context, failures, name + "-gap", spawn[0] + STEP + 88, spawn[1] + STEP, 300);
			check(context, failures, name + "-patch", cx, cz, 800);
			check(context, failures, name + "-whole", cx, cz, 1600);
			check(context, failures, name + "-far", cx, cz, 4000);
			context.setScreen(() -> null);
			minimap(context, failures, name);
		}

		world.getServer().runCommand("tick unfreeze");
		world.getServer().runCommand("gamemode creative @a");
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(distance);
			Settings.get().mapSavedChunks = saved;
			Settings.get().quality = quality;
			Settings.get().apply();
		});
		DioramaClientTest.setGuiScale(context, scale);
		context.getInput().resizeWindow(1600, 900);
		if (failures.length() > 0) {
			throw new AssertionError("Clouds over explored land:" + failures);
		}
	}

	private static void check(ClientGameTestContext context, StringBuilder failures, String name, double x, double z, double wide) {
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x, Projection.v(70, z), mc.getWindow().getGuiScaledWidth() / wide, TileKey.SURFACE));
		DioramaClientTest.settle(context, 30);
		int[] box = context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			int s = (int) Math.round(mc.getWindow().getGuiScale());
			return new int[] {view.x() * s, view.y() * s, (view.x() + view.width()) * s, (view.y() + view.height()) * s};
		});
		compare(context, failures, "clouds-explored-map-" + name, box);
	}

	private static void minimap(ClientGameTestContext context, StringBuilder failures, String name) {
		// Where the minimap is: what changes when it's switched off.
		context.runOnClient(mc -> Settings.get().minimap = false);
		context.waitTicks(3);
		BufferedImage off = read(context.takeScreenshot("clouds-explored-minimap-" + name + "-off"));
		context.runOnClient(mc -> Settings.get().minimap = true);
		DioramaClientTest.settle(context, 30);
		BufferedImage on = read(context.takeScreenshot("clouds-explored-minimap-" + name + "-probe"));
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0};
		for (int y = 0; y < on.getHeight(); y++) {
			for (int x = 0; x < on.getWidth(); x++) {
				if (difference(on.getRGB(x, y), off.getRGB(x, y)) > CHANGED) {
					box[0] = Math.min(box[0], x);
					box[1] = Math.min(box[1], y);
					box[2] = Math.max(box[2], x + 1);
					box[3] = Math.max(box[3], y + 1);
				}
			}
		}
		if (box[2] == 0) {
			failures.append("\n  ").append(name).append(" minimap: not drawn");
			return;
		}
		compare(context, failures, "clouds-explored-minimap-" + name, box);
	}

	/** The share of explored pixels in the box the fog changes, against the same frame drawn without fog. */
	private static void compare(ClientGameTestContext context, StringBuilder failures, String name, int[] box) {
		Path withPath = context.takeScreenshot(name);
		BufferedImage with = read(withPath);
		context.runOnClient(mc -> MapViewport.hideFogForTests(true));
		context.waitTicks(3);
		BufferedImage without = read(context.takeScreenshot(name + "-nofog"));
		context.runOnClient(mc -> MapViewport.hideFogForTests(false));
		context.waitTicks(3);
		int w = with.getWidth();
		int h = with.getHeight();
		int x0 = Math.max(0, box[0]);
		int y0 = Math.max(0, box[1]);
		int x1 = Math.min(w, box[2]);
		int y1 = Math.min(h, box[3]);
		// Distance, in pixels along rows and columns, to the nearest unexplored pixel in the frame without fog.
		int[] near = new int[w * h];
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				near[y * w + x] = difference(without.getRGB(x, y), BACKDROP) <= 12 ? 0 : EDGE + 1;
			}
		}
		for (int pass = 0; pass < EDGE; pass++) {
			int[] next = near.clone();
			for (int y = y0 + 1; y < y1 - 1; y++) {
				for (int x = x0 + 1; x < x1 - 1; x++) {
					int k = y * w + x;
					int m = Math.min(Math.min(near[k - 1], near[k + 1]), Math.min(near[k - w], near[k + w]));
					next[k] = Math.min(near[k], m + 1);
				}
			}
			near = next;
		}
		long explored = 0;
		long covered = 0;
		BufferedImage marked = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int argb = with.getRGB(x, y);
				int shown = argb & 0xFFFFFF;
				if (y > y0 && y < y1 - 1 && x > x0 && x < x1 - 1 && near[y * w + x] > EDGE) {
					explored++;
					if (difference(argb, without.getRGB(x, y)) > CHANGED) {
						covered++;
						shown = 0xFF00FF;
					}
				}
				marked.setRGB(x - x0, y - y0, shown);
			}
		}
		try {
			ImageIO.write(marked, "png", withPath.resolveSibling(withPath.getFileName().toString().replace(".png", "") + "-covered.png").toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		double share = explored == 0 ? 0 : covered / (double) explored;
		Diorama.LOGGER.info("CLOUDS-EXPLORED {}: {} of {} explored pixels covered ({})", name, covered, explored, String.format(Locale.ROOT, "%.2f%%", share * 100));
		if (explored < 20_000) {
			failures.append("\n  ").append(name).append(": only ").append(explored).append(" explored pixels to check");
		} else if (share > MOST_COVERED) {
			failures.append("\n  ").append(name).append(String.format(Locale.ROOT, ": %.1f%% of explored pixels covered", share * 100));
		}
	}

	private static int difference(int a, int b) {
		return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF));
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
