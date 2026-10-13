package dev.orthomap;

import dev.orthomap.bake.BlockLooks;
import dev.orthomap.bake.TileKey;
import dev.orthomap.data.ChunkSnapshot;
import dev.orthomap.render.MapLevel;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import dev.orthomap.data.SeenBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The map state for the world the client is in: which dimension's {@link MapLevel} is live,
 * which chunks are waiting to be snapshotted, and whether the player is underground.
 */
public final class MapSession {
	/** Chunk light usually lands a moment after the blocks, so new chunks wait this long. */
	private static final long LOAD_DELAY_MS = 750;
	private static final long CHANGE_DELAY_MS = 250;
	private static final int SNAPSHOTS_PER_TICK = 24;

	private static @Nullable MapSession current;
	private static final boolean DEBUG_CHANGES = Orthomap.DEBUG;
	private static final Map<String, Integer> CHANGE_TALLY = new java.util.TreeMap<>();
	private static long lastTallyLog;

	private final ClientLevel level;
	private final MapLevel map;
	private final Map<Long, Pending> pending = new LinkedHashMap<>();
	private boolean underground;

	private MapSession(ClientLevel level) {
		this.level = level;
		this.map = new MapLevel(level.getMinY(), level.getMaxY() + 1);
	}

	public static @Nullable MapSession get() {
		return current;
	}

	/** Keeps the session in step with the client's level. Call every client tick. */
	public static void tick(Minecraft mc) {
		MapSession session = forLevel(mc.level);
		if (session == null) {
			return;
		}
		if (BlockLooks.get() == null) {
			BlockLooks.build();
		}
		session.tickSession(mc);
	}

	/** The session for this level, starting a new one if the client moved to it. */
	private static @Nullable MapSession forLevel(@Nullable ClientLevel level) {
		if (current != null && current.level != level) {
			current.map.close();
			current = null;
		}
		if (level != null && current == null) {
			current = new MapSession(level);
			BlockLooks.invalidate();
		}
		return current;
	}

