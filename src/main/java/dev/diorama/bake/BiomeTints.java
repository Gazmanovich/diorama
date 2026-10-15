package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Blends a snapshot's biome tints on a bake thread, the way the game blends them for its own
 * blocks: each column's tint is the average of the biome colors over the blend radius around
 * its top block, with biomes looked up through the same fuzzed zoom. Blending on the client
 * thread took most of its time while exploring, so snapshots only copy the biomes.
 */
public final class BiomeTints {
	private static final ColorResolver[] RESOLVERS = {
		BiomeColors.GRASS_COLOR_RESOLVER,
		BiomeColors.FOLIAGE_COLOR_RESOLVER,
		BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
		BiomeColors.WATER_COLOR_RESOLVER,
	};

	private static final AtomicInteger REBLENDS = new AtomicInteger();

	private BiomeTints() {
	}

	/** How many times tints were blended again because a missing neighbour arrived. For tests. */
	public static int reblends() {
		return REBLENDS.get();
	}

	/**
	 * The snapshot's tints, blended now if no bake has needed them yet, or blended again if a
	 * neighbouring chunk the last blend had to do without has arrived since. Any thread.
	 */
	public static ChunkSnapshot.Tints of(ChunkSnapshot snapshot, ChunkSource source) {
		ChunkSnapshot.Tints tints = snapshot.tints();
		// Only arrivals count: a neighbour trimmed from the map since took nothing from the blend.
		if (tints == null || tints.missing() != 0 && (tints.missing() & ~missing(snapshot, source)) != 0) {
			if (tints != null) {
				REBLENDS.incrementAndGet();
			}
			tints = blend(snapshot, source);
			snapshot.setTints(tints);
		}
		return tints;
	}

	/** Bit for the neighbouring chunk at this offset, in {@link ChunkSnapshot.Tints#missing()}. */
	public static int neighbourBit(int dx, int dz) {
		return 1 << ((dx + 1) * 3 + dz + 1);
	}

	/** The neighbouring chunks the source has no snapshot of, as {@link #neighbourBit} bits. */
	private static int missing(ChunkSnapshot snapshot, ChunkSource source) {
		int missing = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && source.chunk(snapshot.chunkX + dx, snapshot.chunkZ + dz) == null) {
					missing |= neighbourBit(dx, dz);
				}
			}
		}
		return missing;
	}

	/** Blends the snapshot's tints from the snapshots the source has now. Any thread. */
	public static ChunkSnapshot.Tints blend(ChunkSnapshot snapshot, ChunkSource source) {
		// Checked before blending, so a neighbour arriving partway through counts as missing and
		// gets blended in next time.
		int missing = missing(snapshot, source);
		// Biomes in neighbouring chunks come from their snapshots. Where the map has none yet, the
		// game's fallback biome stands in until it arrives; see of().
		BiomeManager biomes = new BiomeManager((quartX, quartY, quartZ) -> {
			int chunkX = quartX >> 2;
			int chunkZ = quartZ >> 2;
			ChunkSnapshot chunk = chunkX == snapshot.chunkX && chunkZ == snapshot.chunkZ ? snapshot : source.chunk(chunkX, chunkZ);
			return chunk == null ? snapshot.fallbackBiome : chunk.noiseBiome(quartX, quartY, quartZ);
		}, snapshot.biomeZoomSeed);

		int[][] colors = new int[RESOLVERS.length][256];
		int radius = snapshot.blendRadius;
		int count = (radius * 2 + 1) * (radius * 2 + 1);
		int[] red = new int[RESOLVERS.length];
		int[] green = new int[RESOLVERS.length];
		int[] blue = new int[RESOLVERS.length];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int bx = (snapshot.chunkX << 4) + x;
				int bz = (snapshot.chunkZ << 4) + z;
				int y = snapshot.surface(x, z);
				java.util.Arrays.fill(red, 0);
				java.util.Arrays.fill(green, 0);
				java.util.Arrays.fill(blue, 0);
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dx = -radius; dx <= radius; dx++) {
						Biome biome = biomes.getBiome(pos.set(bx + dx, y, bz + dz)).value();
						for (int r = 0; r < RESOLVERS.length; r++) {
							int color = RESOLVERS[r].getColor(biome, bx + dx, bz + dz);
							red[r] += ARGB.red(color);
							green[r] += ARGB.green(color);
							blue[r] += ARGB.blue(color);
						}
					}
				}
				int i = (z << 4) | x;
				for (int r = 0; r < RESOLVERS.length; r++) {
					colors[r][i] = ARGB.color(red[r] / count, green[r] / count, blue[r] / count) & 0xFFFFFF;
				}
			}
		}
		return new ChunkSnapshot.Tints(colors[0], colors[1], colors[2], colors[3], missing);
	}
}
