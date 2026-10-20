package dev.diorama.bake;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
//? if >=26.1 {
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.CardinalLighting;
//?} else {
/*import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
*///?}
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * An empty world to hand a block's tint code, which notes the biome colour the code asks for.
 * Tint code that follows the biome, the game's and mods' alike, asks the world for its grass,
 * foliage or water colour; knowing which, the map blends that biome colour itself.
 */
final class TintProbe implements BlockAndTintGetter {
	private @Nullable ColorResolver asked;

	/** Forgets what the last tint code asked for. */
	void reset() {
		this.asked = null;
	}

	/** Whether the tint code asked for any biome colour, one the map knows or not. */
	boolean askedForBiome() {
		return this.asked != null;
	}

	/** The {@code BlockLooks.TINT_*} kind of the biome colour asked for, or {@code TINT_NONE}. */
	byte kind() {
		ColorResolver resolver = this.asked;
		if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
			return BlockLooks.TINT_GRASS;
		}
		if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
			return BlockLooks.TINT_FOLIAGE;
		}
		//? if >=1.21.5 {
		if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) {
			return BlockLooks.TINT_DRY_FOLIAGE;
		}
		//?}
		if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
			return BlockLooks.TINT_WATER;
		}
		return BlockLooks.TINT_NONE;
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver resolver) {
		if (this.asked == null) {
			this.asked = resolver;
		}
		return 0xFFFFFFFF;
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return Blocks.AIR.defaultBlockState();
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return Fluids.EMPTY.defaultFluidState();
	}

	@Override
	public int getHeight() {
		return 384;
	}

	//? if >=1.21.2 {
	@Override
	public int getMinY() {
		return -64;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return LevelLightEngine.EMPTY;
	}
	//?} else {
	/*@Override
	public int getMinBuildHeight() {
		return -64;
	}

	@Override
	public @Nullable LevelLightEngine getLightEngine() {
		return null;
	}
	*///?}

	//? if >=26.1 {
	@Override
	public CardinalLighting cardinalLighting() {
		return CardinalLighting.DEFAULT;
	}
	//?} else {
	/*@Override
	public float getShade(Direction direction, boolean shade) {
		return 1.0F;
	}
	*///?}
}
