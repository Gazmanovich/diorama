package dev.diorama.compat;

import dev.diorama.Diorama;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.craft.Body;
import dev.diorama.data.Copycats;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Reads the ships of Create Aeronautics, and anything else built on the Sable physics mod, which
 * calls them sub-levels. A ship's blocks are ordinary blocks in ordinary chunks of the same level,
 * kept in a plot of its own some twenty million blocks from anywhere, and a pose says where in the
 * world that plot's blocks are shown, moved, turned and tilted.
 *
 * <p>Diorama doesn't build against Sable. If a name below isn't where this class expects it, it
 * logs once and stays off.
 */
public final class SubLevels {
	private static boolean started;
	private static boolean failed;
	private static boolean warned;
	private static Method getContainer;
	private static Method inBounds;
	private static Method all;
	private static Method uniqueId;
	private static Method removed;
	private static Method plot;
	private static Method logicalPose;
	private static Method renderPose;
	private static Method bounds;
	private static final Method[] CORNER = new Method[6];
	private static Method transform;
	/** The last level asked about and its container, as the map asks for every chunk it's told of. */
	private record Held(WeakReference<ClientLevel> level, Object container) {
	}

	private static volatile @Nullable Held held;
	/** Counts the block changes seen inside ships, so they're read again. */
	private static int edits;

	private SubLevels() {
	}

