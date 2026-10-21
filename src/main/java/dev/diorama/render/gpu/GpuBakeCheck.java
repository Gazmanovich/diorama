package dev.diorama.render.gpu;

import dev.diorama.Diorama;
import dev.diorama.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Whether a bake goes to the GPU. The player turns GPU baking on in the settings
 * ({@code DIORAMA_GPU_BAKE=1} forces it on for tests). Before the map shows any GPU tile, the
 * first few are also traced and the two compared: a graphics driver that draws them differently
 * turns GPU baking off for the rest of the session, so it can't spoil a map.
 *
 * <p>Sky views and lit views (cave slices, the Nether under its roof) are checked apart: only
 * sky views draw sun shadows and sky occlusion, so matching caves say nothing about them.
 */
public final class GpuBakeCheck {
	/** How a bake runs. */
	public enum Mode {
		/** On the CPU, as without GPU baking. */
		TRACE,
		/** On the GPU, after the check passed. */
		GPU,
		/** On the GPU and the CPU both; the map gets the traced one. */
		CHECK
	}

	private static final String FORCED = System.getenv("DIORAMA_GPU_BAKE");
	/** Tiles compared per round, each with enough known pixels to say something. */
	private static final int TILES = 4;
	/** Rounds that must fail before the verdict does: chunks still loading can spoil one. */
	private static final int ROUNDS = 2;
	/** Bakes the GPU couldn't draw at all before giving up on it. */
	private static final int FAILURES = 3;
	/**
	 * Mean difference, over pixels both bakes know, and how much brighter or darker the GPU's
	 * are on average, past which they don't match: for the round's tiles together, and looser for
	 * any one of them. Tile by tile, matching drivers measured means up to 2.9 (bright snow is the
	 * worst) and biases up to 1.6; a driver that lost the sky occlusion (an integer texture read
	 * as zeros) means from 3.4 and biases from 2.8, all of it brighter. The bias tells them apart
	 * best; the round's mean is as loose as the comparison test's.
	 */
	private static final double MAX_MEAN = 4;
	private static final double MAX_BIAS = 2;
	private static final double TILE_MAX_MEAN = 5;
	private static final double TILE_MAX_BIAS = 2.5;

	private static final Object LOCK = new Object();
	private static volatile boolean failed;
	private static final Check SKY = new Check("sky view");
	private static final Check LIT = new Check("lit view");

	private GpuBakeCheck() {
	}

	/** One kind of view's check. Guarded by {@link #LOCK}. */
	private static final class Check {
		final String name;
		volatile boolean passed;
		int round = 1;
		int out;
		int compared;
		int failures;
		long both;
		long mismatched;
		long pixels;
		double sum;
		double bias;
		/** A tile of this round past the per-tile limits. */
		String worstTile;

		Check(String name) {
			this.name = name;
		}

		void nextRound() {
			this.round++;
			this.compared = 0;
			this.both = 0;
			this.mismatched = 0;
			this.pixels = 0;
			this.sum = 0;
			this.bias = 0;
			this.worstTile = null;
		}
	}

	/** Whether GPU baking is asked for, by the setting or a test. */
	public static boolean wanted() {
		if (FORCED != null && !FORCED.isEmpty()) {
			return "1".equals(FORCED);
		}
		return Settings.get().gpuBaking;
	}

	/** Whether the check turned GPU baking off this session. */
	public static boolean failed() {
		return failed;
	}

	/**
	 * How the next bake of a sky view or a {@code lit} one runs. A {@link Mode#CHECK} must be
	 * answered with {@link #compare} or {@link #undrawn}, exactly once, with the same {@code lit}.
	 */
	public static Mode mode(boolean lit) {
		if (!wanted() || failed) {
			return Mode.TRACE;
		}
		Check check = lit ? LIT : SKY;
		if (check.passed) {
			return Mode.GPU;
		}
		synchronized (LOCK) {
			// A few at a time, so a tile without much in it doesn't hold the check up.
			if (!failed && !check.passed && check.compared + check.out < TILES) {
				check.out++;
				return Mode.CHECK;
			}
		}
		return Mode.TRACE;
	}

