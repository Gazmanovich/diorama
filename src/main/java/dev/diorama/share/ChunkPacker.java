package dev.diorama.share;

import dev.diorama.data.ChunkTags;
import dev.diorama.data.Nbt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;
//? if <1.21.2 {
/*import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
*///?}

/**
 * Packs a chunk for a player's shared map: only the sections the view from the sky can reach, from
 * the lowest ground down to how far water and overhangs can hide it ({@link ChunkTags#summaryOf}) up
 * to the highest block, with their biomes and block light for night. Sky light stays out: only a
 * view from under a roof uses it. So do the height maps, which follow from the blocks.
 *
 * <p>A block with an opaque full block on all six sides can't show from the sky, so it goes out as
 * stone (end stone in the End): the picture is the same and the sections can't be read for ores, as
 * Paper's anti-xray does for chunk packets. Blocks at the chunk's edge are judged with the chunks
 * next to it; where there's none, they count as open.
 *
 * <p>Deflated NBT, in the save's own layout, so the client reads it as it reads a save. The share
 * worker's own: one thread at a time.
 */
final class ChunkPacker {
	/** As {@code TallChunks.ORDINARY_TOP}: a build over a whole chunk higher than this hides nothing. */
	private static final int ORDINARY_TOP = 320;
	private static final int CACHED_SECTIONS = 8192;
	/** A section's 4096 blocks, one bit each, indexed {@code (y << 8) | (z << 4) | x}: whether each is an opaque full block. */
	private static final long[] OPEN = new long[64];

