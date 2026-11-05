package dev.diorama.render;

import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * How dark the sky view is drawn: by day the tiles as baked, at night dimmed toward moonlight with
 * a warm glow where their block light is ({@code tile_night.fsh}; the numbers are shared with it).
 * Only the sky view of a dimension whose time moves gets dark: caves and the Nether are lit by
 * the game's own light already, and the End's sky never changes.
 */
public final class NightLook {
	/** Each colour channel at full night, before any block light: as {@code tile_night.fsh}'s MOON. */
	private static final float[] MOON = {0.27F, 0.33F, 0.49F};
	/** The glow block light adds, as {@code tile_night.fsh}'s WARM, GLOW and GLOW_CURVE. */
	private static final float[] WARM = {1.15F, 0.82F, 0.50F};
	private static final float GLOW = 1.35F;
	private static final float GLOW_CURVE = 0.9F;
	/** A sky this dark ({@code Level.getSkyDarken}, 0 at noon) counts as full night. */
	private static final int FULL_NIGHT = 11;
	/** Seconds the drawn darkness takes to catch up with the sky, so dusk doesn't step a level at a time. */
	private static final float EASE_SECONDS = 1.5F;

	private static float shown = -1;
	private static long shownAt;
	private static ClientLevel shownFor;

	private NightLook() {
	}

	/**
	 * How far into the night a view of {@code map} at {@code layer} is drawn right now: 0 in caves
	 * and under a roof, which the game's own light lights, else {@link #amount}. Render thread.
	 */
	public static float forView(MapLevel map, int layer) {
		ClientLevel world = Minecraft.getInstance().level;
		return layer != TileKey.SURFACE || map.roofless() || world == null ? 0 : amount(world);
	}

	/**
	 * How far into the night the sky view of {@code level} is drawn right now, 0 for day to 1 for
	 * full night, as the player's setting asks. Render thread.
	 */
	public static float amount(ClientLevel level) {
		Settings.DayNight setting = Settings.get().dayNight;
		if (setting == Settings.DayNight.DAY || level.dimensionType().hasFixedTime()) {
			return 0;
		}
		if (setting == Settings.DayNight.NIGHT) {
			return 1;
		}
		//? if <1.21.2 {
		/*// Until 1.21.2 the client worked this out only when a level loaded.
		level.updateSkyBrightness();
		*///?}
		float target = Mth.clamp(level.getSkyDarken() / (float) FULL_NIGHT, 0, 1);
		long now = System.nanoTime();
		if (shownFor != level || shown < 0) {
			shown = target;
		} else {
			float step = Math.min(1, (now - shownAt) / 1e9F / EASE_SECONDS);
			shown += (target - shown) * step;
		}
		shownFor = level;
		shownAt = now;
		return shown;
	}

	/**
	 * The tint a tile with no block light is drawn with this far into the night, {@code alpha}
	 * (0..255) the map's opacity: white by day.
	 */
	public static int dim(float night, int alpha) {
		int r = Math.round(Mth.lerp(night, 1, MOON[0]) * 255);
		int g = Math.round(Mth.lerp(night, 1, MOON[1]) * 255);
		int b = Math.round(Mth.lerp(night, 1, MOON[2]) * 255);
		return alpha << 24 | r << 16 | g << 8 | b;
	}

	/**
	 * An ABGR tile pixel this far into the night with block light {@code light} (0..1) on it, as
	 * {@code tile_night.fsh} draws it: for image exports.
	 */
	public static int light(int abgr, float light, float night) {
		float glow = (float) Math.pow(light, GLOW_CURVE) * GLOW * night;
		int r = channel((abgr & 255) * (Mth.lerp(night, 1, MOON[0]) + WARM[0] * glow));
		int g = channel((abgr >> 8 & 255) * (Mth.lerp(night, 1, MOON[1]) + WARM[1] * glow));
		int b = channel((abgr >> 16 & 255) * (Mth.lerp(night, 1, MOON[2]) + WARM[2] * glow));
		return abgr & 0xFF000000 | b << 16 | g << 8 | r;
	}

	/** An RGB colour (0..1 a channel) as the tiles darken this far into the night, in place. */
	public static float[] darken(float[] rgb, float night) {
		return new float[] {rgb[0] * Mth.lerp(night, 1, MOON[0]), rgb[1] * Mth.lerp(night, 1, MOON[1]), rgb[2] * Mth.lerp(night, 1, MOON[2])};
	}

	private static int channel(float value) {
		return Math.min(255, Math.round(value));
	}

	/** An ARGB colour as the tiles darken this far into the night. */
	public static int darken(int argb, float night) {
		int r = Math.round((argb >> 16 & 255) * Mth.lerp(night, 1, MOON[0]));
		int g = Math.round((argb >> 8 & 255) * Mth.lerp(night, 1, MOON[1]));
		int b = Math.round((argb & 255) * Mth.lerp(night, 1, MOON[2]));
		return argb & 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** The vertex colour {@code tile_night.fsh} reads: the night in red, crisp in green, the opacity in alpha. */
	public static int params(float night, boolean crisp, int alpha) {
		return alpha << 24 | Math.round(night * 255) << 16 | (crisp ? 255 : 0) << 8;
	}

	/** The tint {@link #dim} gives for the night and opacity in {@link #params}, for drawing such a tile without its light. */
	public static int tintOf(int params) {
		return dim((params >> 16 & 255) / 255.0F, params >>> 24);
	}
}
