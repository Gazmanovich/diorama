package dev.diorama.bake;

import dev.diorama.Diorama;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * What each block state looks like on the map, as the tracer and mesher read it: how rays treat
 * it, its outline, and its average top and front colours. This half is what the game's own rules
 * say about a block (its kind, outline, light and spawn rules), which a dedicated server knows as
 * well as a client. Where the colours come from is up to the table: {@link BlockLooks} reads the
 * client's models and textures, {@link TableLooks} the vanilla colours shipped in the jar.
 *
 * <p>A state's look is worked out the first time anything asks for its id through {@link #ensure}
 * (or a method that calls it), on whichever thread asks. Once worked out, a look never changes,
 * so bake threads read it freely.
 *
 * <p>When a mod with copycats is installed, ids past the block state registry are looks for
 * filled copycats ({@link #copycat}), added by bake threads as they first meet them. Each is
 * written before its id is handed out, and never changes after.
 */
public abstract class Looks {
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
	protected static final byte NOT_YET = -1;
	/** Reads and writes {@link #kind}: a look's other fields are written before its kind is released. */
	protected static final VarHandle KINDS = MethodHandles.arrayElementVarHandle(byte[].class);

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
	 * as looks are worked out, published before the look that uses it; entries past the last
	 * outline are null.
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
	protected static final int COPYCAT_LOOKS = 1 << 14;
	/** The block state registry's size: ids from here on are filled copycats. */
	protected final int registrySize;
	/** Filled-copycat look ids, by copycat state id in the high half and material state id in the low half. */
	private final Map<Long, Integer> copycatIds = new ConcurrentHashMap<>();
	private final AtomicInteger copycatCount = new AtomicInteger();
	private volatile boolean warnedCopycats;

	// Outlines found so far, used only while a look is worked out (one at a time, see make).
	private final Map<VoxelShape, Integer> shapeByVoxels = new IdentityHashMap<>();
	private final Map<String, Integer> shapeByBoxes = new HashMap<>();
	private final List<float[]> shapeList = new ArrayList<>(List.of(new float[0]));
	private boolean warnedShape;

	protected Looks(int registrySize, int size) {
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

	/**
	 * Works out one state's look, unless another thread just did: under a lock, calls
	 * {@link #work} and releases the kind it returns into {@link #kind} with {@link #KINDS}.
	 */
	protected abstract void make(int id);

	/**
	 * Fills in the state's colours and tint for a look whose kind the game's rules make
	 * {@code kind}, and returns its kind, which the colours can change ({@link #GLASS} for a
	 * see-through texture, {@link #AIR} for a block with nothing to show). Under the lock of
	 * {@link #make}.
	 */
	protected abstract byte colours(int id, BlockState state, byte kind);

	/**
	 * What {@link #colours} gives a block with no texture to go by: its colour on Minecraft's own
	 * maps, seen through where those maps see through it ({@link #AIR}). Returns its kind.
	 */
	protected byte flatColours(int id, BlockState state, byte kind) {
		int mapColor = mapColor(state);
		if (mapColor == 0) {
			return AIR;
		}
		FaceColor face = new FaceColor();
		face.add(new float[] {(mapColor >> 16 & 0xFF) / 255.0F, (mapColor >> 8 & 0xFF) / 255.0F, (mapColor & 0xFF) / 255.0F, 1.0F}, -1);
		this.setColours(id, face, face);
		return kind;
	}

	/** Writes both faces' colours into the state's look. */
	protected void setColours(int id, FaceColor topFace, FaceColor sideFace) {
		this.top[id] = topFace.untinted();
		this.topTinted[id] = topFace.tinted();
		this.topTintWeight[id] = topFace.tintWeight();
		this.side[id] = sideFace.untinted();
		this.sideTinted[id] = sideFace.tinted();
		this.sideTintWeight[id] = sideFace.tintWeight();
	}

	/** The block's colour on Minecraft's own maps as 0xRRGGBB, or 0 where those maps see through it. */
	protected static int mapColor(BlockState state) {
		try {
			return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		} catch (RuntimeException | Error e) {
			return 0;
		}
	}

	/**
	 * Fills in the state's look but its kind, which it returns: the game's rules here, the
	 * colours from {@link #colours}. Under the lock of {@link #make}.
	 */
	protected final byte work(int id, BlockState state) {
		this.emission[id] = (byte) state.getLightEmission();
		this.constantTint[id] = -1;
		// Minecraft's own spawn rules, asked of the block alone: none of them look around it.
		try {
			this.spawnOn[id] = state.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, EntityTypes.ZOMBIE);
			this.spawnIn[id] = NaturalSpawner.isValidEmptySpawnBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, state.getFluidState(), EntityTypes.ZOMBIE)
				&& state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		} catch (RuntimeException | Error e) {
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
		} catch (RuntimeException | Error e) {
			if (!this.warnedShape) {
				this.warnedShape = true;
				Diorama.LOGGER.warn("The shape of {} failed when asked for ({}); such blocks draw as full blocks", state, e.toString());
			}
		}
		if (kind == AIR) {
			return AIR;
		}
		kind = this.colours(id, state, kind);
		if (kind == AIR) {
			this.shape[id] = 0;
			return AIR;
		}
		if (this.shape[id] != 0 && !state.getFluidState().isEmpty()) {
			int fluid = Block.BLOCK_STATE_REGISTRY.getId(state.getFluidState().createLegacyBlock());
			if (fluid >= 0 && fluid < this.registrySize && fluid != id && this.kind[this.ensure(fluid)] == WATER) {
				this.wetShape[id] = fluid;
			}
		}
		return kind;
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
	protected static int bakeId(BlockState state, @Nullable Looks looks) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		if (id >= 0) {
			return id;
		}
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

	/**
	 * {@link #bakeId}, with the state's look worked out. A look left unworked because states are
	 * being numbered again stops the bake ({@link StatesRenumbered}), as {@link #bakeId} does.
	 */
	public int bakeLookId(BlockState state) {
		int id = this.ensure(bakeId(state, this));
		if (id < this.registrySize && (byte) KINDS.getAcquire(this.kind, id) == NOT_YET) {
			throw StatesRenumbered.INSTANCE;
		}
		return id;
	}

	/** See {@link #bakeId}. */
	public static final class StatesRenumbered extends RuntimeException {
		static final StatesRenumbered INSTANCE = new StatesRenumbered();

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
		// A material left unworked (states being numbered again) counts as none.
		if (frame != SOLID && frame != GLASS && frame != PLANT || this.kind[material] == AIR || this.kind[material] == NOT_YET) {
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

	/** A shape with more boxes than this counts as its bounding box. */
	private static final int MAX_BOXES = 16;

	/** Finds or adds the state's outline in {@link #shapes}; 0 for a full cube. Under the lock of {@link #make}. */
	private int shapeIndex(BlockState state) {
		Map<VoxelShape, Integer> byVoxels = this.shapeByVoxels;
		List<float[]> shapes = this.shapeList;
		VoxelShape outline;
		try {
			outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException | Error e) {
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
					// Room for more by doubling; the entry is published before the look that uses it.
					float[][] published = this.shapes;
					if (published.length < shapes.size()) {
						published = java.util.Arrays.copyOf(published, Math.max(16, published.length * 2));
					}
					published[shapes.size() - 1] = kept;
					this.shapes = published;
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
		// Leaves stay blocks when a mod lets players walk through them (Passable Foliage), waterlogged
		// ones too.
		boolean noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
			&& !(state.getBlock() instanceof LeavesBlock) && !state.is(BlockTags.LEAVES);
		if (!fluid.isEmpty() && noCollision) {
			return fluid.is(FluidTags.LAVA) ? LAVA : WATER;
		}
		if (noCollision) {
			return state.getBlock() instanceof SnowLayerBlock ? SOLID : PLANT;
		}
		return SOLID;
	}
}