	public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
		// Chunks can arrive before the first client tick of a new level, so this may start the session.
		MapSession session = forLevel(level);
		if (session != null) {
			session.schedule(chunk.getPos().x(), chunk.getPos().z(), LOAD_DELAY_MS, null);
		}
	}

	public static void blockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (current == null || current.level != level) {
			return;
		}
		BlockLooks looks = BlockLooks.get();
		int oldId = Block.BLOCK_STATE_REGISTRY.getId(oldState);
		int newId = Block.BLOCK_STATE_REGISTRY.getId(newState);
		if (looks != null && oldId >= 0 && newId >= 0 && looks.sameLook(oldId, newId)) {
			return;
		}
		if (DEBUG_CHANGES) {
			CHANGE_TALLY.merge(oldState.getBlock() + " -> " + newState.getBlock(), 1, Integer::sum);
			long now = System.currentTimeMillis();
			if (now - lastTallyLog > 10_000) {
				lastTallyLog = now;
				Orthomap.LOGGER.info("Visible block changes so far: {}", CHANGE_TALLY);
			}
		}
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		// Changes buried under the surface can't show in the sky view: it only sees exposed blocks.
		int exposedFrom = Integer.MAX_VALUE;
		for (int[] d : NEIGHBOURS) {
			exposedFrom = Math.min(exposedFrom, level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() + d[0], pos.getZ() + d[1]));
		}
		int surface = pos.getY() >= exposedFrom - 2 ? 1 : 0;
		int[] box = {pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), surface};
		current.schedule(cx, cz, CHANGE_DELAY_MS, box);
		// A light source changing relights the neighbouring chunks too.
		boolean light = oldState.getLightEmission() != newState.getLightEmission();
		int lx = pos.getX() & 15;
		int lz = pos.getZ() & 15;
		if (light || lx == 0) current.schedule(cx - 1, cz, CHANGE_DELAY_MS, box);
		if (light || lx == 15) current.schedule(cx + 1, cz, CHANGE_DELAY_MS, box);
		if (light || lz == 0) current.schedule(cx, cz - 1, CHANGE_DELAY_MS, box);
		if (light || lz == 15) current.schedule(cx, cz + 1, CHANGE_DELAY_MS, box);
	}

	private static final int[][] NEIGHBOURS = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/**
	 * Queues a chunk for a fresh snapshot. {@code box} is the changed blocks as
	 * {x0, y0, z0, x1, y1, z1, visibleFromSky}; null means the whole chunk is new.
	 */
	private void schedule(int chunkX, int chunkZ, long delayMs, int @Nullable [] box) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		long due = System.currentTimeMillis() + delayMs;
		Pending p = this.pending.get(key);
		if (p == null) {
			this.pending.put(key, new Pending(due, box == null ? null : box.clone()));
			return;
		}
		p.due = Math.min(p.due, due);
		if (p.box == null || box == null) {
			p.box = null;
		} else {
			for (int i = 0; i < 3; i++) {
				p.box[i] = Math.min(p.box[i], box[i]);
				p.box[i + 3] = Math.max(p.box[i + 3], box[i + 3]);
			}
			p.box[6] |= box[6];
		}
	}

	private static final class Pending {
		long due;
		int @Nullable [] box;

		Pending(long due, int @Nullable [] box) {
			this.due = due;
			this.box = box;
		}
	}

	private int ticks;

	/** Queues any loaded chunk the map hasn't seen, in case its load event was missed. */
	private void sweepLoadedChunks(LocalPlayer player) {
		int radius = mc().options.renderDistance().get() + 2;
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				if (this.map.chunk(cx, cz) == null && !this.pending.containsKey(ChunkSnapshot.key(cx, cz))
					&& this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
					this.schedule(cx, cz, 0, null);
				}
			}
		}
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private void tickSession(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player != null && this.ticks++ % 40 == 0) {
			this.sweepLoadedChunks(player);
		}
		int playerChunkX = player == null ? 0 : player.blockPosition().getX() >> 4;
		int playerChunkZ = player == null ? 0 : player.blockPosition().getZ() >> 4;
		long now = System.currentTimeMillis();
		int budget = SNAPSHOTS_PER_TICK;
		Iterator<Map.Entry<Long, Pending>> it = this.pending.entrySet().iterator();
		while (it.hasNext() && budget > 0) {
			Map.Entry<Long, Pending> entry = it.next();
			Pending p = entry.getValue();
			if (p.due > now) {
				continue;
			}
			it.remove();
			long key = entry.getKey();
			int cx = (int) (key >> 32);
			int cz = (int) key;
			if (this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk) {
				boolean known = this.map.chunk(cx, cz) != null;
				this.map.put(ChunkSnapshot.take(chunk, this.level), known ? p.box : null, playerChunkX, playerChunkZ);
				budget--;
			}
		}
		boolean fog = CaveRules.fogOfWar(this.level.dimension() == Level.NETHER);
		if (Orthomap.DEBUG && fog != this.map.fogOfWar()) {
			Orthomap.LOGGER.info("Fog of war {}; underground={}", fog, this.underground);
		}
		this.map.setFogOfWar(fog);
		this.map.update();
		if (player != null) {
			this.updateUnderground(player);
			if (this.underground) {
				this.updateSight(player);
			}
		}
	}

	private static final int SIGHT_RADIUS = 24;
	private static final int SIGHT_MAX_BLOCKS = 60_000;
	private @Nullable BlockPos lastSightOrigin;
	private int sightCooldown;

	/**
	 * Marks the blocks the player can see: spreads from their eyes through open space within
	 * {@link #SIGHT_RADIUS}, stopping at solid blocks, which count as seen themselves. Spreading
	 * cannot pass through rock, so a cave behind a wall stays hidden.
	 */
	private void updateSight(LocalPlayer player) {
		BlockLooks looks = BlockLooks.get();
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		if (looks == null || eye.equals(this.lastSightOrigin) && this.sightCooldown-- > 0) {
			return;
		}
		this.lastSightOrigin = eye;
		this.sightCooldown = 40;
		SeenBlocks seen = this.map.seen();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		LongOpenHashSet visited = new LongOpenHashSet();
		queue.enqueue(eye.asLong());
		visited.add(eye.asLong());
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int r2 = SIGHT_RADIUS * SIGHT_RADIUS;
		while (!queue.isEmpty() && visited.size() < SIGHT_MAX_BLOCKS) {
			long packed = queue.dequeueLong();
			int x = BlockPos.getX(packed);
			int y = BlockPos.getY(packed);
			int z = BlockPos.getZ(packed);
			reveal(seen, x, y, z, box);
			for (Direction d : DIRECTIONS) {
				int nx = x + d.getStepX();
				int ny = y + d.getStepY();
				int nz = z + d.getStepZ();
				int dx = nx - eye.getX();
				int dy = ny - eye.getY();
				int dz = nz - eye.getZ();
				if (dx * dx + dy * dy + dz * dz > r2 || ny < this.level.getMinY() || ny > this.level.getMaxY()) {
					continue;
				}
				long next = BlockPos.asLong(nx, ny, nz);
				if (!visited.add(next)) {
					continue;
				}
				int id = Block.BLOCK_STATE_REGISTRY.getId(this.level.getBlockState(pos.set(nx, ny, nz)));
				if (id >= 0 && looks.kind[id] == BlockLooks.SOLID) {
					reveal(seen, nx, ny, nz, box);
				} else {
					queue.enqueue(next);
				}
			}
		}
		if (Orthomap.DEBUG) {
			Orthomap.LOGGER.info("Sight from {}: visited {}, newly seen box {}", eye, visited.size(), java.util.Arrays.toString(box));
		}
		if (box[0] != Integer.MAX_VALUE) {
			this.map.revealed(box[0], box[1], box[2], box[3], box[4], box[5]);
		}
	}

	private static final Direction[] DIRECTIONS = Direction.values();

	private static void reveal(SeenBlocks seen, int x, int y, int z, int[] box) {
		if (seen.markSeen(x, y, z)) {
			box[0] = Math.min(box[0], x);
			box[1] = Math.min(box[1], y);
			box[2] = Math.min(box[2], z);
			box[3] = Math.max(box[3], x);
			box[4] = Math.max(box[4], y);
			box[5] = Math.max(box[5], z);
		}
	}

	private void updateUnderground(LocalPlayer player) {
		if (this.level.dimensionType().hasCeiling()) {
			this.underground = true;
			return;
		}
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		int sky = this.level.getBrightness(LightLayer.SKY, eye);
		boolean covered = !this.level.canSeeSky(eye);
		// Separate thresholds so walking along a cave mouth doesn't flip the map back and forth.
		if (this.underground) {
			this.underground = covered && sky < 10;
		} else {
			this.underground = covered && sky < 5;
		}
	}

	public MapLevel map() {
		return this.map;
	}

	public boolean isUnderground() {
		return this.underground;
	}

	/** The cave slice for a player standing at {@code feetY}: a few blocks above their head. */
	public static int caveLayerFor(double feetY) {
		return Math.floorDiv(Mth.floor(feetY) + 5, 4) * 4;
	}

	/** The layer the minimap should show right now. */
	public int autoLayer(LocalPlayer player) {
		return this.underground ? caveLayerFor(player.getY()) : TileKey.SURFACE;
	}
}
