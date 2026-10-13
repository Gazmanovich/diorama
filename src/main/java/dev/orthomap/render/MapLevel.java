package dev.orthomap.render;

import dev.orthomap.Orthomap;
import dev.orthomap.Projection;
import dev.orthomap.bake.BlockLooks;
import dev.orthomap.bake.ChunkSource;
import dev.orthomap.bake.TileBaker;
import dev.orthomap.bake.TileImage;
import dev.orthomap.bake.TileKey;
import dev.orthomap.data.ChunkSnapshot;
import dev.orthomap.data.SeenBlocks;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Everything the map knows about one dimension: chunk snapshots, baked tiles, and which tiles
 * need baking. Tiles are baked on demand when a view asks for them, and re-baked shortly after
 * the chunks under them change.
 */
public final class MapLevel implements ChunkSource {
	private static final int MAX_CHUNKS = 4096;
	private static final int MAX_GPU_TILES = 192;
	/** A changed tile waits this long for more changes before re-baking... */
	private static final long QUIET_MS = 350;
	/** ...but never longer than this. */
	private static final long MAX_WAIT_MS = 1500;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService BAKERS = Executors.newFixedThreadPool(
		Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)),
		task -> {
			Thread thread = new Thread(task, "orthomap-baker-" + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		}
	);

	private final int minY;
	private final int maxY;
	private final ConcurrentHashMap<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
	private volatile int topY = Integer.MIN_VALUE;
	private final SeenBlocks seen = new SeenBlocks();
	private volatile boolean fogOfWar;
	/**
	 * Bumped whenever tiles baked before stop being valid: new block colours, or cave slices
	 * switching to or from fog of war. Bakes that started under an older value are dropped.
	 */
	private int surfaceGeneration;
	private int caveGeneration;

	// Render thread only below here.
	private final Map<TileKey, MapTile> tiles = new HashMap<>();
	private final Map<TileKey, Dirty> dirty = new HashMap<>();
	private final Map<TileKey, Double> wanted = new HashMap<>();
	private final Set<TileKey> inFlight = new HashSet<>();
	private final ConcurrentLinkedQueue<TileImage> finished = new ConcurrentLinkedQueue<>();
	private long frame;
	private volatile boolean closed;

	public MapLevel(int minY, int maxY) {
		this.minY = minY;
		this.maxY = maxY;
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		return this.chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
	}

	@Override
	public int minY() {
		return this.minY;
	}

	@Override
	public int maxY() {
		return this.maxY;
	}

	@Override
	public int topY() {
		return this.topY;
	}

	public boolean fogOfWar() {
		return this.fogOfWar;
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		return this.seen.isSeen(x, y, z);
	}

	public SeenBlocks seen() {
		return this.seen;
	}

	/** Switches cave slices between full and fog of war, dropping cave tiles baked the other way. */
	public void setFogOfWar(boolean value) {
		if (this.fogOfWar == value) {
			return;
		}
		this.fogOfWar = value;
		this.caveGeneration++;
		this.dropTiles(true);
	}

	/** Block colours changed (a resource reload): every tile has to be baked again. */
	public void colorsChanged() {
		this.surfaceGeneration++;
		this.caveGeneration++;
		this.dropTiles(false);
	}

	private void dropTiles(boolean cavesOnly) {
		Iterator<Map.Entry<TileKey, MapTile>> it = this.tiles.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			if (!cavesOnly || e.getKey().isCave()) {
				e.getValue().close();
				it.remove();
				this.dirty.remove(e.getKey());
			}
		}
	}

	private int generation(TileKey key) {
		return key.isCave() ? this.caveGeneration : this.surfaceGeneration;
	}

	/** Newly seen blocks: cave slices around them can show more now. */
	public void revealed(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (this.fogOfWar) {
			this.invalidate(x0, y0, y0, z0, x1, y1, z1, false);
		}
	}

	/** Light changed in these sections; only cave slices use block and sky light. */
	public void lightChanged(int chunkX, int chunkZ, int y0, int y1) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		this.invalidate(x0, y0, y0, z0, x0 + 15, y1, z0 + 15, false);
	}

	public int chunkCount() {
		return this.chunks.size();
	}

	/**
	 * Stores a fresh snapshot and marks the parts of tiles that can look different because of it.
	 *
	 * @param changed the blocks that changed, as {x0, y0, z0, x1, y1, z1} inclusive plus a last
	 *     element that is 1 if any of them can be seen from the sky; null when the whole chunk is new
	 */
	public void put(ChunkSnapshot snapshot, int @Nullable [] changed, int playerChunkX, int playerChunkZ) {
		this.chunks.put(snapshot.key(), snapshot);
		if (snapshot.maxY != Integer.MIN_VALUE && snapshot.maxY + 1 > this.topY) {
			this.topY = Math.min(this.maxY, snapshot.maxY + 1);
		}
		if (changed == null) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			// Rays from any height could have stopped at this chunk while it was unknown. The sky
			// view only reaches down to the surface; cave slices can be anywhere below it.
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.topY, z0 + 15, true);
		} else {
			this.invalidate(changed[0], changed[1], changed[1], changed[2], changed[3], changed[4], changed[5], changed[6] != 0);
		}
		if (this.chunks.size() > MAX_CHUNKS) {
			this.trimChunks(playerChunkX, playerChunkZ);
		}
	}

	/** Light from a changed block reaches this far, which matters for cave slices. */
	private static final int LIGHT_REACH = 15;

	/**
	 * Marks tile blocks that can look different after blocks in this box changed. {@code y0} is the
	 * bottom of the box for the sky view and {@code caveY0} for cave slices.
	 */
	private void invalidate(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1, boolean visibleFromSky) {
		Set<Integer> layers = new HashSet<>();
		for (TileKey key : this.tiles.keySet()) {
			layers.add(key.layer());
		}
		for (TileKey key : this.inFlight) {
			layers.add(key.layer());
		}
		long now = System.currentTimeMillis();
		for (int layer : layers) {
			boolean cave = layer != TileKey.SURFACE;
			if (!cave && !visibleFromSky) {
				continue;
			}
			int reachX = cave ? LIGHT_REACH : TileBaker.SHADOW_REACH_X;
			int reachZ = cave ? LIGHT_REACH : TileBaker.SHADOW_REACH_Z;
			int reachY = cave ? LIGHT_REACH : TileBaker.SHADOW_REACH_Y;
			double top = y1 + 2 + (cave ? LIGHT_REACH : 0);
			double bottom = (cave ? caveY0 : y0) - 1 - reachY;
			if (cave) {
				top = Math.min(top, layer);
				bottom = Math.max(bottom, layer - TileBaker.CAVE_DEPTH - 1);
				if (top < bottom) {
					continue;
				}
			}
			double u0 = x0 - 1 - (cave ? LIGHT_REACH : 0);
			double u1 = x1 + 2 + reachX;
			double v0 = Projection.v(top, z0 - 1 - reachZ);
			double v1 = Projection.v(bottom, z1 + 2 + (cave ? LIGHT_REACH : 0));
			this.markDirty(layer, u0, v0, u1, v1, now);
		}
	}

	private void markDirty(int layer, double u0, double v0, double u1, double v1, long now) {
		double units = Projection.TILE_UNITS;
		double blockUnits = (double) TileImage.BLOCK / Projection.PIXELS_PER_UNIT;
		int perRow = Projection.TILE_PIXELS / TileImage.BLOCK;
		int tu0 = (int) Math.floor(u0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tv1 = (int) Math.floor(v1 / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(layer, tu, tv);
				if (!this.tiles.containsKey(key) && !this.inFlight.contains(key)) {
					continue;
				}
				int bx0 = Math.max(0, (int) Math.floor((u0 - tu * units) / blockUnits));
				int bx1 = Math.min(perRow - 1, (int) Math.floor((u1 - tu * units) / blockUnits));
				int by0 = Math.max(0, (int) Math.floor((v0 - tv * units) / blockUnits));
				int by1 = Math.min(perRow - 1, (int) Math.floor((v1 - tv * units) / blockUnits));
				Dirty d = this.dirty.computeIfAbsent(key, k -> new Dirty(now));
				d.last = now;
				for (int by = by0; by <= by1; by++) {
					d.blocks.set(by * perRow + bx0, by * perRow + bx1 + 1);
				}
			}
		}
	}

	/** Blocks of a tile waiting to be re-baked, and since when. */
	private static final class Dirty {
		final BitSet blocks = new BitSet();
		final long first;
		long last;

		Dirty(long now) {
			this.first = now;
			this.last = now;
		}
	}

	private void trimChunks(int playerChunkX, int playerChunkZ) {
		List<ChunkSnapshot> all = new ArrayList<>(this.chunks.values());
		all.sort(Comparator.comparingLong(s -> -(sq(s.chunkX - playerChunkX) + sq(s.chunkZ - playerChunkZ))));
		for (int i = 0; i < all.size() - MAX_CHUNKS * 3 / 4; i++) {
			this.chunks.remove(all.get(i).key());
		}
	}

	private static long sq(long v) {
		return v * v;
	}

	/**
	 * Returns the tile for drawing if one has been baked, and records that a view wants it.
	 * {@code priority} is lower for tiles that matter more, such as those near the view center.
	 */
	public @Nullable MapTile want(TileKey key, double priority) {
		this.wanted.merge(key, priority, Math::min);
		MapTile tile = this.tiles.get(key);
		if (tile != null) {
			tile.lastDrawn = this.frame;
		}
		return tile;
	}

	/** The baked tile, if any, without asking for it to be baked. */
	public @Nullable MapTile peek(TileKey key) {
		return this.tiles.get(key);
	}

	/** Uploads finished bakes and starts new ones. Call regularly on the render thread. */
	public void update() {
		this.frame++;
		TileImage image;
		while ((image = this.finished.poll()) != null) {
			this.inFlight.remove(image.key());
			if (this.closed) {
				continue;
			}
			if (image.generation() != this.generation(image.key())) {
				// Baked under old colours or the other cave mode; bake it again.
				MapTile stale = this.tiles.remove(image.key());
				if (stale != null) {
					stale.close();
				}
				continue;
			}
			MapTile tile = this.tiles.get(image.key());
			if (tile == null) {
				if (!image.isFull()) {
					// The tile was evicted while its patch baked.
					continue;
				}
				tile = new MapTile();
				this.tiles.put(image.key(), tile);
			}
			tile.upload(image);
		}

		BlockLooks looks = BlockLooks.get();
		if (looks != null && this.topY != Integer.MIN_VALUE) {
			long now = System.currentTimeMillis();
			int limit = 6;
			List<Map.Entry<TileKey, Double>> order = new ArrayList<>(this.wanted.entrySet());
			order.sort(Map.Entry.comparingByValue());
			for (Map.Entry<TileKey, Double> entry : order) {
				if (this.inFlight.size() >= limit) {
					break;
				}
				TileKey key = entry.getKey();
				if (this.inFlight.contains(key)) {
					continue;
				}
				boolean missing = !this.tiles.containsKey(key);
				Dirty d = this.dirty.get(key);
				boolean ready = d != null && (now - d.last > QUIET_MS || now - d.first > MAX_WAIT_MS);
				if (missing || ready) {
					this.dirty.remove(key);
					this.inFlight.add(key);
					BitSet blocks = missing || d.blocks.cardinality() > 160 ? null : d.blocks;
					int generation = this.generation(key);
					boolean fog = this.fogOfWar;
					BAKERS.execute(() -> {
						try {
							long start = System.nanoTime();
							this.finished.add(TileBaker.bake(key, this, looks, blocks, fog).withGeneration(generation));
							logBakeTime(System.nanoTime() - start, blocks == null);
						} catch (Throwable t) {
							Orthomap.LOGGER.error("Failed to bake tile {}", key, t);
							this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
						}
					});
				}
			}
		}
		this.wanted.clear();
		this.evict();
	}

	private static final java.util.concurrent.atomic.AtomicLong BAKE_NANOS = new java.util.concurrent.atomic.AtomicLong();
	private static final AtomicInteger BAKE_COUNT = new AtomicInteger();

	private static final AtomicInteger PARTIAL_COUNT = new AtomicInteger();

	private static void logBakeTime(long nanos, boolean full) {
		if (!full) {
			if (PARTIAL_COUNT.incrementAndGet() % 50 == 0) {
				Orthomap.LOGGER.info("Re-baked {} partial tiles", PARTIAL_COUNT.get());
			}
			return;
		}
		long total = BAKE_NANOS.addAndGet(nanos);
		int count = BAKE_COUNT.incrementAndGet();
		if (count % 25 == 0) {
			Orthomap.LOGGER.info("Baked {} full tiles, {} ms average", count, total / count / 1_000_000);
		}
	}

	private void evict() {
		int resident = 0;
		for (MapTile tile : this.tiles.values()) {
			if (!tile.isEmpty()) {
				resident++;
			}
		}
		if (resident <= MAX_GPU_TILES && this.frame % 600 != 0) {
			return;
		}
		List<Map.Entry<TileKey, MapTile>> entries = new ArrayList<>(this.tiles.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue().lastDrawn));
		Iterator<Map.Entry<TileKey, MapTile>> it = entries.iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			boolean stale = this.frame - e.getValue().lastDrawn > 3600;
			if (resident <= MAX_GPU_TILES * 3 / 4 && !stale) {
				break;
			}
			if (!e.getValue().isEmpty()) {
				resident--;
			}
			e.getValue().close();
			this.tiles.remove(e.getKey());
			this.dirty.remove(e.getKey());
		}
	}

	public void close() {
		this.closed = true;
		for (MapTile tile : this.tiles.values()) {
			tile.close();
		}
		this.tiles.clear();
		this.chunks.clear();
	}

	public int bakingCount() {
		return this.inFlight.size();
	}
}