	private static boolean ready() {
		if (!started) {
			started = true;
			try {
				Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
				Class<?> ship = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
				Class<?> pose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
				Class<?> box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
				getContainer = containers.getMethod("getContainer", ClientLevel.class);
				inBounds = containers.getMethod("inBounds", int.class, int.class);
				all = containers.getMethod("getAllSubLevels");
				uniqueId = ship.getMethod("getUniqueId");
				removed = ship.getMethod("isRemoved");
				plot = ship.getMethod("getPlot");
				logicalPose = ship.getMethod("logicalPose");
				renderPose = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel").getMethod("renderPose", float.class);
				bounds = plot.getReturnType().getMethod("getBoundingBox");
				String[] corners = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
				for (int i = 0; i < 6; i++) {
					CORNER[i] = box.getMethod(corners[i]);
				}
				transform = pose.getMethod("transformPosition", Vec3.class);
			} catch (ClassNotFoundException e) {
				failed = true;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				fail(e);
			}
		}
		return !failed;
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read Sable's sub-levels; Create Aeronautics ships won't show on the map", e);
		}
	}

	/** One ship failing doesn't turn off the rest. */
	private static void skip(Exception e) {
		if (!warned) {
			warned = true;
			Diorama.LOGGER.warn("Couldn't read a Sable sub-level; skipping it", e);
		}
	}

	private static @Nullable Object container(ClientLevel level) throws ReflectiveOperationException {
		Held last = held;
		if (last != null && last.level.get() == level) {
			return last.container;
		}
		Object container = getContainer.invoke(null, level);
		if (container != null) {
			held = new Held(new WeakReference<>(level), container);
		}
		return container;
	}

	/**
	 * Whether a chunk is one of those ships keep their blocks in. Those aren't a place in the
	 * world, so the map leaves them alone: the ships are drawn where they fly instead.
	 */
	public static boolean holds(ClientLevel level, int chunkX, int chunkZ) {
		if (!ready()) {
			return false;
		}
		try {
			Object container = container(level);
			return container != null && (boolean) inBounds.invoke(container, chunkX, chunkZ);
		} catch (LinkageError e) {
			fail(e);
			return false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			skip(e);
			return false;
		}
	}

	/** A block that shows changed in a chunk that {@link #holds} a ship: the ships are read again. */
	public static void edited() {
		edits++;
	}

	/** The ships the client has in this level. */
	public static List<Body> bodies(ClientLevel level) {
		if (!ready()) {
			return List.of();
		}
		try {
			Object container = container(level);
			if (container == null || !(all.invoke(container) instanceof List<?> ships) || ships.isEmpty()) {
				return List.of();
			}
			List<Body> bodies = new ArrayList<>();
			for (Object ship : ships) {
				if (ship == null || !renderPose.getDeclaringClass().isInstance(ship) || (boolean) removed.invoke(ship)) {
					continue;
				}
				Object box = bounds.invoke(plot.invoke(ship));
				int[] corners = new int[6];
				for (int i = 0; i < 6; i++) {
					corners[i] = (int) CORNER[i].invoke(box);
				}
				// Sable's empty box has its corners the wrong way round: a ship whose blocks haven't arrived.
				if (corners[0] > corners[3] || corners[1] > corners[4] || corners[2] > corners[5] || !(uniqueId.invoke(ship) instanceof UUID id)) {
					continue;
				}
				bodies.add(new Ship(id, ship, level, corners, edits));
			}
			return bodies;
		} catch (LinkageError e) {
			fail(e);
			return List.of();
		} catch (ReflectiveOperationException | RuntimeException e) {
			skip(e);
			return List.of();
		}
	}

	/** {@code box} is the ship's blocks in its plot, min x y z then max x y z; {@code edits} the count when it was listed. */
	private record Ship(UUID id, Object ship, ClientLevel level, int[] box, int edits) implements Body {
		@Override
		public long version() {
			return 31L * Arrays.hashCode(this.box) + this.edits;
		}

		@Override
		public Body.@Nullable Blocks read(BlockLooks looks) {
			if (failed) {
				return null;
			}
			int x0 = this.box[0];
			int y0 = this.box[1];
			int z0 = this.box[2];
			int sx = this.box[3] - x0 + 1;
			int sy = this.box[4] - y0 + 1;
			int sz = this.box[5] - z0 + 1;
			if ((long) sx * sy * sz > Body.MAX_CELLS) {
				return null;
			}
			try {
				int[] states = CraftBlocks.empty(sx, sy, sz);
				Int2IntMap copycats = new Int2IntOpenHashMap();
				BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
				for (int cx = x0 >> 4; cx <= this.box[3] >> 4; cx++) {
					for (int cz = z0 >> 4; cz <= this.box[5] >> 4; cz++) {
						LevelChunk chunk = this.level.getChunk(cx, cz);
						LevelChunkSection[] sections = chunk.getSections();
						for (int s = 0; s < sections.length; s++) {
							LevelChunkSection section = sections[s];
							int bottom = chunk.getSectionYFromSectionIndex(s) << 4;
							if (section == null || section.hasOnlyAir() || bottom > this.box[4] || bottom + 15 < y0) {
								continue;
							}
							for (int y = Math.max(y0, bottom); y <= Math.min(this.box[4], bottom + 15); y++) {
								for (int z = Math.max(z0, cz << 4); z <= Math.min(this.box[5], (cz << 4) + 15); z++) {
									for (int x = Math.max(x0, cx << 4); x <= Math.min(this.box[3], (cx << 4) + 15); x++) {
										BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
										if (state.isAir()) {
											continue;
										}
										int index = CraftBlocks.index(sx, sz, x - x0, y - y0, z - z0);
										states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
										if (Copycats.INSTALLED && state.hasBlockEntity()) {
											BlockState material = Copycats.material(chunk.getBlockEntity(pos.set(x, y, z)));
											if (material != null) {
												copycats.put(index, Block.BLOCK_STATE_REGISTRY.getId(material));
											}
										}
									}
								}
							}
						}
					}
				}
				return new Body.Blocks(new CraftBlocks(sx, sy, sz, states, copycats), new int[] {x0, y0, z0});
			} catch (RuntimeException e) {
				skip(e);
				return null;
			}
		}

		@Override
		public boolean pose(int[] min, float partialTick, double[] out) {
			if (failed) {
				return false;
			}
			try {
				// Between ticks Sable draws a ship part way from its last pose to this one; so does the map.
				Object pose = partialTick >= 1 ? logicalPose.invoke(this.ship) : renderPose.invoke(this.ship, partialTick);
				double[] points = new double[12];
				for (int i = 0; i < 4; i++) {
					Vec3 p = (Vec3) transform.invoke(pose, new Vec3(min[0] + (i == 1 ? 1 : 0), min[1] + (i == 2 ? 1 : 0), min[2] + (i == 3 ? 1 : 0)));
					points[i * 3] = p.x;
					points[i * 3 + 1] = p.y;
					points[i * 3 + 2] = p.z;
				}
				System.arraycopy(points, 0, out, 0, 3);
				for (int i = 3; i < 12; i++) {
					out[i] = points[i] - points[i % 3];
				}
				return true;
			} catch (LinkageError e) {
				fail(e);
				return false;
			} catch (ReflectiveOperationException | RuntimeException e) {
				skip(e);
				return false;
			}
		}
	}
}
