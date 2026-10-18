package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.MapLevel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.player.LocalPlayer;
import dev.diorama.data.SeenBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
	private static final boolean DEBUG_CHANGES = Diorama.DEBUG;
	private static final Map<String, Integer> CHANGE_TALLY = new java.util.TreeMap<>();
	private static long lastTallyLog;

	private final ClientLevel level;
	/** The world folder this map saves into, see {@link WorldKey}. */
	private final @Nullable String key;
	private final MapLevel map;
	private final Map<Long, Pending> pending = new LinkedHashMap<>();
	private boolean underground;
	private MapStyle style = MapStyle.get();

	private MapSession(ClientLevel level, @Nullable String key) {
		this.level = level;
		this.key = key;
		// The Nether's roof: its sky view looks down from just under it.
		int roof = level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() : MapLevel.NO_ROOF;
		this.map = new MapLevel(level.getMinY(), level.getMaxY() + 1, roof, mapFolder(level, key));
	}

	/** {@code .minecraft/diorama/<world>/<dimension>}, or null outside a world. */
	private static @Nullable Path mapFolder(ClientLevel level, @Nullable String key) {
		if (key == null) {
			return null;
		}
		return WorldKey.folder(key).resolve(WorldKey.key("dim", level.dimension().identifier().toString()));
	}

	public static @Nullable MapSession get() {
		return current;
	}

	/**
	 * The world seed, which places slime chunks. Only known in singleplayer, and slime chunks
	 * only exist in the overworld.
	 */
	public @Nullable Long slimeSeed() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || this.level.dimension() != Level.OVERWORLD) {
			return null;
		}
		return server.overworld().getSeed();
	}

	/** Saves and ends the session. For closing the game; leaving a world is noticed by {@link #tick}. */
	public static void close() {
		if (current != null) {
			current.map.close();
			current = null;
		}
	}

	/**
	 * Ends the session without saving if it isn't saving into this world folder: the server named
	 * the world after the map opened. The next session maps the loaded chunks again.
	 */
	public static void discardUnless(String key) {
		if (current != null && !key.equals(current.key)) {
			current.map.discard();
			current = null;
		}
	}

	/** Keeps the session in step with the client's level. Call every client tick. */
	public static void tick(Minecraft mc) {
		MapSession session = forLevel(mc.level);
		if (session == null) {
			return;
		}
		if (BlockLooks.get() == null) {
			BlockLooks.build();
		} else if (session.style != MapStyle.get()) {
			session.style = MapStyle.get();
			session.map.colorsChanged();
		} else if (BlockLooks.isStale()) {
			BlockLooks.build();
			session.map.colorsChanged();
			// Snapshots hold biome tints from the old colormaps. Retake every loaded chunk;
			// chunks no longer loaded keep their old tints until you come back.
			session.resnapshotLoaded();
		}
		session.tickSession(mc);
	}

	/**
	 * The session for this level, starting a new one if the client moved to it. None while the
	 * server may still name the world: its chunks are mapped once the folder is known.
	 */
	private static @Nullable MapSession forLevel(@Nullable ClientLevel level) {
		if (current != null && current.level != level) {
			current.map.close();
			current = null;
		}
		if (level != null && current == null) {
			String key = WorldKey.current(level);
			if (key == null && WorldKey.waiting(level)) {
				return null;
			}
			current = new MapSession(level, key);
			BlockLooks.invalidate();
		}
		return current;
	}

	public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
		// Chunks can arrive before the first client tick of a new level, so this may start the session.
		MapSession session = forLevel(level);
		if (session == null) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		session.schedule(cx, cz, LOAD_DELAY_MS, null);
		// A neighbour snapshotted before this chunk loaded blended its tints along this edge against
		// the game's fallback biome. Snapshot it again and re-bake that strip, as the game itself
		// recolours its blocks there.
		int reach = ChunkSnapshot.BIOME_REACH;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkSnapshot around = session.map.chunk(cx + dx, cz + dz);
				if ((dx == 0 && dz == 0) || around == null || around.hasBiomesAround(-dx, -dz)) {
					continue;
				}
				int x0 = around.chunkX << 4;
				int z0 = around.chunkZ << 4;
				int[] strip = {
					dx < 0 ? x0 + 16 - reach : x0, level.getMinY(), dz < 0 ? z0 + 16 - reach : z0,
					dx > 0 ? x0 + reach - 1 : x0 + 15, level.getMaxY(), dz > 0 ? z0 + reach - 1 : z0 + 15,
					MapLevel.CHANGED_SURFACE
				};
				session.schedule(around.chunkX, around.chunkZ, CHANGE_DELAY_MS, strip);
			}
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
				Diorama.LOGGER.info("Visible block changes so far: {}", CHANGE_TALLY);
			}
		}
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		int surface = current.visibleFromSky(pos) ? MapLevel.CHANGED_SURFACE : 0;
		int[] box = {pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), surface};
		current.schedule(cx, cz, CHANGE_DELAY_MS, box);
		// A light source changing relights every neighbouring chunk, diagonals included. Other
		// edits only reach a neighbour through shading that reads across the border.
		boolean light = oldState.getLightEmission() != newState.getLightEmission();

		int lx = pos.getX() & 15;
		int lz = pos.getZ() & 15;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean touches = (dx == 0 || dx == -1 && lx == 0 || dx == 1 && lx == 15)
					&& (dz == 0 || dz == -1 && lz == 0 || dz == 1 && lz == 15);
				if ((dx != 0 || dz != 0) && (light || touches)) {
					current.schedule(cx + dx, cz + dz, CHANGE_DELAY_MS, box);
				}
			}
		}
	}

	/**
	 * The server sent new light for a chunk. Cave slices draw light, and so do spawn spots, so the
	 * surface re-bakes too while they're on.
	 */
	/**
	 * The client's light engine changed a section's light, whether from a server packet or its
	 * own work (a torch placed, a wall opened, a roof closed). Cave slices draw light, and so do
	 * spawn spots, so the surface re-bakes too while they're on.
	 */
	public static void lightSectionChanged(ClientChunkCache source, SectionPos section) {
		if (current != null && current.level.getChunkSource() == source) {
			int x0 = section.minBlockX();
			int y0 = section.minBlockY();
			int z0 = section.minBlockZ();
			int surface = current.map.spawnSpots() || current.map.roofless() ? MapLevel.CHANGED_SURFACE_LIGHT : 0;
			current.schedule(section.x(), section.z(), CHANGE_DELAY_MS, new int[] {x0, y0, z0, x0 + 15, y0 + 15, z0 + 15, surface});
		}
	}

	public static void lightUpdated(ClientLevel level, int chunkX, int chunkZ) {
		if (current != null && current.level == level) {
			int x0 = chunkX << 4;
			int z0 = chunkZ << 4;
			int surface = current.map.spawnSpots() || current.map.roofless() ? MapLevel.CHANGED_SURFACE_LIGHT : 0;
			current.schedule(chunkX, chunkZ, CHANGE_DELAY_MS, new int[] {x0, level.getMinY(), z0, x0 + 15, level.getMaxY(), z0 + 15, surface});
		}
	}

	/**
	 * Whether a changed block can affect the sky view. A camera ray only reaches a block through
	 * open air connected to the sky, and vanilla sky light already tracks exactly that space, so
	 * a block counts if it or any neighbour has sky light. This is deliberately loose: a few
	 * changes the camera can't see still count, but buried ones (water and lava settling in
	 * sealed caves) don't. It misses only blocks more than 15 blocks under an overhang.
	 */
	private boolean visibleFromSky(BlockPos pos) {
		if (!this.level.dimensionType().hasSkyLight()) {
			return true;
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		if (this.level.getBrightness(LightLayer.SKY, pos) > 0) {
			return true;
		}
		for (Direction d : DIRECTIONS) {
			if (this.level.getBrightness(LightLayer.SKY, p.setWithOffset(pos, d)) > 0) {
				return true;
			}
		}
		return false;
	}

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

	private void resnapshotLoaded() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		int radius = mc().options.renderDistance().get() + 2;
		int pcx = player.blockPosition().getX() >> 4;
		int pcz = player.blockPosition().getZ() >> 4;
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				if (this.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
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
		if (this.ticks % 600 == 0) {
			this.map.saveSeen();
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
		boolean fog = ServerRules.fogOfWar(this.level.dimension() == Level.NETHER);
		if (Diorama.DEBUG && fog != this.map.fogOfWar()) {
			Diorama.LOGGER.info("Fog of war {}; underground={}", fog, this.underground);
		}
		this.map.setFogOfWar(fog);
		Settings settings = Settings.get();
		boolean spawnSpots = settings.spawnSpots != Settings.SpawnSpots.OFF;
		boolean overlays = settings.chunkGrid || settings.slimeChunks || spawnSpots;
		this.map.setOverlays(overlays ? new Overlays(this.slimeSeed(), spawnSpots) : null);
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
	 * Marks the blocks the player can see. Spreads from their eyes through open space within
	 * {@link #SIGHT_RADIUS}, but only into blocks a straight line from the eyes reaches without
	 * passing through a solid block. Solid blocks on that line count as seen; the spread stops
	 * there. So neither a cave behind a wall nor a room around a bend gets revealed.
	 */
	private void updateSight(LocalPlayer player) {
		BlockLooks looks = BlockLooks.get();
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		if (looks == null || eye.equals(this.lastSightOrigin) && this.sightCooldown-- > 0) {
			return;
		}
		this.lastSightOrigin = eye;
		this.sightCooldown = 40;
		Vec3 eyes = player.getEyePosition();
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
				if (!visited.add(next) || !this.inSight(looks, eyes, nx, ny, nz)) {
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
		if (Diorama.DEBUG) {
			Diorama.LOGGER.info("Sight from {}: visited {}, newly seen box {}", eye, visited.size(), java.util.Arrays.toString(box));
		}
		if (box[0] != Integer.MAX_VALUE) {
			this.map.revealed(box[0], box[1], box[2], box[3], box[4], box[5]);
		}
	}

	private static final Direction[] DIRECTIONS = Direction.values();

	/** True if the line from the eyes to the block's centre passes only through open blocks. */
	private boolean inSight(BlockLooks looks, Vec3 eyes, int tx, int ty, int tz) {
		double dx = tx + 0.5 - eyes.x;
		double dy = ty + 0.5 - eyes.y;
		double dz = tz + 0.5 - eyes.z;
		int x = Mth.floor(eyes.x);
		int y = Mth.floor(eyes.y);
		int z = Mth.floor(eyes.z);
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - eyes.x : eyes.x - x) * deltaX;
		double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - eyes.y : eyes.y - y) * deltaY;
		double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - eyes.z : eyes.z - z) * deltaZ;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 4 * SIGHT_RADIUS; i++) {
			if (maxX < maxY && maxX < maxZ) {
				x += stepX;
				maxX += deltaX;
			} else if (maxY < maxZ) {
				y += stepY;
				maxY += deltaY;
			} else {
				z += stepZ;
				maxZ += deltaZ;
			}
			if (x == tx && y == ty && z == tz) {
				return true;
			}
			int id = Block.BLOCK_STATE_REGISTRY.getId(this.level.getBlockState(pos.set(x, y, z)));
			if (id >= 0 && looks.kind[id] == BlockLooks.SOLID) {
				return false;
			}
		}
		return false;
	}

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
