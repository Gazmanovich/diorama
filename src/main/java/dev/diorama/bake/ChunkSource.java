package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
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

	/**
	 * In a dimension with a roof (the Nether), the y just above the top of the space under it;
	 * {@link #NO_ROOF} otherwise. The sky view of such a dimension looks down from under the roof.
	 */
	default int roof() {
		return NO_ROOF;
	}

	int NO_ROOF = Integer.MIN_VALUE;

	boolean isSeen(int x, int y, int z);
}
