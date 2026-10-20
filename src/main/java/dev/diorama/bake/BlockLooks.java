package dev.diorama.bake;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.NaturalSpawner;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
//? if >=26.1 {
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.FluidModel;
//?} else if >=1.21.5 {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
*///?} else {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import org.jspecify.annotations.Nullable;
*///?}
//? if <26.1 && fabric {
/*import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.loader.api.FabricLoader;
*///?} else if <26.1 && >=1.21.11 {
/*import net.minecraft.data.AtlasIds;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?} else if <26.1 {
/*import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?}
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if >=26.1 {
import net.minecraft.client.resources.model.geometry.BakedQuad;
//?} else {
/*import net.minecraft.client.renderer.block.model.BakedQuad;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * What each block state looks like on the map: how rays treat it, and its average top and
 * front colors taken from the game's own textures. Built once on the client thread, then
 * read-only, so bake threads can use it freely.
 */
public final class BlockLooks {
	public static final byte AIR = 0;
	public static final byte SOLID = 1;
	public static final byte WATER = 2;
	public static final byte LAVA = 3;
	/** See-through blocks such as glass: rays pass and pick up a tint. */
	public static final byte GLASS = 4;
	/** Plants without collision: rays pass and pick up partial color. */
	public static final byte PLANT = 5;

	public static final byte TINT_NONE = 0;
	public static final byte TINT_GRASS = 1;
	public static final byte TINT_FOLIAGE = 2;
	public static final byte TINT_DRY_FOLIAGE = 3;
	public static final byte TINT_WATER = 4;

	//? if >=1.21.5 {
	private static final Set<Block> GRASS_TINTED = Set.of(
		Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
		Blocks.BUSH, Blocks.POTTED_FERN, Blocks.SUGAR_CANE, Blocks.PINK_PETALS, Blocks.WILDFLOWERS
	);
	//?} else {
	/*// Bushes and wildflowers came in 1.21.5.
	private static final Set<Block> GRASS_TINTED = Set.of(
		Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
		Blocks.POTTED_FERN, Blocks.SUGAR_CANE, Blocks.PINK_PETALS
	);
	*///?}
	private static final Set<Block> FOLIAGE_TINTED = Set.of(
		Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
		Blocks.VINE, Blocks.MANGROVE_LEAVES
	);
	private static final Set<Block> WATER_TINTED = Set.of(Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.WATER_CAULDRON);

	private static volatile BlockLooks current;
	/** The models the current table was built from; a resource reload replaces them. */
	private static Object builtFrom;

	public final byte[] kind;
	public final byte[] tint;
	public final byte[] emission;
	/** Untinted part of the top color, as 0xRRGGBB. */
	public final int[] top;
	/** Part of the top color that gets multiplied by the biome tint. */
	public final int[] topTinted;
	public final float[] topTintWeight;
	public final int[] side;
	public final int[] sideTinted;
	public final float[] sideTintWeight;
	/** Fixed tint for blocks whose tint doesn't depend on the biome, or -1. */
	public final int[] constantTint;
	/** Whether a monster can spawn standing on this block (not on leaves or glass, say). */
	public final boolean[] spawnOn;
	/**
	 * Whether a monster's body fits in this block's space: Minecraft's empty-spawn-block rule, and
	 * no collision at all, since a slab at head height blocks a zombie too.
	 */
	public final boolean[] spawnIn;

	private BlockLooks(int size) {
		this.kind = new byte[size];
		this.tint = new byte[size];
		this.emission = new byte[size];
		this.top = new int[size];
		this.topTinted = new int[size];
		this.topTintWeight = new float[size];
		this.side = new int[size];
		this.sideTinted = new int[size];
		this.sideTintWeight = new float[size];
		this.constantTint = new int[size];
		this.spawnOn = new boolean[size];
		this.spawnIn = new boolean[size];
	}

	public static BlockLooks get() {
		return current;
	}

