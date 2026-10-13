package dev.orthomap.data;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

/**
 * A private copy of one chunk's blocks, light and biome tints, safe to read from bake threads.
 * Taken on the client thread; never mutated afterwards.
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
	/** Per-column biome tints, indexed {@code (z << 4) | x}. */
	public final int[] grass = new int[256];
	public final int[] foliage = new int[256];
	public final int[] dryFoliage = new int[256];
	public final int[] water = new int[256];
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
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = this.chunkX << 4;
		int baseZ = this.chunkZ << 4;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int solid = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				top = Math.max(top, surface);
				floor = Math.min(floor, solid);
				pos.set(baseX + x, surface, baseZ + z);
				int i = (z << 4) | x;
				this.grass[i] = BiomeColors.getAverageGrassColor(level, pos);
				this.foliage[i] = BiomeColors.getAverageFoliageColor(level, pos);
				this.dryFoliage[i] = BiomeColors.getAverageDryFoliageColor(level, pos);
				this.water[i] = BiomeColors.getAverageWaterColor(level, pos);
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

	public long key() {
		return key(this.chunkX, this.chunkZ);
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
