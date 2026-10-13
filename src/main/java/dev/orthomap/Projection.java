package dev.orthomap;

/**
 * The fixed oblique camera every map pixel is traced through.
 *
 * <p>The camera looks north and down at {@link #PITCH_DEGREES} from the horizon, so the map shows
 * block tops and their south faces. Map space is measured in blocks:
 * <ul>
 *   <li>{@code u = x} (east is right)</li>
 *   <li>{@code v = z * SIN - y * COS} (south is down, and higher blocks move up the screen)</li>
 * </ul>
 */
public final class Projection {
	public static final double PITCH_DEGREES = 60.0;
	public static final double SIN = Math.sin(Math.toRadians(PITCH_DEGREES));
	public static final double COS = Math.cos(Math.toRadians(PITCH_DEGREES));

	/** Map units per tile edge. */
	public static final int TILE_UNITS = 128;
	/** Texture pixels per map unit. */
	public static final int PIXELS_PER_UNIT = 4;
	public static final int TILE_PIXELS = TILE_UNITS * PIXELS_PER_UNIT;
	/** Fog coverage cells per tile edge (one cell covers {@code TILE_UNITS / COVERAGE_CELLS} units). */
	public static final int COVERAGE_CELLS = 64;

	private Projection() {
	}

	public static double v(double y, double z) {
		return z * SIN - y * COS;
	}

	/** World z where a ray through map row {@code v} crosses height {@code y}. */
	public static double zAt(double v, double y) {
		return (v + y * COS) / SIN;
	}
}