	/**
	 * True when two states draw the same on the map. Leaves changing their distance property,
	 * for example, is a block update nobody can see.
	 */
	public boolean sameLook(int a, int b) {
		return this.kind[a] == this.kind[b]
			&& this.top[a] == this.top[b]
			&& this.topTinted[a] == this.topTinted[b]
			&& this.side[a] == this.side[b]
			&& this.sideTinted[a] == this.sideTinted[b]
			&& this.topTintWeight[a] == this.topTintWeight[b]
			&& this.sideTintWeight[a] == this.sideTintWeight[b]
			&& this.tint[a] == this.tint[b]
			&& this.constantTint[a] == this.constantTint[b]
			&& this.emission[a] == this.emission[b]
			// Spawn spots show on the map too: a slab becoming a double slab changes them.
			&& this.spawnOn[a] == this.spawnOn[b]
			&& this.spawnIn[a] == this.spawnIn[b];
	}

	public static void invalidate() {
		current = null;
	}

	/** True when resource packs were reloaded since the table was built. */
	public static boolean isStale() {
		//? if >=26.1 {
		return current != null && builtFrom != Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		//?} else {
		/*return current != null && builtFrom != Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
		*///?}
	}

	/** Builds the table. Must run on the client thread, after models are loaded. */
	public static BlockLooks build() {
		long start = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		//? if >=26.1 {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		//?} else {
		/*BlockModelShaper models = mc.getModelManager().getBlockModelShaper();
		*///?}
		BlockColors colors = mc.getBlockColors();
		int size = Block.BLOCK_STATE_REGISTRY.size();
		BlockLooks looks = new BlockLooks(size);
		Map<TextureAtlasSprite, float[]> spriteCache = new IdentityHashMap<>();
		RandomSource random = RandomSource.create(42L);
		//? if >=26.1 {
		List<BlockStateModelPart> parts = new ArrayList<>();
		//?} else if >=1.21.5 {
		/*List<BlockModelPart> parts = new ArrayList<>();
		*///?}

		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.BLOCK_STATE_REGISTRY.getId(state);
			if (id < 0 || id >= size) {
				continue;
			}
			looks.emission[id] = (byte) state.getLightEmission();
			looks.constantTint[id] = -1;
			// Minecraft's own spawn rules, asked of the block alone: none of them look around it.
			try {
				looks.spawnOn[id] = state.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, EntityTypes.ZOMBIE);
				looks.spawnIn[id] = NaturalSpawner.isValidEmptySpawnBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, state.getFluidState(), EntityTypes.ZOMBIE)
					&& state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
			} catch (RuntimeException e) {
				looks.spawnOn[id] = false;
				looks.spawnIn[id] = false;
			}
			byte kind = classify(state);
			looks.kind[id] = kind;
			if (kind == AIR) {
				continue;
			}

			//? if >=26.1 {
			parts.clear();
			random.setSeed(42L);
			BlockStateModel model = models.get(state);
			model.collectParts(random, parts);
			//?} else if >=1.21.5 {
			/*parts.clear();
			random.setSeed(42L);
			BlockStateModel model = models.getBlockModel(state);
			model.collectParts(random, parts);
			*///?} else {
			/*BakedModel model = models.getBlockModel(state);
			List<LegacyPart> parts = List.of(new LegacyPart(model, state, random));
			*///?}
			FaceColor topFace = new FaceColor();
			FaceColor sideFace = new FaceColor();
			//? if >=26.1 {
			for (BlockStateModelPart part : parts) {
			//?} else if >=1.21.5 {
			/*for (BlockModelPart part : parts) {
			*///?} else {
			/*for (LegacyPart part : parts) {
			*///?}
				accumulate(topFace, part.getQuads(Direction.UP), spriteCache);
				accumulate(sideFace, part.getQuads(Direction.SOUTH), spriteCache);
				if (sideFace.isEmpty()) {
					accumulate(sideFace, part.getQuads(Direction.NORTH), spriteCache);
				}
				// Cross-shaped plants and other unculled geometry.
				List<BakedQuad> loose = part.getQuads(null);
				if (!loose.isEmpty()) {
					accumulateLoose(topFace, sideFace, loose, spriteCache);
				}
			}
			if (topFace.isEmpty() && sideFace.isEmpty()) {
				// Block entities (chests, signs, beds) have no block model; use the particle texture.
				//? if >=26.1 {
				float[] avg = spriteAverage(model.particleMaterial().sprite(), spriteCache);
				//?} else {
				/*float[] avg = spriteAverage(model.particleIcon(), spriteCache);
				*///?}
				topFace.add(avg, false);
				sideFace.add(avg, false);
			}
			if (topFace.isEmpty()) {
				topFace = sideFace;
			}
			if (sideFace.isEmpty()) {
				sideFace = topFace;
			}
			looks.top[id] = topFace.untinted();
			looks.topTinted[id] = topFace.tinted();
			looks.topTintWeight[id] = topFace.tintWeight();
			looks.side[id] = sideFace.untinted();
			looks.sideTinted[id] = sideFace.tinted();
			looks.sideTintWeight[id] = sideFace.tintWeight();

			if (kind == SOLID && !state.is(BlockTags.LEAVES) && topFace.coverage() < 0.55F) {
				looks.kind[id] = GLASS;
			}

			Block block = state.getBlock();
			if (GRASS_TINTED.contains(block)) {
				looks.tint[id] = TINT_GRASS;
			} else if (FOLIAGE_TINTED.contains(block)) {
				looks.tint[id] = TINT_FOLIAGE;
			//? if >=1.21.5 {
			} else if (block == Blocks.LEAF_LITTER) {
				looks.tint[id] = TINT_DRY_FOLIAGE;
			//?}
			} else if (WATER_TINTED.contains(block)) {
				looks.tint[id] = TINT_WATER;
			} else if (block == Blocks.LILY_PAD) {
				//? if >=1.21.4 {
				looks.constantTint[id] = BlockColors.LILY_PAD_IN_WORLD & 0xFFFFFF;
				//?} else {
				/*// The constant came in 1.21.4, with this value.
				looks.constantTint[id] = -14647248 & 0xFFFFFF;
				*///?}
			} else {
				//? if >=26.1 {
				BlockTintSource source = colors.getTintSource(state, 0);
				if (source == null) {
					source = colors.getTintSource(state, 1);
				}
				if (source != null) {
					looks.constantTint[id] = source.color(state) & 0xFFFFFF;
				}
				//?} else {
				/*// One colour function per block covers all its tint layers. Without a world or
				// position it gives the block's colour out of the world, as BlockTintSource.color
				// does from 26.1; -1 means no function is registered. A mod's function may not
				// expect the missing world, so a failure counts as no tint.
				try {
					int color = colors.getColor(state, null, null, 0);
					if (color != -1) {
						looks.constantTint[id] = color & 0xFFFFFF;
					}
				} catch (RuntimeException e) {
					looks.constantTint[id] = -1;
				}
				*///?}
			}
		}
		// A fluid's block has no model of its own, so every cell holding a fluid and nothing solid
		// (kelp and seagrass stand in water) draws as the fluid's still texture. Water takes the
		// biome's water tint; another fluid keeps its own fixed tint, if it has one.
		Map<Fluid, FluidLook> fluidLooks = new IdentityHashMap<>();
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.BLOCK_STATE_REGISTRY.getId(state);
			if (id < 0 || id >= size || looks.kind[id] != WATER) {
				continue;
			}
			FluidState fluid = state.getFluidState();
			FluidLook look = fluidLooks.computeIfAbsent(fluid.getType(), type -> fluidLook(fluid, spriteCache));
			if (look.color() < 0) {
				// No texture to be found: keep the colour of the block's particle texture.
				continue;
			}
			looks.top[id] = look.color();
			looks.topTinted[id] = look.color();
			looks.topTintWeight[id] = 1;
			looks.side[id] = look.color();
			looks.sideTinted[id] = look.color();
			looks.sideTintWeight[id] = 1;
			if (fluid.getType().isSame(Fluids.WATER)) {
				looks.tint[id] = TINT_WATER;
			} else {
				looks.tint[id] = TINT_NONE;
				looks.constantTint[id] = look.tint();
			}
		}
		current = looks;
		//? if >=26.1 {
		builtFrom = models;
		//?} else {
		/*// The model shaper outlives resource reloads; the missing model is baked anew with each.
		builtFrom = mc.getModelManager().getMissingBlockStateModel();
		*///?}
		Diorama.LOGGER.info("Built block colors for {} states in {} ms", size, (System.nanoTime() - start) / 1_000_000);
		return looks;
	}

	//? if <1.21.5 {
	/*// Before 1.21.5 a block's model gave its quads itself, for one random seed, rather than
	// through parts. This stands in for the one part, asked the same way collectParts would.
	private record LegacyPart(BakedModel model, BlockState state, RandomSource random) {
		List<BakedQuad> getQuads(@Nullable Direction direction) {
			this.random.setSeed(42L);
			return this.model.getQuads(this.state, direction, this.random);
		}
	}

	*///?}
	private static byte classify(BlockState state) {
		if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE && state.getFluidState().isEmpty()) {
			return AIR;
		}
		FluidState fluid = state.getFluidState();
		boolean noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		if (!fluid.isEmpty() && noCollision) {
			return fluid.is(FluidTags.LAVA) ? LAVA : WATER;
		}
		if (noCollision) {
			return state.getBlock() instanceof SnowLayerBlock ? SOLID : PLANT;
		}
		return SOLID;
	}

	/** A fluid's colour as 0xRRGGBB, or -1 if its texture can't be found, and its fixed tint or -1. */
	private record FluidLook(int color, int tint) {
	}

	/**
	 * Looks up the still texture and tint the game draws a fluid with. A mod's tint code may not
	 * expect to be asked without a world, so a failure counts as no tint.
	 */
	private static FluidLook fluidLook(FluidState fluid, Map<TextureAtlasSprite, float[]> cache) {
		TextureAtlasSprite sprite = null;
		int tint = -1;
		try {
			//? if >=26.1 {
			FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid);
			sprite = model.stillMaterial().sprite();
			BlockTintSource source = model.tintSource();
			tint = source == null ? -1 : source.color(fluid.createLegacyBlock());
			//?} else if fabric {
			/*FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
			if (handler != null) {
				sprite = handler.getFluidSprites(null, null, fluid)[0];
				tint = handler.getFluidColor(null, null, fluid);
			}
			*///?} else if >=1.21.11 {
			/*IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
			sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(extensions.getStillTexture());
			tint = extensions.getTintColor();
			*///?} else {
			/*IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
			sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(extensions.getStillTexture());
			tint = extensions.getTintColor();
			*///?}
		} catch (RuntimeException e) {
			// Keep the sprite if only the tint failed.
			tint = -1;
		}
		float[] avg = sprite == null ? null : spriteAverage(sprite, cache);
		int color = avg == null || avg[3] <= 0 ? -1 : FaceColor.pack(avg[0], avg[1], avg[2]);
		// -1 and opaque white both mean no tint.
		return new FluidLook(color, (tint & 0xFFFFFF) == 0xFFFFFF ? -1 : tint & 0xFFFFFF);
	}

	private static void accumulate(FaceColor face, List<BakedQuad> quads, Map<TextureAtlasSprite, float[]> cache) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			face.add(spriteAverage(quad.materialInfo().sprite(), cache), quad.materialInfo().tintIndex() >= 0);
			//?} else {
			/*face.add(spriteAverage(quad.sprite(), cache), quad.tintIndex() >= 0);
			*///?}
		}
	}

	private static void accumulateLoose(FaceColor top, FaceColor side, List<BakedQuad> quads, Map<TextureAtlasSprite, float[]> cache) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			float[] avg = spriteAverage(quad.materialInfo().sprite(), cache);
			boolean tinted = quad.materialInfo().tintIndex() >= 0;
			//?} else {
			/*float[] avg = spriteAverage(quad.sprite(), cache);
			boolean tinted = quad.tintIndex() >= 0;
			*///?}
			if (quad.direction() == Direction.UP || quad.direction() == Direction.DOWN) {
				top.add(avg, tinted);
			} else {
				top.add(avg, tinted);
				side.add(avg, tinted);
			}
		}
	}

	private static final Field ORIGINAL_IMAGE;

	static {
		Field field;
		try {
			//? if >=26.1 || neoforge {
			field = SpriteContents.class.getDeclaredField("originalImage");
			//?} else {
			/*// Fabric runs the game with intermediary names outside the dev environment (NeoForge
			// doesn't), so look the field up by its intermediary name (SpriteContents.originalImage, a
			// NativeImage) mapped to whatever names this run uses.
			field = SpriteContents.class.getDeclaredField(FabricLoader.getInstance().getMappingResolver()
				.mapFieldName("intermediary", "net.minecraft.class_7764", "field_40539", "Lnet/minecraft/class_1011;"));
			*///?}
			field.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			field = null;
		}
		ORIGINAL_IMAGE = field;
	}

	/** Returns {r, g, b, coverage} averaged over the sprite's visible pixels, colors in 0..1. */
	private static float[] spriteAverage(TextureAtlasSprite sprite, Map<TextureAtlasSprite, float[]> cache) {
		return cache.computeIfAbsent(sprite, s -> {
			SpriteContents contents = s.contents();
			NativeImage image = null;
			try {
				image = ORIGINAL_IMAGE == null ? null : (NativeImage) ORIGINAL_IMAGE.get(contents);
			} catch (IllegalAccessException ignored) {
			}
			if (image == null) {
				return new float[] {1.0F, 0.0F, 1.0F, 1.0F};
			}
			int w = Math.min(contents.width(), image.getWidth());
			int h = Math.min(contents.height(), image.getHeight());
			double r = 0;
			double g = 0;
			double b = 0;
			double weight = 0;
			int visible = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					//? if >=1.21.2 {
					int argb = image.getPixel(x, y);
					//?} else {
					/*// Only the ABGR getter existed; swap red and blue.
					int abgr = image.getPixelRGBA(x, y);
					int argb = (abgr & 0xFF00FF00) | (abgr >> 16 & 0xFF) | (abgr & 0xFF) << 16;
					*///?}
					int a = argb >>> 24;
					if (a < 26) {
						continue;
					}
					double wa = a / 255.0;
					// Average in linear light so bright and dark pixels mix the way they look.
					r += srgbToLinear((argb >> 16) & 0xFF) * wa;
					g += srgbToLinear((argb >> 8) & 0xFF) * wa;
					b += srgbToLinear(argb & 0xFF) * wa;
					weight += wa;
					visible++;
				}
			}
			if (weight == 0) {
				return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
			}
			return new float[] {
				(float) linearToSrgb(r / weight), (float) linearToSrgb(g / weight), (float) linearToSrgb(b / weight),
				(float) visible / (w * h)
			};
		});
	}

	private static double srgbToLinear(int c) {
		double v = c / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}

	private static double linearToSrgb(double v) {
		return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
	}

	/** Running average of the quads that make up one face direction. */
	private static final class FaceColor {
		private float ur, ug, ub, uw;
		private float tr, tg, tb, tw;
		private float coverage;
		private int count;

		void add(float[] avg, boolean tinted) {
			float w = avg[3];
			if (w <= 0) {
				return;
			}
			if (tinted) {
				this.tr += avg[0] * w;
				this.tg += avg[1] * w;
				this.tb += avg[2] * w;
				this.tw += w;
			} else {
				this.ur += avg[0] * w;
				this.ug += avg[1] * w;
				this.ub += avg[2] * w;
				this.uw += w;
			}
			this.coverage = Math.max(this.coverage, w);
			this.count++;
		}

		boolean isEmpty() {
			return this.count == 0;
		}

		float coverage() {
			return this.coverage;
		}

		int untinted() {
			return this.uw == 0 ? 0 : pack(this.ur / this.uw, this.ug / this.uw, this.ub / this.uw);
		}

		int tinted() {
			return this.tw == 0 ? 0 : pack(this.tr / this.tw, this.tg / this.tw, this.tb / this.tw);
		}

		float tintWeight() {
			float total = this.uw + this.tw;
			return total == 0 ? 0 : this.tw / total;
		}

		private static int pack(float r, float g, float b) {
			return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
		}

		private static int clamp255(float v) {
			return Math.max(0, Math.min(255, Math.round(v * 255)));
		}
	}
}
