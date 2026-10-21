package dev.diorama.render.gpu;

/** How a {@link MapTexture} is sampled when drawn. Every choice minifies linearly. */
public enum Sampling {
	/** Tiles shown smaller than their pixels: blends across mip levels, clamped at the edges. */
	TILE_SMOOTH,
	/** Tiles shown larger than their pixels: as above, but magnified pixels stay crisp so blocks keep their edges. */
	TILE_CRISP,
	/** Clamped at the edges, linear, no mipmaps: the fog mask and icons. */
	CLAMP_LINEAR,
	/** Clamped, no mipmaps, magnified pixels kept square: pixel art drawn on the GUI grid. */
	CLAMP_CRISP,
	/** Repeating, linear, no mipmaps: the fog noise. */
	REPEAT_LINEAR;

	public boolean mipmapped() {
		return this == TILE_SMOOTH || this == TILE_CRISP;
	}

	public boolean crisp() {
		return this == TILE_CRISP || this == CLAMP_CRISP;
	}

	public boolean repeats() {
		return this == REPEAT_LINEAR;
	}
}
