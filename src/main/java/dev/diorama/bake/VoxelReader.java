package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Block and light lookups for one bake job. Decodes each section it touches once into a flat
 * array of block state ids. Not thread-safe; each job gets its own reader.
 *
 * <p>Lookups are the bake's inner loop, so they skip the work they can: a ray stays in one section
 * for several steps, so the last section's ids are kept at hand, and the chunks a job has seen sit
 * in a small table indexed by their coordinates, so moving between neighbouring chunks (shadow
 * and sky rays do, all the time) doesn't hash.
 */
final class VoxelReader {
	static final int UNKNOWN = -1;
	static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
	/** The ids of a section with nothing in it. Shared and never written. */
	private static final char[] AIR_SECTION = new char[4096];
	/** The solid mask of a section with nothing solid in it. Shared and never written. */
	private static final long[] NOTHING_SOLID = new long[64];

	static {
		Arrays.fill(AIR_SECTION, (char) AIR_ID);
	}

	/**
	 * The chunk table holds 2^TABLE_BITS chunks each way, by the low bits of their coordinates.
	 * A bake's reach is narrower than that, so its chunks rarely share a slot; when they do, the
	 * slot is filled again from {@link #cache}.
	 */
	private static final int TABLE_BITS = 5;
	private static final int TABLE_MASK = (1 << TABLE_BITS) - 1;

	private final ChunkSource source;
	/** {@link BlockLooks#kind}, for {@link #solid}; null when only {@link #state} is used. */
	private final byte @Nullable [] kinds;
	private final int minY;
	private final int maxY;
	/**
	 * Every chunk this job has asked the source for, null where it had none. A chunk is asked for
	 * once, so the whole job sees the same blocks even while chunks load and unload.
	 */
	private final Long2ObjectOpenHashMap<@Nullable Decoded> cache = new Long2ObjectOpenHashMap<>();
	private final int[] tableX = new int[1 << (2 * TABLE_BITS)];
	private final int[] tableZ = new int[1 << (2 * TABLE_BITS)];
	private final @Nullable Decoded[] tableChunk = new Decoded[1 << (2 * TABLE_BITS)];
	// The section the last block lookup landed in, in section coordinates, and its ids: null when
	// its chunk is missing.
	private int sectionX = Integer.MIN_VALUE;
	private int sectionY = Integer.MIN_VALUE;
	private int sectionZ = Integer.MIN_VALUE;
	private char @Nullable [] sectionIds;
	// The same for the last solid lookup, with its own section: shadow and sky rays interleave
	// with camera rays. The mask is null when the chunk is missing.
	private int solidX = Integer.MIN_VALUE;
	private int solidY = Integer.MIN_VALUE;
	private int solidZ = Integer.MIN_VALUE;
	private long @Nullable [] solidMask;

	VoxelReader(ChunkSource source) {
		this(source, null);
	}

	VoxelReader(ChunkSource source, byte @Nullable [] kinds) {
		this.source = source;
		this.kinds = kinds;
		this.minY = source.minY();
		this.maxY = source.maxY();
		// No chunk has this x, so every slot starts empty.
		Arrays.fill(this.tableX, Integer.MIN_VALUE);
	}

	static final boolean DEBUG = dev.diorama.Diorama.DEBUG;
	final java.util.Set<Long> missing = DEBUG ? new java.util.HashSet<>() : null;

