package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Settings → Look → Fog look, switched while the map and the minimap are open: the soft fog
 * replaces the clouds over unexplored land, and switching back brings the clouds back as they were.
 */
final class FogLookTest {
	/** Pixels the soft fog must change: tens of thousands on boat, even where only a strip of the minimap is unexplored. */
	private static final int LEAST_CHANGED = 1000;
	/** Of those, the most that may still differ once the clouds are back. */
	private static final double MOST_LEFT = 0.01;
	/** Mean difference per channel, out of 255, between two shots of a view that has stopped changing. */
	private static final double STILL = 0.1;

	private FogLookTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		Settings.FogLook look = context.computeOnClient(mc -> Settings.get().fogLook);
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("tick freeze");
		// Nothing new on the map between the shots: a section before this one may have changed the
		// render distance, and the chunks it brings keep landing for a while.
		int last = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
		for (int i = 0, still = 0; i < 2400 && still < 100; i++) {
			context.waitTick();
			int now = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
			still = now == last ? still + 1 : 0;
			last = now;
		}
		DioramaClientTest.settle(context, 20);
		// The explored square around the player with unexplored land all round it.
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()),
			mc.getWindow().getGuiScaledWidth() / 900.0, TileKey.SURFACE));
		StringBuilder failures = new StringBuilder();
		check(context, failures, "map");
		context.setScreen(() -> null);
		// The minimap zoomed all the way out, so it reaches past the explored land.
		double zoom = context.computeOnClient(mc -> Settings.get().minimapPixelsPerBlock);
		context.runOnClient(mc -> Settings.get().zoomStep(0));
		check(context, failures, "minimap");
		context.runOnClient(mc -> {
			Settings.get().minimapPixelsPerBlock = zoom;
			Settings.get().fogLook = look;
		});
		world.getServer().runCommand("tick unfreeze");
		if (failures.length() > 0) {
			throw new AssertionError("Fog look:" + failures);
		}
	}

	private static void check(ClientGameTestContext context, StringBuilder failures, String where) {
		// The clouds once the view has stopped changing: tiles at its edge can still be loading in
		// after the chunk count settles.
		BufferedImage clouds = shot(context, Settings.FogLook.CLOUDS, "fog-look-" + where + "-clouds");
		for (int i = 0; i < 10; i++) {
			context.waitTicks(20);
			BufferedImage next = shot(context, Settings.FogLook.CLOUDS, "fog-look-" + where + "-clouds");
			boolean same = difference(clouds, next, new int[] {0, 0, clouds.getWidth(), clouds.getHeight()}) <= STILL;
			clouds = next;
			if (same) {
				break;
			}
		}
		BufferedImage soft = shot(context, Settings.FogLook.SOFT, "fog-look-" + where + "-soft");
		BufferedImage back = shot(context, Settings.FogLook.CLOUDS, "fog-look-" + where + "-clouds-again");
		int changed = changedPixels(clouds, soft);
		int left = changedPixels(clouds, back);
		Diorama.LOGGER.info("FOG-LOOK {}: soft fog changed {} pixels, {} still differ once the clouds are back", where, changed, left);
		if (changed < LEAST_CHANGED) {
			failures.append("\n  ").append(where).append(": the soft fog looks like the clouds (").append(changed).append(" pixels changed)");
		}
		if (left > MOST_LEFT * changed) {
			failures.append("\n  ").append(where).append(": switched back, the clouds aren't as they were (").append(left).append(" pixels differ)");
		}
	}

	private static BufferedImage shot(ClientGameTestContext context, Settings.FogLook look, String name) {
		context.runOnClient(mc -> Settings.get().fogLook = look);
		DioramaClientTest.settle(context, 10);
		return read(context.takeScreenshot(name));
	}

	/** Pixels that differ by more than a little. */
	private static int changedPixels(BufferedImage a, BufferedImage b) {
		int n = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (pixelDifference(a.getRGB(x, y), b.getRGB(x, y)) > 24) {
					n++;
				}
			}
		}
		return n;
	}

	/** Mean absolute difference per channel over the box, out of 255. */
	private static double difference(BufferedImage a, BufferedImage b, int[] box) {
		long sum = 0;
		for (int y = box[1]; y < box[3]; y++) {
			for (int x = box[0]; x < box[2]; x++) {
				sum += pixelDifference(a.getRGB(x, y), b.getRGB(x, y));
			}
		}
		return sum / (3.0 * (box[2] - box[0]) * (box[3] - box[1]));
	}

	private static int pixelDifference(int p, int q) {
		return Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