	private final Level level;
	private final ChunkTags tags;
	private final BlockState hidden;
	/** Reads a neighbouring chunk's tag, or null if there's none: loaded or saved. */
	private final BiFunction<Integer, Integer, @Nullable CompoundTag> neighbours;
	private final Map<BlockState, Boolean> opaque = new IdentityHashMap<>();
	/** Opacity of sections seen lately, by {@link #sectionKey}, for the chunks next to them. */
	private final Map<Long, long[]> sections = new LinkedHashMap<>(CACHED_SECTIONS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
			return this.size() > CACHED_SECTIONS;
		}
	};
	/** Chunks next door read lately, by section, as most of their sections are asked for one after another. */
	private final Map<Long, Map<Integer, CompoundTag>> neighbourTags = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Map<Integer, CompoundTag>> eldest) {
			return this.size() > 32;
		}
	};
	/** Chunks next door with no tag, so they aren't asked for again for a while. */
	private final Map<Long, Boolean> missing = new LinkedHashMap<>(1024, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
			return this.size() > 4096;
		}
	};
	long hiddenBlocks;
	/** When the neighbours kept were first read: chunks change, so they're read again after a while. */
	private long keptSince = System.currentTimeMillis();
	private static final long KEEP_MS = 30_000;

	ChunkPacker(Level level, ChunkTags tags, BiFunction<Integer, Integer, @Nullable CompoundTag> neighbours) {
		this.level = level;
		this.tags = tags;
		this.neighbours = neighbours;
		this.hidden = level.dimension() == Level.END ? Blocks.END_STONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
	}

	/** Forgets the neighbours read more than {@link #KEEP_MS} ago, which may have changed since. */
	void forgetIfOld() {
		long now = System.currentTimeMillis();
		if (now - this.keptSince > KEEP_MS) {
			this.sections.clear();
			this.neighbourTags.clear();
			this.missing.clear();
			this.keptSince = now;
		}
	}

	/** The chunk packed, or null if it has nothing finished to draw. {@code tag} is brought up to this version. */
	byte @Nullable [] pack(int chunkX, int chunkZ, CompoundTag tag) {
		if (!ChunkTags.finished(tag)) {
			return null;
		}
		int[] surface = this.tags.surface(tag);
		int[] solid = this.tags.solid(tag);
		if (surface == null || solid == null) {
			return null;
		}
		ChunkTags.Summary summary = ChunkTags.summaryOf(surface, solid);
		if (summary.maxY() <= this.level.getMinY()) {
			return null;
		}
		int minSolid = summary.floorY() + 24;
		int floorY = minSolid > ORDINARY_TOP ? this.level.getMinY() : summary.floorY();
		int minSection = this.level.getMinSectionY();
		int maxSection = minSection + this.level.getSectionsCount() - 1;
		int lo = Math.max(minSection, Math.floorDiv(floorY, 16));
		int hi = Math.min(maxSection, Math.floorDiv(summary.maxY(), 16));

		Map<Integer, CompoundTag> byY = sectionsByY(tag);
		Map<Integer, PalettedContainer<BlockState>> states = new HashMap<>();
		Map<Integer, long[]> opacity = new HashMap<>();
		for (int y = lo - 1; y <= hi + 1; y++) {
			CompoundTag section = byY.get(y);
			PalettedContainer<BlockState> blocks = section == null ? null : this.tags.blockStates(section);
			if (blocks != null) {
				states.put(y, blocks);
			}
			long[] bits = blocks == null ? OPEN : this.opacity(blocks);
			opacity.put(y, bits);
			if (y >= lo && y <= hi) {
				this.sections.put(sectionKey(chunkX, chunkZ, y), bits);
			}
		}
		for (int y = lo; y <= hi; y++) {
			PalettedContainer<BlockState> blocks = states.get(y);
			if (blocks != null) {
				this.hide(chunkX, chunkZ, y, blocks, opacity);
			}
		}

		CompoundTag out = new CompoundTag();
		out.putInt("DataVersion", ChunkTags.dataVersion());
		out.putString("Status", "minecraft:full");
		out.putInt("xPos", chunkX);
		out.putInt("zPos", chunkZ);
		// No height maps: the client works them out from the blocks, which saves about half a kilobyte.
		ListTag sections = new ListTag();
		for (Map.Entry<Integer, CompoundTag> found : new java.util.TreeMap<>(byY).entrySet()) {
			int y = found.getKey();
			CompoundTag section = found.getValue();
			CompoundTag kept = new CompoundTag();
			kept.putByte("Y", (byte) y);
			// Biomes for every section: a neighbour's tint blends with them at its own height, which
			// can be above or below this chunk's blocks. Blocks and light only where the view reaches.
			CompoundTag biomes = Nbt.compound(section, "biomes");
			if (!biomes.isEmpty()) {
				kept.put("biomes", biomes);
			}
			if (y >= lo && y <= hi) {
				PalettedContainer<BlockState> blocks = states.get(y);
				if (blocks != null) {
					Tag encoded = this.tags.blockStates().encodeStart(NbtOps.INSTANCE, blocks).result().orElse(null);
					if (encoded != null) {
						kept.put("block_states", encoded);
					}
				}
				byte[] light = Nbt.bytes(section, "BlockLight");
				if (light != null) {
					kept.putByteArray("BlockLight", light);
				}
			}
			sections.add(kept);
		}
		out.put("sections", sections);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
		Deflater deflater = new Deflater(6);
		try (DataOutputStream data = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
			NbtIo.write(out, data);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deflater.end();
		}
		return bytes.toByteArray();
	}

	/** Turns blocks no ray from the sky can reach into {@link #hidden}. */
	private void hide(int chunkX, int chunkZ, int sectionY, PalettedContainer<BlockState> blocks, Map<Integer, long[]> opacity) {
		long[] self = opacity.get(sectionY);
		long[] below = opacity.get(sectionY - 1);
		long[] above = opacity.get(sectionY + 1);
		long[] west = null;
		long[] east = null;
		long[] north = null;
		long[] south = null;
		// Any block, opaque or not: a chest or spawner closed in stone is as hidden as an ore.
		for (int i = 0; i < 4096; i++) {
			int x = i & 15;
			int z = (i >> 4) & 15;
			int y = i >> 8;
			if (!(y == 0 ? bit(below, i | (15 << 8)) : bit(self, i - 256)) || !(y == 15 ? bit(above, i & 255) : bit(self, i + 256))) {
				continue;
			}
			if (x == 0) {
				west = west != null ? west : this.neighbour(chunkX - 1, chunkZ, sectionY);
				if (!bit(west, i | 15)) {
					continue;
				}
			} else if (!bit(self, i - 1)) {
				continue;
			}
			if (x == 15) {
				east = east != null ? east : this.neighbour(chunkX + 1, chunkZ, sectionY);
				if (!bit(east, i & ~15)) {
					continue;
				}
			} else if (!bit(self, i + 1)) {
				continue;
			}
			if (z == 0) {
				north = north != null ? north : this.neighbour(chunkX, chunkZ - 1, sectionY);
				if (!bit(north, i | (15 << 4))) {
					continue;
				}
			} else if (!bit(self, i - 16)) {
				continue;
			}
			if (z == 15) {
				south = south != null ? south : this.neighbour(chunkX, chunkZ + 1, sectionY);
				if (!bit(south, i & ~(15 << 4))) {
					continue;
				}
			} else if (!bit(self, i + 16)) {
				continue;
			}
			if (blocks.get(x, y, z) != this.hidden) {
				blocks.getAndSetUnchecked(x, y, z, this.hidden);
				this.hiddenBlocks++;
			}
		}
	}

	private static boolean bit(long[] bits, int i) {
		return (bits[i >> 6] & (1L << (i & 63))) != 0;
	}

	/** A neighbouring chunk's section's opacity, all open if there's no such chunk. */
	private long[] neighbour(int chunkX, int chunkZ, int sectionY) {
		long key = sectionKey(chunkX, chunkZ, sectionY);
		long[] cached = this.sections.get(key);
		if (cached != null) {
			return cached;
		}
		long chunk = sectionKey(chunkX, chunkZ, 0);
		Map<Integer, CompoundTag> byY = this.neighbourTags.get(chunk);
		if (byY == null) {
			if (this.missing.containsKey(chunk)) {
				return OPEN;
			}
			CompoundTag tag = this.neighbours.apply(chunkX, chunkZ);
			if (tag == null || !ChunkTags.finished(tag)) {
				this.missing.put(chunk, Boolean.TRUE);
				return OPEN;
			}
			byY = sectionsByY(tag);
			this.neighbourTags.put(chunk, byY);
		}
		CompoundTag section = byY.get(sectionY);
		PalettedContainer<BlockState> blocks = section == null ? null : this.tags.blockStates(section);
		long[] bits = blocks == null ? OPEN : this.opacity(blocks);
		this.sections.put(key, bits);
		return bits;
	}

	private long[] opacity(PalettedContainer<BlockState> blocks) {
		long[] bits = new long[64];
		for (int i = 0; i < 4096; i++) {
			if (this.opaque(blocks.get(i & 15, i >> 8, (i >> 4) & 15))) {
				bits[i >> 6] |= 1L << (i & 63);
			}
		}
		return bits;
	}

	private boolean opaque(BlockState state) {
		Boolean known = this.opaque.get(state);
		if (known == null) {
			//? if >=1.21.2 {
			known = state.isSolidRender();
			//?} else {
			/*known = state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			*///?}
			this.opaque.put(state, known);
		}
		return known;
	}

	private static Map<Integer, CompoundTag> sectionsByY(CompoundTag tag) {
		Map<Integer, CompoundTag> byY = new HashMap<>();
		ListTag sections = Nbt.list(tag, "sections");
		for (int s = 0; s < sections.size(); s++) {
			CompoundTag section = Nbt.compound(sections, s);
			byY.put(Nbt.sectionY(section), section);
		}
		return byY;
	}

	private static long sectionKey(int chunkX, int chunkZ, int sectionY) {
		return ((long) (chunkX & 0x3FFFFF) << 30) | ((long) (chunkZ & 0x3FFFFF) << 8) | (sectionY & 0xFF);
	}
}
