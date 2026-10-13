package dev.orthomap.bake;

import com.mojang.blaze3d.platform.NativeImage;
import dev.orthomap.Orthomap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
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

	private static final Set<Block> GRASS_TINTED = Set.of(
		Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
		Blocks.BUSH, Blocks.POTTED_FERN, Blocks.SUGAR_CANE, Blocks.PINK_PETALS, Blocks.WILDFLOWERS
	);
	private static final Set<Block> FOLIAGE_TINTED = Set.of(
		Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
		Blocks.VINE, Blocks.MANGROVE_LEAVES
	);
	private static final Set<Block> WATER_TINTED = Set.of(Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.WATER_CAULDRON);

	private static volatile BlockLooks current;

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
			&& this.tint[a] == this.tint[b]
			&& this.emission[a] == this.emission[b];
	}

	public static void invalidate() {
		current = null;
	}

	/** Builds the table. Must run on the client thread, after models are loaded. */
	public static BlockLooks build() {
		long start = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		BlockColors colors = mc.getBlockColors();
		int size = Block.BLOCK_STATE_REGISTRY.size();
		BlockLooks looks = new BlockLooks(size);
		Map<TextureAtlasSprite, float[]> spriteCache = new IdentityHashMap<>();
		RandomSource random = RandomSource.create(42L);
		List<BlockStateModelPart> parts = new ArrayList<>();

		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			int id = Block.BLOCK_STATE_REGISTRY.getId(state);
			if (id < 0 || id >= size) {
				continue;
			}
			looks.emission[id] = (byte) state.getLightEmission();
			looks.constantTint[id] = -1;
			byte kind = classify(state);
			looks.kind[id] = kind;
			if (kind == AIR) {
				continue;
			}

			parts.clear();
			random.setSeed(42L);
			BlockStateModel model = models.get(state);
			model.collectParts(random, parts);
			FaceColor topFace = new FaceColor();
			FaceColor sideFace = new FaceColor();
			for (BlockStateModelPart part : parts) {
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
				float[] avg = spriteAverage(model.particleMaterial().sprite(), spriteCache);
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
			} else if (block == Blocks.LEAF_LITTER) {
				looks.tint[id] = TINT_DRY_FOLIAGE;
			} else if (WATER_TINTED.contains(block) || kind == WATER) {
				looks.tint[id] = TINT_WATER;
			} else if (block == Blocks.LILY_PAD) {
				looks.constantTint[id] = BlockColors.LILY_PAD_IN_WORLD & 0xFFFFFF;
			} else {
				BlockTintSource source = colors.getTintSource(state, 0);
				if (source == null) {
					source = colors.getTintSource(state, 1);
				}
				if (source != null) {
					looks.constantTint[id] = source.color(state) & 0xFFFFFF;
				}
			}
		}
		// Fluids have no block model, so their particle texture is the untinted grey water. Every
		// water cell (including kelp and seagrass, which sit in water) draws as tinted water.
		int water = Block.BLOCK_STATE_REGISTRY.getId(Blocks.WATER.defaultBlockState());
		looks.topTinted[water] = looks.top[water];
		looks.topTintWeight[water] = 1;
		looks.sideTinted[water] = looks.side[water];
		looks.sideTintWeight[water] = 1;
		looks.tint[water] = TINT_WATER;
		for (int id = 0; id < size; id++) {
			if (looks.kind[id] == WATER && id != water) {
				looks.top[id] = looks.top[water];
				looks.topTinted[id] = looks.topTinted[water];
				looks.topTintWeight[id] = 1;
				looks.side[id] = looks.side[water];
				looks.sideTinted[id] = looks.sideTinted[water];
				looks.sideTintWeight[id] = 1;
				looks.tint[id] = TINT_WATER;
			}
		}
		current = looks;
		Orthomap.LOGGER.info("Built block colors for {} states in {} ms", size, (System.nanoTime() - start) / 1_000_000);
		return looks;
	}

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

	private static void accumulate(FaceColor face, List<BakedQuad> quads, Map<TextureAtlasSprite, float[]> cache) {
		for (BakedQuad quad : quads) {
			face.add(spriteAverage(quad.materialInfo().sprite(), cache), quad.materialInfo().tintIndex() >= 0);
		}
	}

	private static void accumulateLoose(FaceColor top, FaceColor side, List<BakedQuad> quads, Map<TextureAtlasSprite, float[]> cache) {
		for (BakedQuad quad : quads) {
			float[] avg = spriteAverage(quad.materialInfo().sprite(), cache);
			boolean tinted = quad.materialInfo().tintIndex() >= 0;
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
			field = SpriteContents.class.getDeclaredField("originalImage");
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
					int argb = image.getPixel(x, y);
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