	/** A check tile came back from both bakers: the GPU's pixels and the tracer's, before any merge. */
	public static void compare(boolean lit, int[] gpu, int[] traced) {
		long tileBoth = 0;
		long tileMismatched = 0;
		double tileSum = 0;
		double tileBias = 0;
		for (int i = 0; i < traced.length; i++) {
			int c = traced[i];
			int g = gpu[i];
			boolean ck = (c >>> 24) != 0;
			if (ck != ((g >>> 24) != 0)) {
				tileMismatched++;
			} else if (ck) {
				tileBoth++;
				int dr = (g & 0xFF) - (c & 0xFF);
				int dg = ((g >> 8) & 0xFF) - ((c >> 8) & 0xFF);
				int db = ((g >> 16) & 0xFF) - ((c >> 16) & 0xFF);
				tileSum += (Math.abs(dr) + Math.abs(dg) + Math.abs(db)) / 3.0;
				tileBias += (dr + dg + db) / 3.0;
			}
		}
		Check check = lit ? LIT : SKY;
		synchronized (LOCK) {
			check.out--;
			if (failed || check.passed) {
				return;
			}
			if (tileBoth < traced.length / 4) {
				// Mostly unexplored or empty: another tile will tell more.
				return;
			}
			double mean = tileSum / tileBoth;
			double brighter = tileBias / tileBoth;
			// Judged alone as well, so tiles that match can't hide one that doesn't.
			if (mean > TILE_MAX_MEAN || Math.abs(brighter) > TILE_MAX_BIAS) {
				check.worstTile = String.format(java.util.Locale.ROOT, "one tile %.2f/255, %+.2f brighter", mean, brighter);
			}
			check.compared++;
			check.both += tileBoth;
			check.mismatched += tileMismatched;
			check.pixels += traced.length;
			check.sum += tileSum;
			check.bias += tileBias;
			if (check.compared >= TILES) {
				decide(check);
			}
		}
	}

	/**
	 * A check tile the GPU didn't draw, or whose comparison no longer counts; it was traced instead.
	 * Only one the GPU couldn't draw counts against it, not one handed back because the world
	 * closed or the map's look changed meanwhile.
	 */
	public static void undrawn(boolean lit, boolean cannotDraw) {
		Check check = lit ? LIT : SKY;
		synchronized (LOCK) {
			check.out--;
			if (cannotDraw && !failed && !check.passed && ++check.failures >= FAILURES) {
				fail("couldn't draw any of " + check.failures + " " + check.name + " tiles");
			}
		}
	}

	private static void decide(Check check) {
		double mean = check.sum / check.both;
		double brighter = check.bias / check.both;
		double mismatch = check.mismatched / (double) check.pixels;
		String measured = String.format(java.util.Locale.ROOT, "%s, round %d: %d tiles, mean difference %.2f/255, %+.2f brighter, %.2f%% known/unknown mismatch%s",
			check.name, check.round, check.compared, mean, brighter, mismatch * 100, check.worstTile == null ? "" : ", " + check.worstTile);
		// Known/unknown mismatch is only logged: the first tiles bake while chunks are still
		// loading, and at the edge of a missing chunk the mesher and the tracer leave slightly
		// different pixels unknown (3 to 6% then, under 0.1% once loaded). Garbage or a flipped
		// readback would break the mean anyway.
		if (mean <= MAX_MEAN && Math.abs(brighter) <= MAX_BIAS && check.worstTile == null) {
			check.passed = true;
			Diorama.LOGGER.info("GPU baking matches the tracer on {} ({}): on", GpuTileBaker.device(), measured);
		} else if (check.round < ROUNDS) {
			Diorama.LOGGER.info("GPU baking didn't match the tracer on {} ({}); checking again", GpuTileBaker.device(), measured);
			check.nextRound();
		} else {
			fail("its tiles don't match the tracer's (" + measured + ")");
		}
	}

	private static void fail(String why) {
		failed = true;
		Diorama.LOGGER.warn("GPU baking turned off for this session on {}: {}. Tiles are traced on the CPU instead.", GpuTileBaker.device(), why);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			Component message = Component.translatable("diorama.gpu_baking.failed");
			//? if >=26.1 {
			mc.gui.hud.getChat().addClientSystemMessage(message);
			//?} else {
			/*mc.gui.getChat().addMessage(message);
			*///?}
		});
	}
}
