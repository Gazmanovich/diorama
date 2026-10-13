package dev.orthomap.bake;

import dev.orthomap.Projection;

/**
 * One tile of the map. {@code layer} is {@link #SURFACE} for the sky view, or the cut height for a
 * cave slice.
 */
public record TileKey(int layer, int tileU, int tileV) {
	public static final int SURFACE = Integer.MIN_VALUE;

	public boolean isCave() {
		return this.layer != SURFACE;
	}

	public double minU() {
		return (double) this.tileU * Projection.TILE_UNITS;
	}

	public double minV() {
		return (double) this.tileV * Projection.TILE_UNITS;
	}
}
