package dev.orthomap.data;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;

/**
 * Which blocks the player has had line of sight to, one bit per block. The client thread is the
 * only writer and bits are only ever set, so bake threads can read without locking; at worst
 * they see a block as unseen for one more bake.
 */
public final class SeenBlocks {
	private final ConcurrentHashMap<Long, long[]> sections = new ConcurrentHashMap<>();

	public boolean isSeen(int x, int y, int z) {
		long[] bits = this.sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
		if (bits == null) {
			return false;
		}
		int i = index(x, y, z);
		return (bits[i >>> 6] & (1L << i)) != 0;
	}

	/** Marks a block seen; true if it wasn't before. */
	public boolean markSeen(int x, int y, int z) {
		long[] bits = this.sections.computeIfAbsent(SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new long[64]);
		int i = index(x, y, z);
		long mask = 1L << i;
		if ((bits[i >>> 6] & mask) != 0) {
			return false;
		}
		bits[i >>> 6] |= mask;
		return true;
	}

	private static int index(int x, int y, int z) {
		return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
	}
}