	/** Block state id at the position, {@link #AIR_ID} outside the world, or {@link #UNKNOWN}. */
	int state(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return AIR_ID;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.sectionX || sy != this.sectionY || sz != this.sectionZ) {
			this.enterSection(sx, sy, sz);
		}
		char[] ids = this.sectionIds;
		return ids == null ? UNKNOWN : ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
	}

	private void enterSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (DEBUG) {
				this.missing.add(ChunkSnapshot.key(sx, sz));
			}
			this.sectionIds = null;
		} else {
			char[] ids = chunk.section(sy - chunk.snapshot.minSectionY);
			this.sectionIds = ids == null ? AIR_SECTION : ids;
		}
		this.sectionX = sx;
		this.sectionY = sy;
		this.sectionZ = sz;
	}

	/**
	 * 1 if the block is {@link BlockLooks#SOLID}, the kind that stops shadow and sky rays and
	 * casts ambient occlusion, 0 if not, or {@link #UNKNOWN}. Reads a bit per block, built once per
	 * section, which keeps far more of the world in the CPU's caches than the ids do.
	 */
	int solid(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.solidX || sy != this.solidY || sz != this.solidZ) {
			this.enterSolidSection(sx, sy, sz);
		}
		long[] mask = this.solidMask;
		if (mask == null) {
			return UNKNOWN;
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		return (int) (mask[i >> 6] >>> i) & 1;
	}

	private void enterSolidSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (DEBUG) {
				this.missing.add(ChunkSnapshot.key(sx, sz));
			}
			this.solidMask = null;
		} else {
			this.solidMask = chunk.solid(sy - chunk.snapshot.minSectionY, this.kinds);
		}
		this.solidX = sx;
		this.solidY = sy;
		this.solidZ = sz;
	}

	/**
	 * One above the highest {@link BlockLooks#SOLID} block in these chunks, or
	 * {@code Integer.MIN_VALUE} if they have none. Chunks missing from the source are left out:
	 * every lookup there is {@link #UNKNOWN}. If this job hasn't asked for one of the chunks yet,
	 * returns {@code Integer.MAX_VALUE} instead of asking, so the blocks a job sees don't depend on
	 * this call.
	 */
	int solidTop(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
		int top = Integer.MIN_VALUE;
		for (int cx = chunkX0; cx <= chunkX1; cx++) {
			for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
				Decoded chunk;
				int slot = ((cx & TABLE_MASK) << TABLE_BITS) | (cz & TABLE_MASK);
				if (this.tableX[slot] == cx && this.tableZ[slot] == cz) {
					chunk = this.tableChunk[slot];
				} else {
					long key = ChunkSnapshot.key(cx, cz);
					if (!this.cache.containsKey(key)) {
						return Integer.MAX_VALUE;
					}
					chunk = this.cache.get(key);
				}
				if (chunk != null) {
					top = Math.max(top, chunk.solidTop(this.kinds));
				}
			}
		}
		return top;
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
		int slot = ((chunkX & TABLE_MASK) << TABLE_BITS) | (chunkZ & TABLE_MASK);
		if (this.tableX[slot] == chunkX && this.tableZ[slot] == chunkZ) {
			return this.tableChunk[slot];
		}
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		Decoded decoded = this.cache.get(key);
		if (decoded == null && !this.cache.containsKey(key)) {
			ChunkSnapshot snapshot = this.source.chunk(chunkX, chunkZ);
			decoded = snapshot == null ? null : new Decoded(snapshot);
			this.cache.put(key, decoded);
		}
		this.tableX[slot] = chunkX;
		this.tableZ[slot] = chunkZ;
		this.tableChunk[slot] = decoded;
		return decoded;
	}

	private static final class Decoded {
		final ChunkSnapshot snapshot;
		final char[][] sections;
		final boolean[] decoded;
		/** Per section, one bit per block, set where it is {@link BlockLooks#SOLID}; built when first read. */
		final long[][] solid;
		/** See {@link VoxelReader#solidTop}; {@code Integer.MAX_VALUE} until worked out. */
		int solidTop = Integer.MAX_VALUE;

		Decoded(ChunkSnapshot snapshot) {
			this.snapshot = snapshot;
			this.sections = new char[snapshot.sectionCount()][];
			this.decoded = new boolean[snapshot.sectionCount()];
			this.solid = new long[snapshot.sectionCount()][];
		}

		/** The section's solid mask, indexed as its ids. */
		long[] solid(int index, byte[] kinds) {
			if (index < 0 || index >= this.solid.length) {
				return NOTHING_SOLID;
			}
			long[] mask = this.solid[index];
			if (mask == null) {
				char[] ids = this.section(index);
				if (ids == null) {
					mask = NOTHING_SOLID;
				} else {
					mask = new long[64];
					for (int i = 0; i < 4096; i++) {
						if (kinds[ids[i]] == BlockLooks.SOLID) {
							mask[i >> 6] |= 1L << i;
						}
					}
				}
				this.solid[index] = mask;
			}
			return mask;
		}

		int solidTop(byte[] kinds) {
			if (this.solidTop == Integer.MAX_VALUE) {
				int top = Integer.MIN_VALUE;
				for (int index = this.sections.length - 1; index >= 0 && top == Integer.MIN_VALUE; index--) {
					long[] mask = this.solid(index, kinds);
					for (int word = 63; word >= 0; word--) {
						if (mask[word] != 0) {
							// Bits run x, then z, then y: 64 bits are a quarter of one y layer.
							int highest = (word << 6) + 63 - Long.numberOfLeadingZeros(mask[word]);
							top = ((this.snapshot.minSectionY + index) << 4) + (highest >> 8) + 1;
							break;
						}
					}
				}
				this.solidTop = top;
			}
			return this.solidTop;
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
