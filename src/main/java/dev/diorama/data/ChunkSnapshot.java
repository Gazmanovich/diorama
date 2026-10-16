package dev.diorama.data;

import dev.diorama.mixin.BiomeManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

/**
 * A private copy of one chunk's blocks, light and biomes, safe to read from bake threads.
 * Taken on the client thread, which only copies; the biome tints are blended later, on the bake
 * thread that first needs them (see {@code BiomeTints}).
 */
public final class ChunkSnapshot {
	public final int chunkX;
	public final int chunkZ;
	public final int minSectionY;
	/** Block states per section, or null for sections that are only air or a single block. */
	private final @Nullable PalettedContainer<BlockState>[] states;
	/** The one block filling a section, for sections made of a single block. */
	private final @Nullable BlockState[] uniform;
	private final boolean hasSkyLight;
	private final @Nullable DataLayer[] blockLight;
	private final @Nullable DataLayer[] skyLight;
	/**
	 * How far from a column its blended tint reads biomes: the game's largest blend radius, 7, plus
	 * the 5 blocks its biome lookup fuzzes positions by. Never further than a neighbouring chunk.
	 */
	public static final int BIOME_REACH = 12;
	/**
	 * Biome sections of this chunk and its eight neighbours as the game had them when the snapshot
	 * was taken, indexed {@code (dx + 1) * 3 + dz + 1}; null where the game had no chunk.
	 */
	private final @Nullable PalettedContainerRO<Holder<Biome>>[] @Nullable [] biomes;
	/** Top block y per column, indexed {@code (z << 4) | x}: where the column's tints are sampled. */
	private final int[] surface = new int[256];
	/** The hashed seed the client's biome lookups fuzz positions with. */
	public final long biomeZoomSeed;
	/** The player's biome blend radius when the snapshot was taken. */
	public final int blendRadius;
	/** The biome the game assumes where no chunk is loaded. */
	public final Holder<Biome> fallbackBiome;
	/** Blended biome tints, worked out on a bake thread the first time a bake needs them. */
	private volatile @Nullable Tints tints;
	/** Highest non-air block y, or {@code Integer.MIN_VALUE} when the chunk is empty. */
	public final int maxY;
	/** A conservative low point of the visible surface, used to bound tile invalidation. */
	public final int floorY;

