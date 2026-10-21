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

	private enum State {
		UNCHECKED,
		PASSED,
		FAILED
	}

	private static final String FORCED = System.getenv("DIORAMA_GPU_BAKE");
	/** Tiles compared before the verdict, each with enough known pixels to say something. */
	private static final int TILES = 4;
	/** Bakes the GPU couldn't draw at all before giving up on it. */
	private static final int FAILURES = 3;
	/**
	 * Mean difference, over pixels both bakes know, past which they don't match, and how much
	 * brighter or darker the GPU's may be on average. Tile by tile, matching drivers measured
	 * means up to 2.9 (bright snow is the worst) and biases up to 1.6; a driver that lost the sky
	 * occlusion (an integer texture read as zeros) means from 3.4 and biases from 2.8, all of it
	 * brighter. The bias tells them apart best; the mean is as loose as the comparison test's.
	 */
	private static final double MAX_MEAN = 4;
	private static final double MAX_BIAS = 2;

	private static final Object LOCK = new Object();
	private static volatile State state = State.UNCHECKED;
	private static int out;
	private static int compared;
	private static int failures;
	private static long both;
	private static long mismatched;
	private static long pixels;
	private static double sum;
	private static double bias;

	private GpuBakeCheck() {
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
		return state == State.FAILED;
	}

	/** How the next bake runs. A {@link Mode#CHECK} must be answered with {@link #compare} or {@link #undrawn}, exactly once. */
	public static Mode mode() {
		if (!wanted() || state == State.FAILED) {
			return Mode.TRACE;
		}
		if (state == State.PASSED) {
			return Mode.GPU;
		}
		synchronized (LOCK) {
			// A few at a time, so a tile without much in it doesn't hold the check up.
			if (state == State.UNCHECKED && compared + out < TILES) {
				out++;
				return Mode.CHECK;
			}
		}
		return Mode.TRACE;
	}

	/** A check tile came back from both bakers: the GPU's pixels and the tracer's, before any merge. */
	public static void compare(int[] gpu, int[] traced) {
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
		synchronized (LOCK) {
			out--;
			if (state != State.UNCHECKED) {
				return;
			}
			if (tileBoth < traced.length / 4) {
				// Mostly unexplored or empty: another tile will tell more.
				return;
			}
			compared++;
			both += tileBoth;
			mismatched += tileMismatched;
			pixels += traced.length;
			sum += tileSum;
			bias += tileBias;
			if (compared >= TILES) {
				decide();
			}
		}
	}

	/**
	 * A check tile the GPU didn't draw; it was traced instead. Only one it couldn't draw counts
	 * against it, not one handed back because the world closed.
	 */
	public static void undrawn(boolean cancelled) {
		synchronized (LOCK) {
			out--;
			if (!cancelled && state == State.UNCHECKED && ++failures >= FAILURES) {
				fail("couldn't draw any of " + failures + " tiles");
			}
		}
	}

	private static void decide() {
		double mean = sum / both;
		double brighter = bias / both;
		double mismatch = mismatched / (double) pixels;
		String measured = String.format(java.util.Locale.ROOT, "%d tiles, mean difference %.2f/255, %+.2f brighter, %.2f%% known/unknown mismatch",
			compared, mean, brighter, mismatch * 100);
		// Only logged: the first tiles bake while chunks are still loading, and at the edge of a
		// missing chunk the mesher and the tracer leave slightly different pixels unknown (about 4%
		// then, under 0.1% once loaded). Garbage or a flipped readback would break the mean anyway.
		if (mean <= MAX_MEAN && Math.abs(brighter) <= MAX_BIAS) {
			state = State.PASSED;
			Diorama.LOGGER.info("GPU baking matches the tracer on {} ({}): on", GpuTileBaker.device(), measured);
		} else {
			fail("its tiles don't match the tracer's (" + measured + ")");
		}
	}

	private static void fail(String why) {
		state = State.FAILED;
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
