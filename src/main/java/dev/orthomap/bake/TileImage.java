package dev.orthomap.bake;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Baked pixels for a tile on the CPU side: either the whole tile, or patches of
 * {@link #BLOCK}-pixel blocks that changed.
 *
 * <p>Pixels are ABGR ints, the byte order the GPU texture expects. Coverage is the share of known
 * pixels per coverage cell, 0..255, which places the fog.
 */
public record TileImage(TileKey key, int @Nullable [][] mips, byte @Nullable [] coverage, List<Patch> patches, int generation) {
	/** Edge of a re-bake block in pixels. A power of two at least 2^(mip levels - 1), so mips stay aligned. */
	public static final int BLOCK = 32;

	public static TileImage full(TileKey key, int[][] mips, byte[] coverage) {
		return new TileImage(key, mips, coverage, List.of(), 0);
	}

	public static TileImage partial(TileKey key, List<Patch> patches) {
		return new TileImage(key, null, null, patches, 0);
	}

	/** Tags the image with the map state it was baked under; see {@code MapLevel}. */
	public TileImage withGeneration(int generation) {
		return new TileImage(this.key, this.mips, this.coverage, this.patches, generation);
	}

	public boolean isFull() {
		return this.mips != null;
	}

	/** One re-baked block at block coordinates {@code (blockX, blockY)} within the tile. */
	public record Patch(int blockX, int blockY, int[][] mips, byte[] coverage) {
	}
}
