package dev.diorama.bake;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.NaturalSpawner;
import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import dev.diorama.data.Copycats;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
//? if >=26.1 {
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
//?} else if >=1.21.5 {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
*///?} else {
/*import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
*///?}
//? if <26.1 && fabric {
/*import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.loader.api.FabricLoader;
*///?} else if <26.1 && >=1.21.9 {
/*import net.minecraft.data.AtlasIds;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?} else if <26.1 {
/*import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
*///?}
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * What each block state looks like on the map: how rays treat it, and its average top and
 * front colors taken from the game's own textures. A state's look is worked out the first time
 * anything asks for its id through {@link #ensure} (or {@link #lookId}, {@link #bakeLookId},
 * {@link #copycat}, {@link #sameLook}), on whichever thread asks, as Xaero's World Map works a
 * block's colour out when its map writer first meets the block. A big modpack has hundreds of
 * thousands of states and a world uses a few thousand of them; asking every state's model up
 * front cost players minutes on each world load and resource reload, most of all under
 * ModernFix's dynamic resources, which bakes a model only when it's asked for. Once worked out,
 * a look never changes, so bake threads read it freely.
 *
 * <p>When a mod with copycats is installed, ids past the block state registry are looks for
 * filled copycats ({@link #copycat}), added by bake threads as they first meet them. Each is
 * written before its id is handed out, and never changes after.
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

	/** {@link #kind} of a state whose look nobody has asked for yet. */
	private static final byte NOT_YET = -1;
	/** Reads and writes {@link #kind}: a look's other fields are written before its kind is released. */
	private static final VarHandle KINDS = MethodHandles.arrayElementVarHandle(byte[].class);
	/** Held while a look is worked out: one at a time, across tables, since they share mods' code. */
	private static final Object MAKING = new Object();

	private static volatile BlockLooks current;
	private static int builds;
	/** The models the current table was built from; a resource reload replaces them. */
	private static Object builtFrom;

	/** Kind per state; {@link #NOT_YET} until {@link #ensure} works the look out. */
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
	/**
	 * Index into {@link #shapes} of a solid block's outline, or 0 for a full cube: everything else,
	 * and any block whose outline is empty, rays treat as a whole cube.
	 */
	public final int[] shape;
	/**
	 * Outlines of solid blocks smaller than a cube, such as slabs, stairs, fences and carpets: each
	 * box as six floats (min x, y, z, then max x, y, z) in block units. Entry 0 is unused. Grows
	 * as looks are worked out: a new array each time, published before the look that uses it.
	 */
	public volatile float[][] shapes = {new float[0]};
	/** {@link #faces} so far, by shape; made on first use. */
	private volatile @Nullable ShapeFaces @Nullable [] faces;
	/**
	 * For a waterlogged block smaller than a cube, the state id of the fluid around it: rays that
	 * pass beside the block, or reach it from above, go through that fluid. -1 for every other block.
	 */
	public final int[] wetShape;

	/**
	 * How many filled-copycat looks fit past the registry. Each pairing of a copycat state with a
	 * material takes one; past this many, more copycats draw as they do empty.
	 */
	private static final int COPYCAT_LOOKS = 1 << 14;
	/** The block state registry's size: ids from here on are filled copycats. */
	private final int registrySize;
	/** Filled-copycat look ids, by copycat state id in the high half and material state id in the low half. */
	private final Map<Long, Integer> copycatIds = new ConcurrentHashMap<>();
	private final AtomicInteger copycatCount = new AtomicInteger();
	private volatile boolean warnedCopycats;

	// What working a look out reads, all of it taken on the client thread as the table is made and
	// used under MAKING only.
	//? if >=26.1 {
	private final BlockStateModelSet models;
	//?} else {
	/*private final BlockModelShaper models;
	*///?}
	private final BlockColors colors;
	/** The block atlas as the table was made, for textures a reload freed (see {@link #currentSprite}). */
	private final @Nullable TextureAtlas atlas;
	private final Map<TextureAtlasSprite, float[]> spriteCache = new IdentityHashMap<>();
	private final RandomSource random = RandomSource.create(42L);
	private final TintProbe probe = new TintProbe();
	private final Map<VoxelShape, Integer> shapeByVoxels = new IdentityHashMap<>();
	private final Map<String, Integer> shapeByBoxes = new HashMap<>();
	private final List<float[]> shapeList = new ArrayList<>(List.of(new float[0]));
	private final Map<Fluid, FluidLook> fluidLooks = new IdentityHashMap<>();
	//? if >=26.1 {
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	//?} else if >=1.21.5 {
	/*private final List<BlockModelPart> parts = new ArrayList<>();
	*///?}
	private boolean warnedModel;
	private boolean warnedShape;
	/**
	 * Set when a look was worked out while resources were reloading, without the models and
	 * textures the reload is replacing: {@link #isStale} then has the table made again.
	 */
	private volatile boolean provisional;
	/** How many looks have been worked out, and the time it took, for the log. */
	private int made;
	private long makingNanos;

	//? if >=26.1 {
	private BlockLooks(int registrySize, int size, BlockStateModelSet models, BlockColors colors, @Nullable TextureAtlas atlas) {
	//?} else {
	/*private BlockLooks(int registrySize, int size, BlockModelShaper models, BlockColors colors, @Nullable TextureAtlas atlas) {
	*///?}
		this.models = models;
		this.colors = colors;
		this.atlas = atlas;
		this.registrySize = registrySize;
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
		this.shape = new int[size];
		this.wetShape = new int[size];
		java.util.Arrays.fill(this.wetShape, -1);
		java.util.Arrays.fill(this.kind, 0, registrySize, NOT_YET);
	}

	public static BlockLooks get() {
		return current;
	}

	/**
	 * What the GPU baker draws of {@link #shapes}{@code [shape]}. The mesher asks for every smaller
	 * block it meets, so this keeps them by index rather than going through ShapeFaces' shared,
	 * locked table each time. Any thread.
	 */
	ShapeFaces faces(int shape) {
		ShapeFaces[] faces = this.faces;
		if (faces == null || shape >= faces.length) {
			// More outlines since: a longer copy. Two threads may both grow it, and one's entries are
			// lost; they're made again when next asked.
			faces = java.util.Arrays.copyOf(faces == null ? new ShapeFaces[0] : faces, this.shapes.length);
			this.faces = faces;
		}
		ShapeFaces made = faces[shape];
		if (made == null) {
			// Two threads may both look it up; they get the same one.
			made = ShapeFaces.of(this.shapes[shape]);
			faces[shape] = made;
		}
		return made;
	}

	/**
	 * The state's id in the tables here. A state the game hasn't numbered, which some mods make
	 * on the fly (Epic Fight's cracked ground, #180), takes its block's default state's id, or
	 * air's. Only numbers it: {@link #lookId} also works its look out.
	 */
	public static int id(BlockState state) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		if (id < 0) {
			id = Block.BLOCK_STATE_REGISTRY.getId(state.getBlock().defaultBlockState());
		}
		if (id < 0) {
			id = Block.BLOCK_STATE_REGISTRY.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}
		return Math.max(id, 0);
	}

	/**
	 * {@link #id} for a bake. While NeoForge or Forge number every state again, as they do when a
	 * world is left, states have no id for a moment and the table is short: the bake stops
	 * ({@link StatesRenumbered}) rather than read those blocks as something else, since the map
	 * still saves its last bakes as it closes.
	 */
	public static int bakeId(BlockState state) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		if (id >= 0) {
			return id;
		}
		BlockLooks looks = current;
		if (looks != null && Block.BLOCK_STATE_REGISTRY.size() != looks.registrySize) {
			throw StatesRenumbered.INSTANCE;
		}
		return id(state);
	}

	/**
	 * Works the state's look out if nothing has asked for it yet, and returns the id. Any thread:
	 * the first to ask does the work, others asking meanwhile wait for it. Every id whose look is
	 * read here comes through this first, or through a method that calls it.
	 */
	public int ensure(int id) {
		if (id >= 0 && id < this.registrySize && (byte) KINDS.getAcquire(this.kind, id) == NOT_YET) {
			this.make(id);
		}
		return id;
	}

	/** {@link #id}, with the state's look worked out. */
	public int lookId(BlockState state) {
		return this.ensure(id(state));
	}

	/** {@link #bakeId}, with the state's look worked out. */
	public int bakeLookId(BlockState state) {
		return this.ensure(bakeId(state));
	}

	/** See {@link #bakeId}. */
	public static final class StatesRenumbered extends RuntimeException {
		private static final StatesRenumbered INSTANCE = new StatesRenumbered();

		private StatesRenumbered() {
			super("Block states are being numbered again", null, false, false);
		}
	}

	/**
	 * True when two states draw the same on the map. Leaves changing their distance property,
	 * for example, is a block update nobody can see.
	 */
	public boolean sameLook(int a, int b) {
		this.ensure(a);
		this.ensure(b);
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
			&& this.spawnIn[a] == this.spawnIn[b]
			// Same index, same boxes: outlines are stored once each.
			&& this.shape[a] == this.shape[b]
			// Filling or draining a waterlogged slab changes nothing else.
			&& this.wetShape[a] == this.wetShape[b];
	}

	/**
	 * The look of a copycat filled with a material, both given as state ids: the copycat's outline,
	 * spawn rules and water, with the material's colours, tint and light. Rays pass through it if
	 * they pass through the material, as through a copycat filled with glass, and pass through a
	 * copycat without collision (a button, a pressure plate) as they do empty. Any thread: a bake
	 * calls this when it reads a snapshot's copycats, and the look is filled in before its id is
	 * handed out, so a bake only ever reads looks that are done. Returns the copycat's own id when
	 * there's nothing to copy, or no room left.
	 */
	public int copycat(int copycat, int material) {
		if (copycat < 0 || copycat >= this.registrySize || material < 0 || material >= this.registrySize || this.kind.length == this.registrySize) {
			return this.ensure(copycat);
		}
		this.ensure(copycat);
		this.ensure(material);
		byte frame = this.kind[copycat];
		if (frame != SOLID && frame != GLASS && frame != PLANT || this.kind[material] == AIR) {
			return copycat;
		}
		Long key = (long) copycat << 32 | material;
		Integer id = this.copycatIds.get(key);
		if (id != null) {
			return id;
		}
		if (this.copycatCount.get() >= this.kind.length - this.registrySize) {
			// Full: don't grow the table with pairs that would all draw empty anyway.
			this.warnFull();
			return copycat;
		}
		return this.copycatIds.computeIfAbsent(key, unused -> this.addCopycat(copycat, material));
	}

	private void warnFull() {
		if (!this.warnedCopycats) {
			this.warnedCopycats = true;
			Diorama.LOGGER.warn("More than {} kinds of filled copycat; the rest draw as they do empty", COPYCAT_LOOKS);
		}
	}

	private int addCopycat(int copycat, int material) {
		int id = this.registrySize + this.copycatCount.getAndIncrement();
		if (id >= this.kind.length) {
			// Bake threads that passed the check above together.
			this.warnFull();
			return copycat;
		}
		this.kind[id] = this.kind[copycat] == PLANT ? PLANT : this.kind[material] == GLASS ? GLASS : SOLID;
		this.shape[id] = this.shape[copycat];
		this.spawnOn[id] = this.spawnOn[copycat];
		this.spawnIn[id] = this.spawnIn[copycat];
		this.wetShape[id] = this.wetShape[copycat];
		this.top[id] = this.top[material];
		this.topTinted[id] = this.topTinted[material];
		this.topTintWeight[id] = this.topTintWeight[material];
		this.side[id] = this.side[material];
		this.sideTinted[id] = this.sideTinted[material];
		this.sideTintWeight[id] = this.sideTintWeight[material];
		this.tint[id] = this.tint[material];
		this.constantTint[id] = this.constantTint[material];
		this.emission[id] = this.emission[material];
		return id;
	}

	/** For tests: how many times the table has been built. */
	public static int builds() {
		return builds;
	}

	public static void invalidate() {
		current = null;
	}

	/**
	 * True when resource packs were reloaded since the table was built, or a look in it was worked
	 * out while they were reloading.
	 */
	public static boolean isStale() {
		BlockLooks looks = current;
		if (looks == null) {
			return false;
		}
		if (looks.provisional) {
			return true;
		}
		//? if >=26.1 {
		return builtFrom != Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		//?} else {
		/*return builtFrom != Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
		*///?}
	}

	/**
	 * Makes a new, empty table; looks are worked out as they're asked for. Must run on the client
	 * thread, after models are loaded.
	 */
	public static BlockLooks build() {
		long start = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		BlockLooks last = current;
		if (last != null) {
			Diorama.LOGGER.info("The last block color table worked out {}", last.madeSoFar());
		}
		//? if >=26.1 {
		BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
		//?} else {
		/*BlockModelShaper models = mc.getModelManager().getBlockModelShaper();
		*///?}
		int size = Block.BLOCK_STATE_REGISTRY.size();
		TextureAtlas atlas = null;
		try {
			// Found from a texture of a block every version has, so no version's atlas id is needed.
			//? if >=26.1 {
			TextureAtlasSprite stone = particle(models.get(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
			//?} else {
			/*TextureAtlasSprite stone = particle(models.getBlockModel(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
			*///?}
			if (stone != null && mc.getTextureManager().getTexture(stone.atlasLocation()) instanceof TextureAtlas blocks) {
				atlas = blocks;
			}
		} catch (RuntimeException | LinkageError e) {
			// Without it, a texture a reload freed adds no colour.
		}
		BlockLooks looks = new BlockLooks(size, Copycats.INSTALLED ? size + COPYCAT_LOOKS : size, models, mc.getBlockColors(), atlas);
		warnedFreed = false;
		synchronized (MAKING) {
			// Before any block: those tinted like water ask for the same colour as water does.
			looks.probe.reset();
			BiomeTints.setWater(waterResolver(looks.probe));
		}
		// What a bake reads where a section's palette has no entry.
		looks.ensure(VoxelReader.AIR_ID);
		current = looks;
		builds++;
		//? if >=26.1 {
		builtFrom = models;
		//?} else {
		/*// The model shaper outlives resource reloads; the missing model is baked anew with each.
		builtFrom = mc.getModelManager().getMissingBlockStateModel();
		*///?}
		Diorama.LOGGER.info("Block color table for {} states set up in {} ms; each state's colors are worked out when the map first meets it",
			size, (System.nanoTime() - start) / 1_000_000);
		return looks;
	}

	/** For the log: how many looks this table has worked out, and the milliseconds they took. */
	public String madeSoFar() {
		synchronized (MAKING) {
			return this.made + " looks in " + this.makingNanos / 1_000_000 + " ms";
		}
	}

	/** Works out one state's look, unless another thread just did. */
	private void make(int id) {
		synchronized (MAKING) {
			if (this.kind[id] != NOT_YET) {
				return;
			}
			long start = System.nanoTime();
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
			// A reload frees the atlas's images in its last step, long after it puts up its overlay,
			// and swaps the models meanwhile: while it's up, a look takes no model or texture, and the
			// table is made again once the reload is over.
			boolean reloading = Minecraft.getInstance().gui.overlay() != null;
			byte kind = state == null ? AIR : this.work(id, state, !reloading);
			if (reloading) {
				this.provisional = true;
			}
			KINDS.setRelease(this.kind, id, kind);
			this.made++;
			this.makingNanos += System.nanoTime() - start;
		}
	}

	/** Fills in the state's look but its kind, which it returns. Under {@link #MAKING}. */
	private byte work(int id, BlockState state, boolean withModels) {
		this.emission[id] = (byte) state.getLightEmission();
		this.constantTint[id] = -1;
		// Minecraft's own spawn rules, asked of the block alone: none of them look around it.
		try {
			this.spawnOn[id] = state.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, EntityTypes.ZOMBIE);
			this.spawnIn[id] = NaturalSpawner.isValidEmptySpawnBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, state.getFluidState(), EntityTypes.ZOMBIE)
				&& state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		} catch (RuntimeException | LinkageError e) {
			this.spawnOn[id] = false;
			this.spawnIn[id] = false;
		}
		// A mod's block can give a null shape or throw when asked, and under ModernFix, which builds
		// each state's cache on first use, so can anything that reads the cache. One that fails
		// counts as a full block and keeps its model's colours.
		byte kind = SOLID;
		try {
			kind = classify(state);
			if (kind == SOLID) {
				this.shape[id] = this.shapeIndex(state);
			}
		} catch (RuntimeException | LinkageError e) {
			if (!this.warnedShape) {
				this.warnedShape = true;
				Diorama.LOGGER.warn("The shape of {} failed when asked for ({}); such blocks draw as full blocks", state, e.toString());
			}
		}
		if (kind == AIR) {
			return AIR;
		}
		kind = withModels ? this.colours(id, state, kind) : this.flatColours(id, state, kind);
		if (kind == AIR) {
			this.shape[id] = 0;
			return AIR;
		}
		// A fluid's block has no model of its own, so every cell holding a fluid and nothing solid
		// (kelp and seagrass stand in water) draws as the fluid's still texture, tinted the way the
		// fluid's own tint code asks: water by the biome.
		if (kind == WATER && withModels) {
			FluidState fluid = state.getFluidState().getType().defaultFluidState();
			FluidLook look = this.fluidLooks.computeIfAbsent(fluid.getType(), type -> this.fluidLook(fluid));
			// With no texture to be found, the block's particle texture's colour stays.
			if (look.color() >= 0) {
				this.top[id] = look.color();
				this.topTinted[id] = look.color();
				this.topTintWeight[id] = 1;
				this.side[id] = look.color();
				this.sideTinted[id] = look.color();
				this.sideTintWeight[id] = 1;
				this.tint[id] = look.tint().kind();
				this.constantTint[id] = look.tint().constant();
			}
		}
		if (this.shape[id] != 0 && !state.getFluidState().isEmpty()) {
			int fluid = Block.BLOCK_STATE_REGISTRY.getId(state.getFluidState().createLegacyBlock());
			if (fluid >= 0 && fluid < this.registrySize && fluid != id && this.kind[this.ensure(fluid)] == WATER) {
				this.wetShape[id] = fluid;
			}
		}
		return kind;
	}

	/** The state's colours from its model's textures; returns its kind, which they can change. */
	private byte colours(int id, BlockState state, byte kind) {
		//? if >=26.1 {
		BlockStateModel model = this.models.get(state);
		//?} else if >=1.21.5 {
		/*BlockStateModel model = this.models.getBlockModel(state);
		*///?} else {
		/*BakedModel model = this.models.getBlockModel(state);
		*///?}
		FaceColor topFace = new FaceColor();
		FaceColor sideFace = new FaceColor();
		// A mod's model can fail when asked for its faces without its block entity's data, as
		// Immersive Engineering's mirrored multiblock parts do. Such a block falls back below like
		// one with no faces, instead of the failure crashing the game.
		try {
			//? if >=1.21.5 {
			this.parts.clear();
			this.random.setSeed(42L);
			model.collectParts(this.random, this.parts);
			//?} else {
			/*List<LegacyPart> parts = List.of(new LegacyPart(model, state, this.random));
			*///?}
			//? if >=26.1 {
			for (BlockStateModelPart part : this.parts) {
			//?} else if >=1.21.5 {
			/*for (BlockModelPart part : this.parts) {
			*///?} else {
			/*for (LegacyPart part : parts) {
			*///?}
				this.accumulate(topFace, part.getQuads(Direction.UP));
				this.accumulate(sideFace, part.getQuads(Direction.SOUTH));
				if (sideFace.isEmpty()) {
					this.accumulate(sideFace, part.getQuads(Direction.NORTH));
				}
				// Cross-shaped plants and other unculled geometry.
				List<BakedQuad> loose = part.getQuads(null);
				if (!loose.isEmpty()) {
					this.accumulateLoose(topFace, sideFace, loose);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			topFace = new FaceColor();
			sideFace = new FaceColor();
			if (!this.warnedModel) {
				this.warnedModel = true;
				Diorama.LOGGER.warn("The model of {} failed when asked for its faces ({}); such blocks take their particle texture or map colour", state, e.toString());
			}
		}
		if (topFace.isEmpty() && sideFace.isEmpty()) {
			// Block entities (chests, signs, beds) have no block model; use the particle texture.
			TextureAtlasSprite particle = particle(model);
			if (particle != null && !isMissing(particle)) {
				float[] avg = this.spriteAverage(particle);
				topFace.add(avg, -1);
				sideFace.add(avg, -1);
			}
		}
		if (topFace.isEmpty() && sideFace.isEmpty()) {
			// No texture to go by: a model that draws only from its block entity's data, which this
			// table can't see (Copycats+ copycats take the look of the block they're filled with), a
			// texture the game can't find, or one a reload freed with nothing in its place.
			return this.flatColours(id, state, kind);
		}
		if (topFace.isEmpty()) {
			topFace = sideFace;
		}
		if (sideFace.isEmpty()) {
			sideFace = topFace;
		}
		this.top[id] = topFace.untinted();
		this.topTinted[id] = topFace.tinted();
		this.topTintWeight[id] = topFace.tintWeight();
		this.side[id] = sideFace.untinted();
		this.sideTinted[id] = sideFace.tinted();
		this.sideTintWeight[id] = sideFace.tintWeight();

		if (kind == SOLID && !state.is(BlockTags.LEAVES) && topFace.coverage() < 0.55F) {
			kind = GLASS;
		}

		// The tint layer the model's quads use, asked of the block's own tint code.
		int tintIndex = topFace.tintIndex() >= 0 ? topFace.tintIndex() : sideFace.tintIndex();
		if (tintIndex >= 0) {
			Tint tint = blockTint(this.colors, state, tintIndex, this.probe);
			this.tint[id] = tint.kind();
			this.constantTint[id] = tint.constant();
		}
		return kind;
	}

	/**
	 * The block's colour on Minecraft's own maps instead of the missing texture's purple, seen
	 * through where those maps see through it ({@link #AIR}). Returns its kind.
	 */
	private byte flatColours(int id, BlockState state, byte kind) {
		int mapColor = mapColor(state);
		if (mapColor == 0) {
			return AIR;
		}
		FaceColor face = new FaceColor();
		face.add(new float[] {(mapColor >> 16 & 0xFF) / 255.0F, (mapColor >> 8 & 0xFF) / 255.0F, (mapColor & 0xFF) / 255.0F, 1.0F}, -1);
		this.top[id] = face.untinted();
		this.topTinted[id] = face.tinted();
		this.topTintWeight[id] = face.tintWeight();
		this.side[id] = face.untinted();
		this.sideTinted[id] = face.tinted();
		this.sideTintWeight[id] = face.tintWeight();
		return kind;
	}

	//? if >=26.1 {
	private static @Nullable TextureAtlasSprite particle(BlockStateModel model) {
	//?} else if >=1.21.5 {
	/*private static @Nullable TextureAtlasSprite particle(BlockStateModel model) {
	*///?} else {
	/*private static @Nullable TextureAtlasSprite particle(BakedModel model) {
	*///?}
		try {
			//? if >=26.1 {
			return model.particleMaterial().sprite();
			//?} else {
			/*return model.particleIcon();
			*///?}
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
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
	/** A shape with more boxes than this counts as its bounding box. */
	private static final int MAX_BOXES = 16;

	/** Finds or adds the state's outline in {@link #shapes}; 0 for a full cube. Under {@link #MAKING}. */
	private int shapeIndex(BlockState state) {
		Map<VoxelShape, Integer> byVoxels = this.shapeByVoxels;
		List<float[]> shapes = this.shapeList;
		VoxelShape outline;
		try {
			outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException | LinkageError e) {
			return 0;
		}
		Integer known = byVoxels.get(outline);
		if (known != null) {
			return known;
		}
		int index = 0;
		if (!outline.isEmpty() && outline != Shapes.block()) {
			List<AABB> boxes = outline.toAabbs();
			if (boxes.size() > MAX_BOXES) {
				boxes = List.of(outline.bounds());
			}
			float[] flat = new float[boxes.size() * 6];
			int n = 0;
			boolean full = false;
			for (AABB box : boxes) {
				// Some mods' outlines reach into the next block; only the part in this one counts,
				// and a box wholly outside it is dropped.
				float[] b = {clamp01(box.minX), clamp01(box.minY), clamp01(box.minZ), clamp01(box.maxX), clamp01(box.maxY), clamp01(box.maxZ)};
				if (b[0] >= b[3] || b[1] >= b[4] || b[2] >= b[5]) {
					continue;
				}
				System.arraycopy(b, 0, flat, n, 6);
				n += 6;
				full |= b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1;
			}
			float[] kept = java.util.Arrays.copyOf(flat, n);
			if (!full && n > 0) {
				index = this.shapeByBoxes.computeIfAbsent(java.util.Arrays.toString(kept), key -> {
					shapes.add(kept);
					// Published before the look that uses it.
					this.shapes = shapes.toArray(new float[0][]);
					return shapes.size() - 1;
				});
			}
		}
		byVoxels.put(outline, index);
		return index;
	}

	private static float clamp01(double v) {
		return (float) Math.max(0, Math.min(1, v));
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

	/** A fluid's colour as 0xRRGGBB, or -1 if its texture can't be found, and its tint. */
	private record FluidLook(int color, Tint tint) {
	}

	/** Looks up the still texture and the tint the game draws a fluid with. */
	private FluidLook fluidLook(FluidState fluid) {
		TextureAtlasSprite sprite;
		try {
			//? if >=26.1 {
			sprite = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).stillMaterial().sprite();
			//?} else if fabric {
			/*FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
			sprite = handler == null ? null : handler.getFluidSprites(null, null, fluid)[0];
			*///?} else if >=1.21.9 {
			/*sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)
				.getSprite(IClientFluidTypeExtensions.of(fluid).getStillTexture());
			*///?} else {
			/*sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
				.apply(IClientFluidTypeExtensions.of(fluid).getStillTexture());
			*///?}
		} catch (RuntimeException | LinkageError e) {
			sprite = null;
		}
		if (sprite != null && isMissing(sprite)) {
			// An unregistered fluid or a missing texture: better the block's particle colour than purple.
			sprite = null;
		}
		float[] avg = sprite == null ? null : this.spriteAverage(sprite);
		int color = avg == null || avg[3] <= 0 ? -1 : FaceColor.pack(avg[0], avg[1], avg[2]);
		return new FluidLook(color, fluidTint(fluid, this.probe));
	}

	/** How a block or fluid is tinted: by a biome colour ({@code TINT_*}) or a fixed colour, -1 for none. */
	private record Tint(byte kind, int constant) {
		static final Tint NONE = new Tint(TINT_NONE, -1);

		/**
		 * Asks tint code for its colour in the probe's world. For a biome colour the map doesn't
		 * blend itself (a mod's own), or code that fails in the stand-in world, it takes the code's
		 * colour out of the world instead, as the map did before it asked in a world at all.
		 */
		static Tint ask(TintProbe probe, IntSupplier inWorld, IntSupplier outOfWorld) {
			probe.reset();
			int color;
			// Here and below, a LinkageError too: a mod's code can name a class from a mod that isn't
			// installed, which fails only when it runs, and the map runs every block's code at once.
			try {
				color = inWorld.getAsInt();
			} catch (RuntimeException | LinkageError e) {
				color = -1;
			}
			byte kind = probe.kind();
			if (kind != TINT_NONE) {
				return new Tint(kind, -1);
			}
			if (probe.askedForBiome() || color == -1) {
				try {
					color = outOfWorld.getAsInt();
				} catch (RuntimeException | LinkageError e) {
					return NONE;
				}
			}
			// -1 (no tint code) and white both leave the texture as it is.
			return (color & 0xFFFFFF) == 0xFFFFFF ? NONE : new Tint(TINT_NONE, color & 0xFFFFFF);
		}
	}

	/** Asks a block's tint code how one tint layer is coloured. */
	private static Tint blockTint(BlockColors colors, BlockState state, int index, TintProbe probe) {
		//? if >=26.1 {
		BlockTintSource source = colors.getTintSource(state, index);
		return source == null ? Tint.NONE : Tint.ask(probe, () -> source.colorInWorld(state, probe, BlockPos.ZERO), () -> source.color(state));
		//?} else {
		/*return Tint.ask(probe, () -> colors.getColor(state, probe, BlockPos.ZERO, index), () -> colors.getColor(state, null, null, index));
		*///?}
	}

	/** The biome colour water's tint code asks for, the game's own if it asks for none. */
	private static ColorResolver waterResolver(TintProbe probe) {
		probe.reset();
		fluidTint(Fluids.WATER.defaultFluidState(), probe);
		ColorResolver asked = probe.asked();
		return asked != null ? asked : BiomeColors.WATER_COLOR_RESOLVER;
	}

	/** The same for a fluid's tint code. */
	private static Tint fluidTint(FluidState fluid, TintProbe probe) {
		try {
			//? if >=26.1 {
			BlockTintSource source = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).tintSource();
			BlockState block = fluid.createLegacyBlock();
			return source == null ? Tint.NONE : Tint.ask(probe, () -> source.colorInWorld(block, probe, BlockPos.ZERO), () -> source.color(block));
			//?} else if fabric {
			/*FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
			return handler == null ? Tint.NONE
				: Tint.ask(probe, () -> handler.getFluidColor(probe, BlockPos.ZERO, fluid), () -> handler.getFluidColor(null, null, fluid));
			*///?} else {
			/*IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
			return Tint.ask(probe, () -> extensions.getTintColor(fluid, probe, BlockPos.ZERO), extensions::getTintColor);
			*///?}
		} catch (RuntimeException | LinkageError e) {
			return Tint.NONE;
		}
	}

	private void accumulate(FaceColor face, List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			int tintIndex = quad.materialInfo().tintIndex();
			//?} else {
			/*TextureAtlasSprite sprite = quad.sprite();
			int tintIndex = quad.tintIndex();
			*///?}
			if (!isMissing(sprite)) {
				face.add(this.spriteAverage(sprite), tintIndex);
			}
		}
	}

	private void accumulateLoose(FaceColor top, FaceColor side, List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			//? if >=26.1 {
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			int tintIndex = quad.materialInfo().tintIndex();
			//?} else {
			/*TextureAtlasSprite sprite = quad.sprite();
			int tintIndex = quad.tintIndex();
			*///?}
			if (isMissing(sprite)) {
				continue;
			}
			float[] avg = this.spriteAverage(sprite);
			if (quad.direction() == Direction.UP || quad.direction() == Direction.DOWN) {
				top.add(avg, tintIndex);
			} else {
				top.add(avg, tintIndex);
				side.add(avg, tintIndex);
			}
		}
	}

	/** The game's magenta and black stand-in for a texture it can't find. */
	private static boolean isMissing(TextureAtlasSprite sprite) {
		return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
	}

	/** The block's colour on Minecraft's own maps as 0xRRGGBB, or 0 where those maps see through it. */
	private static int mapColor(BlockState state) {
		try {
			return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		} catch (RuntimeException | LinkageError e) {
			return 0;
		}
	}

	private static final Field ORIGINAL_IMAGE;

	static {
		Field field;
		try {
			//? if >=26.1 || neoforge {
			field = SpriteContents.class.getDeclaredField("originalImage");
			//?} else if forge {
			/*// Forge runs the game with SRG names outside the dev environment, so look the field up by
			// its SRG name, mapped to whatever names this run uses.
			field = SpriteContents.class.getDeclaredField(net.minecraftforge.fml.util.ObfuscationReflectionHelper.remapName(
				cpw.mods.modlauncher.api.INameMappingService.Domain.FIELD, "f_243904_"));
			*///?} else {
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
	private float[] spriteAverage(TextureAtlasSprite sprite) {
		return this.spriteCache.computeIfAbsent(sprite, s -> {
			float[] avg = average(s);
			if (avg != null) {
				return avg;
			}
			// The sprite is from the atlas before the last resource reload, which freed its image: a
			// model that outlived the reload still points at it (issue 94, a big NeoForge pack). Read
			// the texture of that name in the atlas now; if the atlas has none, it adds no colour.
			TextureAtlasSprite current = this.currentSprite(s);
			avg = current == null ? null : average(current);
			if (!warnedFreed) {
				warnedFreed = true;
				Diorama.LOGGER.warn("A block model holds texture {} from before the last resource reload; {}", s.contents().name(),
					avg != null ? "using the reloaded texture" : "it adds no colour");
			}
			return avg != null ? avg : new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		});
	}

	/** Set once per build, so a mod that keeps many old models is named once in the log. */
	private static boolean warnedFreed;

	/** The sprite with the same name in its atlas as the atlas is now, or null if there's none. */
	private @Nullable TextureAtlasSprite currentSprite(TextureAtlasSprite old) {
		try {
			// The block atlas, as found on the client thread when the table was made: the texture
			// manager's own map is only safe to read there. Atlases are registered once at startup and
			// filled again by each reload, so this is the atlas as it is now.
			TextureAtlas atlas = this.atlas;
			if (atlas == null || !old.atlasLocation().equals(atlas.location())) {
				if (!Minecraft.getInstance().isSameThread()) {
					return null;
				}
				atlas = Minecraft.getInstance().getTextureManager().getTexture(old.atlasLocation()) instanceof TextureAtlas found ? found : null;
				if (atlas == null) {
					return null;
				}
			}
			TextureAtlasSprite sprite = atlas.getSprite(old.contents().name());
			return sprite == old || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? null : sprite;
		} catch (RuntimeException | LinkageError e) {
			// The atlas isn't filled, or a mod's atlas isn't a TextureAtlas.
		}
		return null;
	}

	/** {@link #spriteAverage} for one sprite, or null if its image has been freed. */
	private static float @Nullable [] average(TextureAtlasSprite s) {
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
		try {
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
					// Average the stored (sRGB) values, as Xaero's World Map and JourneyMap do. In linear
					// light the bright pixels outweigh the dark ones: a casing that is a dark panel in a
					// brass frame came out the frame's colour, and an enchanting table's red top grey.
					r += ((argb >> 16) & 0xFF) * wa;
					g += ((argb >> 8) & 0xFF) * wa;
					b += (argb & 0xFF) * wa;
					weight += wa;
					visible++;
				}
			}
		} catch (IllegalStateException freed) {
			// "Image is not allocated": NativeImage checks before every read.
			return null;
		}
		if (weight == 0) {
			return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		}
		return new float[] {
			(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255),
			(float) visible / (w * h)
		};
	}

	/** Running average of the quads that make up one face direction. */
	private static final class FaceColor {
		private float ur, ug, ub, uw;
		private float tr, tg, tb, tw;
		private float coverage;
		private int count;
		/** The first tint layer a quad asked for, or -1. */
		private int tintIndex = -1;

		void add(float[] avg, int tintIndex) {
			float w = avg[3];
			if (w <= 0) {
				return;
			}
			if (tintIndex >= 0) {
				if (this.tintIndex < 0) {
					this.tintIndex = tintIndex;
				}
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

		int tintIndex() {
			return this.tintIndex;
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
