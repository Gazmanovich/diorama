package dev.diorama.bake;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Baked pixels for a tile on the CPU side: either the whole tile, or patches of
 * {@link #BLOCK}-pixel blocks that changed.
 *
 * <p>Pixels are ABGR ints, the byte order the GPU texture expects. Coverage is the share of known
 * pixels per coverage cell, 0..255, which places the fog. {@code info} holds {@link Overlays} bits
 * per pixel, or is null when the bake didn't record them. {@code spots} are the {@link TileSpots}
 * of a surface tile baked to be saved, null otherwise.
 */
public record TileImage(
	TileKey key, int @Nullable [][] mips, byte @Nullable [] coverage, byte @Nullable [] info, List<Patch> patches, int generation, int @Nullable [] spots
) {
	/** Edge of a re-bake block in pixels. A power of two at least 2^(mip levels - 1), so mips stay aligned. */
	public static final int BLOCK = 32;

	public static TileImage full(TileKey key, int[][] mips, byte[] coverage) {
		return full(key, mips, coverage, null);
	}

	public static TileImage full(TileKey key, int[][] mips, byte[] coverage, byte @Nullable [] info) {
		return new TileImage(key, mips, coverage, info, List.of(), 0, null);
	}

	public static TileImage partial(TileKey key, List<Patch> patches) {
		return new TileImage(key, null, null, null, patches, 0, null);
	}

	/** Tags the image with the map state it was baked under; see {@code MapLevel}. */
	public TileImage withGeneration(int generation) {
		return new TileImage(this.key, this.mips, this.coverage, this.info, this.patches, generation, this.spots);
	}

	public TileImage withSpots(int @Nullable [] spots) {
		return new TileImage(this.key, this.mips, this.coverage, this.info, this.patches, this.generation, spots);
	}

	public boolean isFull() {
		return this.mips != null;
	}

	/** One re-baked block at block coordinates {@code (blockX, blockY)} within the tile. */
	public record Patch(int blockX, int blockY, int[][] mips, byte[] coverage, byte @Nullable [] info) {
	}
}
