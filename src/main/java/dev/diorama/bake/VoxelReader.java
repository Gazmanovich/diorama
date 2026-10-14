package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Block and light lookups for one bake job. Decodes each section it touches once into a flat
 * array of block state ids. Not thread-safe; each job gets its own reader.
 */
final class VoxelReader {
	static final int UNKNOWN = -1;
	static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());

	private final ChunkSource source;
	private final int minY;
	private final int maxY;
	private final Long2ObjectOpenHashMap<Decoded> cache = new Long2ObjectOpenHashMap<>();
	private int lastChunkX = Integer.MIN_VALUE;
	private int lastChunkZ = Integer.MIN_VALUE;
	private @Nullable Decoded last;

	VoxelReader(ChunkSource source) {
		this.source = source;
		this.minY = source.minY();
		this.maxY = source.maxY();
	}

	static final boolean DEBUG = dev.diorama.Diorama.DEBUG;
	final java.util.Set<Long> missing = DEBUG ? new java.util.HashSet<>() : null;

	/** Block state id at the position, {@link #AIR_ID} outside the world, or {@link #UNKNOWN}. */
	int state(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return AIR_ID;
		}
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			if (DEBUG) {
				this.missing.add(ChunkSnapshot.key(x >> 4, z >> 4));
			}
			return UNKNOWN;
		}
		char[] ids = chunk.section((y >> 4) - chunk.snapshot.minSectionY);
		return ids == null ? AIR_ID : ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
	}

	int blockLight(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 0;
		}
		return chunk.snapshot.blockLight((y >> 4) - chunk.snapshot.minSectionY, x & 15, y & 15, z & 15);
	}

	int skyLight(int x, int y, int z) {
		if (y < this.minY) {
			return 0;
		}
		if (y >= this.maxY) {
			return 15;
		}
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 15;
		}
		return chunk.snapshot.skyLight((y >> 4) - chunk.snapshot.minSectionY, x & 15, y & 15, z & 15);
	}

	/** The chunk snapshot holding this column, for biome tints. */
	@Nullable ChunkSnapshot snapshot(int x, int z) {
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		return chunk == null ? null : chunk.snapshot;
	}

	private @Nullable Decoded chunk(int chunkX, int chunkZ) {
		if (chunkX == this.lastChunkX && chunkZ == this.lastChunkZ) {
			return this.last;
		}
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		Decoded decoded = this.cache.get(key);
		if (decoded == null && !this.cache.containsKey(key)) {
			ChunkSnapshot snapshot = this.source.chunk(chunkX, chunkZ);
			decoded = snapshot == null ? null : new Decoded(snapshot);
			this.cache.put(key, decoded);
		}
		this.lastChunkX = chunkX;
		this.lastChunkZ = chunkZ;
		this.last = decoded;
		return decoded;
	}

	private static final class Decoded {
		final ChunkSnapshot snapshot;
		final char[][] sections;
		final boolean[] decoded;

		Decoded(ChunkSnapshot snapshot) {
			this.snapshot = snapshot;
			this.sections = new char[snapshot.sectionCount()][];
			this.decoded = new boolean[snapshot.sectionCount()];
		}

		char @Nullable [] section(int index) {
			if (index < 0 || index >= this.sections.length) {
				return null;
			}
			if (!this.decoded[index]) {
				this.decoded[index] = true;
				PalettedContainer<BlockState> states = this.snapshot.section(index);
				BlockState uniform = this.snapshot.uniformSection(index);
				if (uniform != null) {
					char[] ids = new char[4096];
					java.util.Arrays.fill(ids, (char) Block.BLOCK_STATE_REGISTRY.getId(uniform));
					this.sections[index] = ids;
				} else if (states != null) {
					char[] ids = new char[4096];
					BlockState lastState = null;
					char lastId = 0;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = states.get(x, y, z);
								if (state != lastState) {
									lastState = state;
									lastId = (char) Block.BLOCK_STATE_REGISTRY.getId(state);
								}
								ids[(y << 8) | (z << 4) | x] = lastId;
							}
						}
					}
					this.sections[index] = ids;
				}
			}
			return this.sections[index];
		}
	}
}
