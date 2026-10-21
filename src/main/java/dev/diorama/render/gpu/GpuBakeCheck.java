package dev.diorama.render.gpu;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.Settings;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Whether a bake goes to the GPU. The player turns GPU baking on in the settings
 * ({@code DIORAMA_GPU_BAKE=1} forces it on for tests). Before the map shows any GPU tile, the
 * first few are also traced and the two compared: a graphics driver that draws them differently
 * turns GPU baking off for the rest of the session, so it can't spoil a map.
 *
 * <p>Both bakes of a check tile read the same chunks (a {@code PinnedSource}), so any difference
 * past what the two sample patterns explain is the GPU's. Four kinds of bake are checked apart:
 * sky views and lit views (cave slices, the Nether under its roof), each with overlays and
 * without. Only sky views draw sun shadows and sky occlusion, and only overlay bakes write the
 * overlay bits, so a pass on one kind says nothing about another. Fog-of-war bakes are never
 * check tiles, since what the player has seen keeps changing under them.
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
	/** Tiles compared, each with enough known pixels to say something. */
	private static final int TILES = 4;
	/** Bakes the GPU couldn't draw at all before giving up on it. */
	private static final int FAILURES = 3;
	/**
	 * Limits for each tile, over pixels both bakes know: the mean difference and how much brighter
	 * or darker the GPU's are on average. Tile by tile, matching drivers measured biases up to 1.6
	 * and means up to 2.9 in forest but 4.4 in bright snow, where every shadow edge the two sample
	 * patterns place differently costs more. A driver that lost the sky occlusion (an integer
	 * texture read as zeros) measured biases from 2.8, all of it brighter: the bias tells them
	 * apart, and the mean only catches what's far off.
	 */
	private static final double MAX_MEAN = 6;
	private static final double MAX_BIAS = 2.2;
	/**
	 * Share of the tile's pixels known to one bake and not the other, and of pixels both know
	 * with a different alpha (how many of the four samples hit something). Along the edge of the
	 * explored area the two sample patterns split pixels differently: matching drivers measured
	 * up to 0.12% and 3.1% there. A GPU that gets coverage wrong gets it wrong almost everywhere.
	 */
	private static final double MAX_MISMATCH = 0.02;
	private static final double MAX_ALPHA = 0.2;
	/** Share of pixels both know whose overlay bits differ: 0.35% measured, from the sample positions. */
	private static final double MAX_OVERLAY = 0.02;
	/**
	 * Pixels both bakes must know for a tile to count towards a pass, and, fewer, for its
	 * differences to fail the check: a tile mostly unexplored still shows a GPU that's wrong.
	 */
	private static final int PASS_PIXELS = Projection.TILE_PIXELS * Projection.TILE_PIXELS / 4;
	private static final int JUDGE_PIXELS = 2048;

	private static final Object LOCK = new Object();
	private static volatile boolean failed;
	/** By kind: sky view, lit view, each without overlays and with. */
	private static final Check[] CHECKS = {
		new Check("sky view"), new Check("lit view"), new Check("sky view with overlays"), new Check("lit view with overlays")
	};

	private GpuBakeCheck() {
	}

	/** One kind of view's check. Guarded by {@link #LOCK}. */
	private static final class Check {
		final String name;
		volatile boolean passed;
		int out;
		int compared;
		int failures;
		double worstMean;
		double worstBias;
		double worstMismatch;
		double worstAlpha;
		double worstOverlay;

		Check(String name) {
			this.name = name;
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

	private static Check check(boolean lit, boolean overlays) {
		return CHECKS[(lit ? 1 : 0) + (overlays ? 2 : 0)];
	}

	/**
	 * How the next bake of a sky view or a {@code lit} one runs, with fog of war and overlays or
	 * not. A {@link Mode#CHECK} must be answered with {@link #compare} or {@link #undrawn},
	 * exactly once, with the same kind, and its two bakes must read the same chunks.
	 */
	public static Mode mode(boolean lit, boolean fogOfWar, boolean overlays) {
		if (!wanted() || failed) {
			return Mode.TRACE;
		}
		Check check = check(lit, overlays);
		if (check.passed) {
			return Mode.GPU;
		}
		if (fogOfWar) {
			return Mode.TRACE;
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

	/**
	 * A check tile came back from both bakers: the GPU's pixels and the tracer's, before any merge,
	 * with their overlay bits when the bake asked for them.
	 */
	public static void compare(boolean lit, int[] gpu, int[] traced, byte @Nullable [] gpuInfo, byte @Nullable [] tracedInfo) {
		boolean overlays = tracedInfo != null;
		long both = 0;
		long mismatched = 0;
		long alphaDiffers = 0;
		long overlayDiffers = 0;
		double sum = 0;
		double bias = 0;
		for (int i = 0; i < traced.length; i++) {
			int c = traced[i];
			int g = gpu[i];
			boolean ck = (c >>> 24) != 0;
			if (ck != ((g >>> 24) != 0)) {
				mismatched++;
			} else if (ck) {
				both++;
				if ((c >>> 24) != (g >>> 24)) {
					alphaDiffers++;
				}
				if (overlays && (gpuInfo == null || gpuInfo[i] != tracedInfo[i])) {
					overlayDiffers++;
				}
				int dr = (g & 0xFF) - (c & 0xFF);
				int dg = ((g >> 8) & 0xFF) - ((c >> 8) & 0xFF);
				int db = ((g >> 16) & 0xFF) - ((c >> 16) & 0xFF);
				sum += (Math.abs(dr) + Math.abs(dg) + Math.abs(db)) / 3.0;
				bias += (dr + dg + db) / 3.0;
			}
		}
		Check check = check(lit, overlays);
		synchronized (LOCK) {
			check.out--;
			if (failed || check.passed) {
				return;
			}
			double mismatch = mismatched / (double) traced.length;
			// Too few pixels known to both to judge their colours: only the mismatch tells.
			boolean judged = both >= JUDGE_PIXELS;
			double mean = judged ? sum / both : 0;
			double brighter = judged ? bias / both : 0;
			double alpha = judged ? alphaDiffers / (double) both : 0;
			double overlay = judged && overlays ? overlayDiffers / (double) both : 0;
			// Every tile must match on its own: the two read the same chunks, so nothing but the
			// GPU makes them differ, and matching tiles mustn't hide one that doesn't.
			if (mean > MAX_MEAN || Math.abs(brighter) > MAX_BIAS || mismatch > MAX_MISMATCH || alpha > MAX_ALPHA || overlay > MAX_OVERLAY) {
				fail(String.format(java.util.Locale.ROOT, "a %s tile doesn't match the tracer's (mean difference %.2f/255, %+.2f brighter, %.2f%% known/unknown mismatch, %.2f%% different alpha, %.2f%% different overlay bits)",
					check.name, mean, brighter, mismatch * 100, alpha * 100, overlay * 100));
				return;
			}
			if (both < PASS_PIXELS) {
				// Mostly unexplored or empty: it matched, but another tile will tell more.
				return;
			}
			check.compared++;
			check.worstMean = Math.max(check.worstMean, mean);
			check.worstBias = Math.abs(brighter) > Math.abs(check.worstBias) ? brighter : check.worstBias;
			check.worstMismatch = Math.max(check.worstMismatch, mismatch);
			check.worstAlpha = Math.max(check.worstAlpha, alpha);
			check.worstOverlay = Math.max(check.worstOverlay, overlay);
			if (check.compared >= TILES) {
				check.passed = true;
				Diorama.LOGGER.info("GPU baking matches the tracer on {} ({}): on", GpuTileBaker.device(), String.format(java.util.Locale.ROOT,
					"%s, %d tiles, worst: mean difference %.2f/255, %+.2f brighter, %.2f%% known/unknown mismatch, %.2f%% different alpha, %.2f%% different overlay bits",
					check.name, check.compared, check.worstMean, check.worstBias, check.worstMismatch * 100, check.worstAlpha * 100, check.worstOverlay * 100));
			}
		}
	}

	/**
	 * A check tile the GPU didn't draw, or whose comparison no longer counts; it was traced instead.
	 * Only one the GPU couldn't draw counts against it, not one handed back because the world
	 * closed or the map's look changed meanwhile.
	 */
	public static void undrawn(boolean lit, boolean overlays, boolean cannotDraw) {
		Check check = check(lit, overlays);
		synchronized (LOCK) {
			check.out--;
			if (cannotDraw && !failed && !check.passed && ++check.failures >= FAILURES) {
				fail("couldn't draw any of " + check.failures + " " + check.name + " tiles");
			}
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
