package dev.orthomap;

import java.util.List;

/**
 * How the map looks: camera angle, colour grade, outlines, per-block variation and the shape of
 * the fog edge. Switching style re-bakes every tile.
 *
 * @param sunTint colour of direct sunlight, multiplied per channel
 * @param shadowTint colour of the ambient sky light that fills shadows
 * @param outline how much to darken pixels next to a much nearer surface, 0 for none
 * @param blockVariation brightness spread between neighbouring blocks of the same kind, 0 for none
 * @param roundedFog round the fog edge off instead of following the loaded chunks
 * @param clearWater water absorbs each colour by depth, so shallows look turquoise and deep
 *     water blue, instead of laying an even wash of the water colour over the bottom
 */
public record MapStyle(
	String name,
	double pitchDegrees,
	float saturation,
	float exposure,
	float[] sunTint,
	float[] shadowTint,
	float outline,
	float blockVariation,
	boolean roundedFog,
	boolean clearWater
) {
	public static final MapStyle BASELINE = new MapStyle(
		"Baseline", 60, 1.18F, 1.06F, new float[] {1.06F, 1.0F, 0.9F}, new float[] {0.9F, 0.95F, 1.08F}, 0, 0, false, false
	);

	/** One variable changed from {@link #BASELINE} each, for comparing looks side by side. */
	public static final List<MapStyle> VARIANTS = List.of(
		BASELINE,
		BASELINE.withName("Steeper camera").withPitch(70),
		BASELINE.withName("Diorama colour").withGrade(1.45F, 1.08F, new float[] {1.14F, 1.0F, 0.8F}, new float[] {0.74F, 0.86F, 1.18F}),
		BASELINE.withName("Outlines").withOutline(0.38F),
		BASELINE.withName("Block variation").withBlockVariation(0.07F),
		BASELINE.withName("Rounded fog edge").withRoundedFog(true),
		BASELINE.withName("Clear water").withClearWater(true)
	);

	/** {@link #VARIANTS} plus the default, for the in-game style tour. */
	public static List<MapStyle> tour() {
		return java.util.stream.Stream.concat(VARIANTS.stream(), java.util.stream.Stream.of(DEFAULT)).toList();
	}

	/** What players get: outlines for crisp silhouettes and a rounded fog edge. */
	public static final MapStyle DEFAULT = BASELINE.withName("Default").withOutline(0.38F).withRoundedFog(true);

	private static volatile MapStyle current = DEFAULT;

	public static MapStyle get() {
		return current;
	}

	public static void set(MapStyle style) {
		current = style;
		Projection.setPitch(style.pitchDegrees);
	}

	public MapStyle withName(String name) {
		return new MapStyle(name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater);
	}

	public MapStyle withPitch(double degrees) {
		return new MapStyle(this.name, degrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater);
	}

	public MapStyle withGrade(float saturation, float exposure, float[] sun, float[] shadow) {
		return new MapStyle(this.name, this.pitchDegrees, saturation, exposure, sun, shadow, this.outline, this.blockVariation, this.roundedFog, this.clearWater);
	}

	public MapStyle withOutline(float outline) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, outline, this.blockVariation, this.roundedFog, this.clearWater);
	}

	public MapStyle withBlockVariation(float variation) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, variation, this.roundedFog, this.clearWater);
	}

	public MapStyle withClearWater(boolean clear) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, clear);
	}

	public MapStyle withRoundedFog(boolean rounded) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, rounded, this.clearWater);
	}
}
