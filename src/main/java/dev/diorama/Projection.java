package dev.diorama;

/**
 * The oblique camera every map pixel is traced through.
 *
 * <p>The camera looks north and down at the style's pitch from the horizon, so the map shows
 * block tops and their south faces. Map space is measured in blocks:
 * <ul>
 *   <li>{@code u = x} (east is right)</li>
 *   <li>{@code v = z * sin - y * cos} (south is down, and higher blocks move up the screen)</li>
 * </ul>
 */
public final class Projection {
	// The default style's angle until a style is applied.
	private static volatile double sin = Math.sin(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));
	private static volatile double cos = Math.cos(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));

	/** Map units per tile edge. */
	public static final int TILE_UNITS = 128;
	/** Texture pixels per map unit. */
	public static final int PIXELS_PER_UNIT = 4;
	public static final int TILE_PIXELS = TILE_UNITS * PIXELS_PER_UNIT;
	/** Fog coverage cells per tile edge (one cell covers {@code TILE_UNITS / COVERAGE_CELLS} units). */
	public static final int COVERAGE_CELLS = 64;

	private Projection() {
	}

	/** Changes the camera angle. Everything baked before is wrong afterwards; see {@link MapStyle}. */
	static void setPitch(double degrees) {
		sin = Math.sin(Math.toRadians(degrees));
		cos = Math.cos(Math.toRadians(degrees));
	}

	public static double sin() {
		return sin;
	}

	public static double cos() {
		return cos;
	}

	public static double v(double y, double z) {
		return z * sin - y * cos;
	}

	/** World z where a ray through map row {@code v} crosses height {@code y}. */
	public static double zAt(double v, double y) {
		return (v + y * cos) / sin;
	}
}