	@SuppressWarnings("unchecked")
	private ChunkSnapshot(LevelChunk chunk, ClientLevel level) {
		this.chunkX = chunk.getPos().x();
		this.chunkZ = chunk.getPos().z();
		this.minSectionY = chunk.getMinSectionY();
		LevelChunkSection[] sections = chunk.getSections();
		this.states = new PalettedContainer[sections.length];
		this.uniform = new BlockState[sections.length];
		// The game never writes into a section's biome container: new biomes arrive in a new one,
		// swapped in. So holding the containers keeps the biomes as they are now, without copying.
		this.biomes = new PalettedContainerRO[9][];
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkAccess around = dx == 0 && dz == 0 ? chunk : level.getChunkSource().getChunk(this.chunkX + dx, this.chunkZ + dz, ChunkStatus.BIOMES, false);
				if (around != null) {
					LevelChunkSection[] aroundSections = around.getSections();
					PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[aroundSections.length];
					for (int i = 0; i < aroundSections.length; i++) {
						biomes[i] = aroundSections[i] == null ? null : aroundSections[i].getBiomes();
					}
					this.biomes[(dx + 1) * 3 + dz + 1] = biomes;
				}
			}
		}
		this.biomeZoomSeed = ((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed();
		this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
		this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		this.hasSkyLight = level.dimensionType().hasSkyLight();
		this.blockLight = new DataLayer[sections.length];
		this.skyLight = new DataLayer[sections.length];
		LevelLightEngine light = level.getLightEngine();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section != null && !section.hasOnlyAir()) {
				PalettedContainer<BlockState> states = section.getStates();
				// A single-block section's palette copies as itself, and a later chunk packet can
				// rewrite it in place. Keep just the block so the snapshot never changes under a bake.
				BlockState single = Sections.singleValue(states);
				if (single != null) {
					this.uniform[i] = single;
				} else {
					this.states[i] = states.copy();
				}
			}
			SectionPos pos = SectionPos.of(this.chunkX, this.minSectionY + i, this.chunkZ);
			DataLayer block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
			DataLayer sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
			this.blockLight[i] = block == null ? null : block.copy();
			this.skyLight[i] = sky == null ? null : sky.copy();
		}

		int top = Integer.MIN_VALUE;
		int floor = Integer.MAX_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int solid = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				top = Math.max(top, surface);
				floor = Math.min(floor, solid);
				this.surface[(z << 4) | x] = surface;
			}
		}
		this.maxY = top <= chunk.getMinY() ? Integer.MIN_VALUE : top;
		// Water and shallow overhangs can hide blocks below the motion-blocking height.
		this.floorY = floor == Integer.MAX_VALUE ? chunk.getMinY() : floor - 24;
	}

	public static ChunkSnapshot take(LevelChunk chunk, ClientLevel level) {
		return new ChunkSnapshot(chunk, level);
	}

	public int sectionCount() {
		return this.states.length;
	}

	public @Nullable PalettedContainer<BlockState> section(int index) {
		return this.states[index];
	}

	public @Nullable BlockState uniformSection(int index) {
		return this.uniform[index];
	}

	public int blockLight(int sectionIndex, int x, int y, int z) {
		DataLayer layer = this.blockLight[sectionIndex];
		return layer == null ? 0 : layer.get(x, y, z);
	}

	public int skyLight(int sectionIndex, int x, int y, int z) {
		if (!this.hasSkyLight) {
			return 0;
		}
		DataLayer layer = this.skyLight[sectionIndex];
		if (layer != null) {
			return layer.get(x, y, z);
		}
		// Same as vanilla: a section without stored sky light takes the bottom layer of the
		// nearest stored section above it, and is fully lit if there is none.
		for (int i = sectionIndex + 1; i < this.skyLight.length; i++) {
			if (this.skyLight[i] != null) {
				return this.skyLight[i].get(x, 0, z);
			}
		}
		return 15;
	}

	/**
	 * The biome at a quart (4-block) position in this chunk or a neighbouring one, as the game had
	 * it when the snapshot was taken: y clamped to the world's height like the game's own chunk
	 * lookup, and the game's fallback biome where it had no chunk.
	 */
	public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
		int dx = (quartX >> 2) - this.chunkX;
		int dz = (quartZ >> 2) - this.chunkZ;
		PalettedContainerRO<Holder<Biome>>[] sections = dx < -1 || dx > 1 || dz < -1 || dz > 1 ? null : this.biomes[(dx + 1) * 3 + dz + 1];
		if (sections == null) {
			return this.fallbackBiome;
		}
		int minQuart = this.minSectionY << 2;
		int y = Mth.clamp(quartY, minQuart, minQuart + (sections.length << 2) - 1);
		PalettedContainerRO<Holder<Biome>> section = sections[(y >> 2) - this.minSectionY];
		return section == null ? this.fallbackBiome : section.get(quartX & 3, y & 3, quartZ & 3);
	}

	/** Whether the game had the neighbouring chunk at this offset when the snapshot was taken. */
	public boolean hasBiomesAround(int dx, int dz) {
		return this.biomes[(dx + 1) * 3 + dz + 1] != null;
	}

	/** The column's top block y, where its tints are sampled. */
	public int surface(int x, int z) {
		return this.surface[((z & 15) << 4) | (x & 15)];
	}

	public @Nullable Tints tints() {
		return this.tints;
	}

	public void setTints(Tints tints) {
		this.tints = tints;
	}

	/** Blended biome tints per column, as 0xRRGGBB, indexed {@code (z << 4) | x}. */
	public record Tints(int[] grass, int[] foliage, int[] dryFoliage, int[] water) {
	}

	public long key() {
		return key(this.chunkX, this.chunkZ);
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
