package dev.diorama.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The colours the waypoint editor offers, in two rows of sixteen: Diorama's own palette and the
 * sixteen dye colours in creative-inventory order. Any other colour comes from the hue and shade
 * bars or as hex.
 */
public final class WaypointColours {
	public static final int COLUMNS = 16;

	/** One colour in the picker and the name its tooltip shows. */
	public record Swatch(int argb, Component name) {
	}

	private static final String[] CLASSIC_NAMES = {
		"red", "orange", "amber", "green", "teal", "sky", "indigo", "violet",
		"pink", "white", "silver", "slate", "brown", "lime", "ice", "blossom"
	};
	/** The dyes' colours as the game lists them for dyed leather and banners. */
	private static final int[] DYES = {
		0xF9FFFE, 0x9D9D97, 0x474F52, 0x1D1D21, 0x835432, 0xB02E26, 0xF9801D, 0xFED83D,
		0x80C71F, 0x5E7C16, 0x169C9C, 0x3AB3DA, 0x3C44AA, 0x8932B8, 0xC74EBD, 0xF38BAA
	};
	private static final String[] DYE_NAMES = {
		"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
		"lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"
	};
	public static final List<Swatch> ALL;

	static {
		List<Swatch> all = new ArrayList<>();
		for (int i = 0; i < Waypoint.PALETTE.length; i++) {
			all.add(new Swatch(Waypoint.PALETTE[i], Component.translatable("diorama.colour.classic." + CLASSIC_NAMES[i])));
		}
		for (int i = 0; i < DYES.length; i++) {
			// The game's own names for its dye colours.
			all.add(new Swatch(0xFF000000 | DYES[i], Component.translatable("color.minecraft." + DYE_NAMES[i])));
		}
		ALL = Collections.unmodifiableList(all);
	}

	private WaypointColours() {
	}

	public static int rows() {
		return (ALL.size() + COLUMNS - 1) / COLUMNS;
	}

	/** The named swatch with this colour, if there is one. */
	public static @Nullable Swatch named(int argb) {
		for (Swatch swatch : ALL) {
			if ((swatch.argb() & 0xFFFFFF) == (argb & 0xFFFFFF)) {
				return swatch;
			}
		}
		return null;
	}

	/**
	 * A random, bright colour that's clearly different from {@code avoid}: any hue at least a
	 * sixth of the way round the wheel from it, saturated enough to read on grass and water.
	 */
	public static int random(int avoid) {
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		float avoidHue = hsv(avoid)[0];
		float hue;
		do {
			hue = rng.nextFloat();
		} while (hueDistance(hue, avoidHue) < 1 / 6.0F);
		return rgb(hue, 0.6F + rng.nextFloat() * 0.35F, 0.8F + rng.nextFloat() * 0.2F);
	}

	private static float hueDistance(float a, float b) {
		float d = Math.abs(a - b) % 1;
		return Math.min(d, 1 - d);
	}

	/** An opaque colour from hue, saturation and value, each 0..1. */
	public static int rgb(float hue, float saturation, float value) {
		float h = (hue - (float) Math.floor(hue)) * 6;
		int sector = (int) h % 6;
		float f = h - (int) h;
		float p = value * (1 - saturation);
		float q = value * (1 - saturation * f);
		float t = value * (1 - saturation * (1 - f));
		float[] c = switch (sector) {
			case 0 -> new float[] {value, t, p};
			case 1 -> new float[] {q, value, p};
			case 2 -> new float[] {p, value, t};
			case 3 -> new float[] {p, q, value};
			case 4 -> new float[] {t, p, value};
			default -> new float[] {value, p, q};
		};
		return 0xFF000000 | Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
	}

	/** Hue, saturation and value of a colour, each 0..1. */
	public static float[] hsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255.0F;
		float g = ((argb >> 8) & 0xFF) / 255.0F;
		float b = (argb & 0xFF) / 255.0F;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float hue = 0;
		if (d > 0) {
			if (max == r) {
				hue = ((g - b) / d) / 6;
			} else if (max == g) {
				hue = ((b - r) / d + 2) / 6;
			} else {
				hue = ((r - g) / d + 4) / 6;
			}
		}
		return new float[] {hue - (float) Math.floor(hue), max == 0 ? 0 : d / max, max};
	}

	/**
	 * A shade of a hue along the picker's shade bar: white at 0, the pure hue at 0.5, near black at 1.
	 */
	public static int shade(float hue, float t) {
		return t <= 0.5F ? rgb(hue, t * 2, 1) : rgb(hue, 1, 1 - (t - 0.5F) * 2 * 0.9F);
	}

	/** Where a colour sits on the shade bar of its hue: the inverse of {@link #shade}, near enough. */
	public static float shadeOf(int argb) {
		float[] hsv = hsv(argb);
		return hsv[2] >= 0.99F ? hsv[1] / 2 : 0.5F + Math.min(1, (1 - hsv[2]) / 0.9F) / 2;
	}

	/** A colour as {@code #RRGGBB}. */
	public static String hex(int argb) {
		return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	/** Reads {@code RRGGBB}, {@code #RRGGBB} or {@code RGB} as an opaque colour; null if it isn't one. */
	public static @Nullable Integer parse(String text) {
		String t = text.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		if (t.length() == 3) {
			t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
		}
		if (t.length() != 6) {
			return null;
		}
		try {
			return 0xFF000000 | Integer.parseInt(t, 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
