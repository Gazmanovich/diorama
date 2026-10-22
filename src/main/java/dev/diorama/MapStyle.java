package dev.diorama;

import java.util.List;

/**
 * How the map looks: camera angle, colour grade, lighting, outlines, per-block variation and the
 * shape of the fog edge. Switching to a style whose tiles look different re-bakes every tile.
 *
 * @param sunTint colour of direct sunlight, multiplied per channel
 * @param shadowTint colour of the ambient sky light that fills shadows
 * @param outline how much to darken pixels next to a much nearer surface, 0 for none
 * @param blockVariation brightness spread between neighbouring blocks of the same kind, 0 for none
 * @param roundedFog round the fog edge off instead of following the loaded chunks
 * @param clearWater water absorbs each colour by depth, so shallows look turquoise and deep
 *     water blue, instead of laying an even wash of the water colour over the bottom
 * @param skyOcclusion how much a spot that sees little sky (under trees, in a valley, at the foot
 *     of a wall) loses of its ambient light, 0 for none
 * @param sunElevation the sun's height above the horizon in degrees; lower casts longer shadows
 * @param ambient sky light that fills shadows; lower makes shadows deeper
 * @param sunStrength direct sunlight on a top face
 * @param heightShade how much brighter high ground is than low ground, 0 for none
 * @param cornerOcclusion how much the shade where a block meets a wall or another block dims the
 *     sunlight there too: 0 dims only the sky light, 1 the sunlight as much, the way the game's
 *     smooth lighting does, and more digs the creases deeper still
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
	boolean clearWater,
	float skyOcclusion,
	double sunElevation,
	float ambient,
	float sunStrength,
	float heightShade,
	float cornerOcclusion
) {
	/** The lowest and highest sun a style may use; shadow reach for re-baking is worked out from them. */
	public static final double MIN_SUN_ELEVATION = 25;
	public static final double MAX_SUN_ELEVATION = 60;

	public static final MapStyle BASELINE = new MapStyle(
		"Baseline", 60, 1.18F, 1.06F, new float[] {1.06F, 1.0F, 0.9F}, new float[] {0.9F, 0.95F, 1.08F}, 0, 0, false, false, 0, 51.3, 0.5F, 0.484F, 0, 0
	);

	/** The look before depth: outlines for crisp silhouettes and a rounded fog edge. */
	public static final MapStyle FLAT = BASELINE.withName("Flat").withOutline(0.38F).withRoundedFog(true);

	/**
	 * What players get: {@link #FLAT} made to read in depth. Ground that sees less sky gets
	 * darker, a lower sun casts longer shadows, shadows are deeper and cooler against warm
	 * sunlight, high ground is a little brighter than low ground, creases where blocks meet are
	 * shaded in sunlight too, and the camera looks a bit lower so more block sides show.
	 */
	public static final MapStyle DEFAULT = FLAT.withName("Default")
		.withPitch(50)
		.withGrade(1.35F, 1.16F, new float[] {1.14F, 1.0F, 0.8F}, new float[] {0.74F, 0.86F, 1.18F})
		.withSkyOcclusion(0.85F)
		.withSunElevation(38)
		.withLight(0.34F, 0.62F)
		.withHeightShade(0.22F)
		.withCornerOcclusion(1.5F);

	/** {@link #FLAT}, then {@link #DEFAULT} with one part of the depth look taken back each, for comparing. */
	public static final List<MapStyle> VARIANTS = List.of(
		FLAT,
		DEFAULT.withName("No sky occlusion").withSkyOcclusion(0),
		DEFAULT.withName("High sun").withSunElevation(BASELINE.sunElevation),
		DEFAULT.withName("Soft light").withLight(BASELINE.ambient, BASELINE.sunStrength),
		DEFAULT.withName("No height shade").withHeightShade(0),
		DEFAULT.withName("Corners in shade only").withCornerOcclusion(0),
		DEFAULT.withName("Steeper camera").withPitch(BASELINE.pitchDegrees),
		DEFAULT.withName("Neutral colour").withGrade(BASELINE.saturation, BASELINE.exposure, BASELINE.sunTint, BASELINE.shadowTint)
	);

	/** {@link #VARIANTS} plus the default, for the in-game style tour. */
	public static List<MapStyle> tour() {
		return java.util.stream.Stream.concat(VARIANTS.stream(), java.util.stream.Stream.of(DEFAULT)).toList();
	}

	private static volatile MapStyle current = DEFAULT;

	public static MapStyle get() {
		return current;
	}

	public static void set(MapStyle style) {
		current = style;
		Projection.setPitch(style.pitchDegrees);
	}

	/**
	 * True when tiles baked under one style look the same under the other. The name doesn't
	 * count, and neither does the fog edge, which is drawn over the tiles.
	 */
	public boolean looksLike(MapStyle other) {
		return this.pitchDegrees == other.pitchDegrees
			&& this.saturation == other.saturation
			&& this.exposure == other.exposure
			&& java.util.Arrays.equals(this.sunTint, other.sunTint)
			&& java.util.Arrays.equals(this.shadowTint, other.shadowTint)
			&& this.outline == other.outline
			&& this.blockVariation == other.blockVariation
			&& this.clearWater == other.clearWater
			&& this.skyOcclusion == other.skyOcclusion
			&& this.sunElevation == other.sunElevation
			&& this.ambient == other.ambient
			&& this.sunStrength == other.sunStrength
			&& this.heightShade == other.heightShade
			&& this.cornerOcclusion == other.cornerOcclusion;
	}

	public MapStyle withName(String name) {
		return new MapStyle(name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withPitch(double degrees) {
		return new MapStyle(this.name, degrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withGrade(float saturation, float exposure, float[] sun, float[] shadow) {
		return new MapStyle(this.name, this.pitchDegrees, saturation, exposure, sun, shadow, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withOutline(float outline) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withBlockVariation(float variation) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, variation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withClearWater(boolean clear) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, clear, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withRoundedFog(boolean rounded) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, rounded, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withSkyOcclusion(float amount) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, amount, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withLight(float ambient, float sunStrength) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, ambient, sunStrength, this.heightShade, this.cornerOcclusion);
	}

	public MapStyle withHeightShade(float amount) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, amount, this.cornerOcclusion);
	}

	public MapStyle withCornerOcclusion(float amount) {
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, this.sunElevation, this.ambient, this.sunStrength, this.heightShade, amount);
	}

	public MapStyle withSunElevation(double degrees) {
		double clamped = Math.max(MIN_SUN_ELEVATION, Math.min(MAX_SUN_ELEVATION, degrees));
		return new MapStyle(this.name, this.pitchDegrees, this.saturation, this.exposure, this.sunTint, this.shadowTint, this.outline, this.blockVariation, this.roundedFog, this.clearWater, this.skyOcclusion, clamped, this.ambient, this.sunStrength, this.heightShade, this.cornerOcclusion);
	}
}
