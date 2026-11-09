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
	/** Mean difference per channel, out of 255, over the frame: the two looks differ by far more, a redraw of one look by far less. */
	private static final double DIFFERENT = 3;
	private static final double SAME = 1;

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
			boolean same = difference(clouds, next, new int[] {0, 0, clouds.getWidth(), clouds.getHeight()}) <= SAME / 10;
			clouds = next;
			if (same) {
				break;
			}
		}
		BufferedImage soft = shot(context, Settings.FogLook.SOFT, "fog-look-" + where + "-soft");
		BufferedImage back = shot(context, Settings.FogLook.CLOUDS, "fog-look-" + where + "-clouds-again");
		// Over what the look changes: the map's unexplored land, or the minimap.
		int[] box = changedBox(clouds, soft);
		double changed = difference(clouds, soft, box);
		double again = difference(clouds, back, box);
		Diorama.LOGGER.info("FOG-LOOK {}: clouds to soft fog {}, back to clouds {}", where, String.format(Locale.ROOT, "%.2f", changed), String.format(Locale.ROOT, "%.2f", again));
		if (changed < DIFFERENT) {
			failures.append("\n  ").append(where).append(": the soft fog looks like the clouds (").append(String.format(Locale.ROOT, "%.2f", changed)).append(")");
		}
		if (again > SAME) {
			failures.append("\n  ").append(where).append(": switched back, the clouds aren't as they were (").append(String.format(Locale.ROOT, "%.2f", again)).append(")");
		}
	}

	private static BufferedImage shot(ClientGameTestContext context, Settings.FogLook look, String name) {
		context.runOnClient(mc -> Settings.get().fogLook = look);
		DioramaClientTest.settle(context, 10);
		return read(context.takeScreenshot(name));
	}

	/** The box round every pixel that differs by more than a little, or the whole frame if none does. */
	private static int[] changedBox(BufferedImage a, BufferedImage b) {
		int[] box = {a.getWidth(), a.getHeight(), 0, 0};
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (pixelDifference(a.getRGB(x, y), b.getRGB(x, y)) > 24) {
					box[0] = Math.min(box[0], x);
					box[1] = Math.min(box[1], y);
					box[2] = Math.max(box[2], x + 1);
					box[3] = Math.max(box[3], y + 1);
				}
			}
		}
		return box[2] == 0 ? new int[] {0, 0, a.getWidth(), a.getHeight()} : box;
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
