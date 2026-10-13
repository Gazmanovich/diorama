package dev.orthomap.bake;

import dev.orthomap.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;

/** Where bake threads read chunk snapshots from. Must be safe to call from any thread. */
public interface ChunkSource {
	@Nullable ChunkSnapshot chunk(int chunkX, int chunkZ);

	/** Lowest block y in this dimension. */
	int minY();

	/** One above the highest block y in this dimension. */
	int maxY();

	/** One above the highest non-air block seen so far. */
	int topY();

	boolean isSeen(int x, int y, int z);
}
