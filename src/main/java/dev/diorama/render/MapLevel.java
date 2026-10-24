package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.PinnedSource;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileSpots;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SeenBlocks;
import dev.diorama.data.TileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
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
	private static final int BAKER_COUNT = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
	private static final ExecutorService BAKERS = Executors.newFixedThreadPool(
		BAKER_COUNT,
		task -> {
			Thread thread = new Thread(task, "diorama-baker-" + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		}
	);
	/**
	 * Bakes the GPU self-check's tiles both ways, one at a time, beside the bakers: the map gets
	 * those tiles traced as usual, so checking never makes it wait.
	 */
	private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-gpu-check");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private final int minY;
	private final int maxY;
	private final ConcurrentHashMap<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
	private volatile int topY = Integer.MIN_VALUE;
	private final SeenBlocks seen;
	/** Where this dimension's map is saved, or null when there's no world to save it for. */
	private final @Nullable Path folder;
	/** Saved tiles for the current camera angle, or null when nothing is saved. Render thread. */
	private @Nullable TileStore store;
	/** The folder {@link #store} saves into; see {@link #storeName()}. */
	private String storeName = "";
	private volatile boolean fogOfWar;
	/** What bakes record for the map overlays, or null when no overlay is on. */
	private volatile @Nullable Overlays overlays;
	/**
	 * Bumped whenever tiles baked before stop being valid: new block colours, or cave slices
	 * switching to or from fog of war. Bakes that started under an older value are dropped.
	 * Volatile: the GPU baking check reads them from baker threads.
	 */
	private volatile int surfaceGeneration;
	private volatile int caveGeneration;

	// Render thread only below here.
	private final Map<TileKey, MapTile> tiles = new HashMap<>();
	/** Goes up whenever a tile's coverage changes or a tile comes or goes; see {@link #coverageVersion()}. */
	private long coverageVersion;
	private final Map<TileKey, Dirty> dirty = new HashMap<>();
	private final Map<TileKey, Double> wanted = new HashMap<>();
	private final Set<TileKey> inFlight = new HashSet<>();
	private final ConcurrentLinkedQueue<TileImage> finished = new ConcurrentLinkedQueue<>();
	/** Overview tiles the writer rebuilt; reloaded when next wanted. */
	private final ConcurrentLinkedQueue<TileKey> overviewChanged = new ConcurrentLinkedQueue<>();
	/** Overview tiles with nothing saved under them; not asked for again until that changes. */
	private final Set<TileKey> emptyOverviews = new HashSet<>();
	/**
	 * Surface tiles whose chunks changed since the tile was last baked, and when. They're baked in
	 * the background and saved, so the saved map covers everywhere the player has been, not only
	 * the parts some map happened to show.
	 */
	private final Map<TileKey, Long> unsaved = new HashMap<>();
	/** Background bakes in flight: saved when done, but not kept on the GPU. */
	private final Set<TileKey> backgroundBakes = new HashSet<>();
	/** A changed tile waits this long for more changes before a background bake. */
	private static final long BACKGROUND_QUIET_MS = 2000;
	/**
	 * Set when a trim couldn't get the snapshots down because their tiles still wait to be saved.
	 * New snapshots then don't start another try each; the next one comes {@link #TRIM_RETRY_MS}
	 * later, since a trim looks at every snapshot.
	 */
	private boolean trimStuck;
	private long lastTrimMs;
	private static final long TRIM_RETRY_MS = 1000;
	/** Where the player was at the last {@link #put}; trims keep the snapshots nearest it. */
	private int playerChunkX;
	private int playerChunkZ;
	private long frame;
	private volatile boolean closed;

	/** See {@link ChunkSource#roof()}. */
	private final int roof;

	public MapLevel(int minY, int maxY, int roof, @Nullable Path folder) {
		this.minY = minY;
		this.maxY = maxY;
		this.roof = roof;
		this.folder = folder;
		this.store = this.openStore();
		this.seen = folder == null ? new SeenBlocks() : SeenBlocks.load(folder.resolve("seen.bin"));
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

	@Override
	public int roof() {
		return this.roof;
	}

	/** Whether the sky view is drawn from under a roof, lit as if the rock over the first open space were lifted off. */
	public boolean roofless() {
		return this.roof != NO_ROOF;
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
		// Under a roof the sky view follows fog of war too, and keeps its own saved tiles for it.
		this.switchStoreIfNeeded();
	}

	/**
	 * What bakes record for the overlays. Turning overlays on, turning spawn spots on, or a new
	 * slime seed bakes the tiles in view again, including any bake already under way, so they
	 * record current bits. Switching between overlays a bake already recorded needs no bake.
	 */
	public void setOverlays(@Nullable Overlays overlays) {
		if (java.util.Objects.equals(overlays, this.overlays)) {
			return;
		}
		this.overlays = overlays;
		if (overlays == null) {
			return;
		}
		int blocks = (Projection.TILE_PIXELS / TileImage.BLOCK) * (Projection.TILE_PIXELS / TileImage.BLOCK);
		Set<TileKey> keys = new HashSet<>(this.tiles.keySet());
		keys.addAll(this.inFlight);
		for (TileKey key : keys) {
			if (key.lod() == 0) {
				this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, blocks);
			}
		}
	}

	/** Whether spawn spots are recorded, so light changes have to refresh the surface. */
	public boolean spawnSpots() {
		Overlays o = this.overlays;
		return o != null && o.spawnSpots();
	}

	/**
	 * The saved tiles for the current camera angle. Where a tile's pixels land depends on the
	 * angle, so tiles saved at one angle would merge into the wrong places at another.
	 */
	private @Nullable TileStore openStore() {
		if (this.folder == null) {
			return null;
		}
		this.storeName = this.storeName();
		TileStore opened = new TileStore(this.folder.resolve(this.storeName));
		opened.onOverviewChanged(this.overviewChanged::add);
		return opened;
	}

	/**
	 * Where the sky view's tiles are saved: one folder per kind of view, since their pixels mean
	 * different things and saved pixels are merged into fresh bakes. The camera angle moves where
	 * pixels land. Under a roof, the view is a different picture, and with fog of war it may only
	 * hold what the player has seen. 60 degrees was the only angle before others existed, and its
	 * sky view tiles are in "surface".
	 */
	private String storeName() {
		long pitch = Math.round(dev.diorama.MapStyle.get().pitchDegrees());
		if (this.roofless()) {
			return "under-roof-" + pitch + (this.fogOfWar ? "-fog" : "");
		}
		return pitch == 60 ? "surface" : "surface-" + pitch;
	}

	/** Moves to another folder if the kind of view changed, and queues everything loaded to be saved there. */
	private void switchStoreIfNeeded() {
		if (this.store == null || this.storeName().equals(this.storeName)) {
			return;
		}
		this.store.close();
		this.store = this.openStore();
		this.unsaved.clear();
		this.emptyOverviews.clear();
		for (ChunkSnapshot snapshot : this.chunks.values()) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.topY, z0 + 15, true, true);
		}
	}

	/** Block colours or the map style changed: every tile has to be baked again. */
	public void colorsChanged() {
		this.surfaceGeneration++;
		this.caveGeneration++;
		this.dropTiles(false);
		this.switchStoreIfNeeded();
	}

	private void dropTiles(boolean cavesOnly) {
		Iterator<Map.Entry<TileKey, MapTile>> it = this.tiles.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			if (!cavesOnly || e.getKey().isCave() || this.roofless()) {
				e.getValue().close();
				it.remove();
				this.dirty.remove(e.getKey());
				this.coverageVersion++;
			}
		}
	}

	/**
	 * A number that changes whenever what {@link #peek} returns could show different coverage,
	 * so a view can keep its fog mask until it does.
	 */
	public long coverageVersion() {
		return this.coverageVersion;
	}

	private int generation(TileKey key) {
		return key.isCave() || this.roofless() ? this.caveGeneration : this.surfaceGeneration;
	}

	/** Newly seen blocks: cave slices around them can show more now. */
	public void revealed(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (this.fogOfWar) {
			this.invalidate(x0, y0, y0, z0, x1, y1, z1, false, true);
		}
	}

	/** Light changed in these sections; only cave slices use block and sky light. */
	public void lightChanged(int chunkX, int chunkZ, int y0, int y1) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		this.invalidate(x0, y0, y0, z0, x0 + 15, y1, z0 + 15, false, true);
	}

	public int chunkCount() {
		return this.chunks.size();
	}

	/**
	 * Stores a fresh snapshot and marks the parts of tiles that can look different because of it.
	 *
	 * @param changed the blocks that changed, as {x0, y0, z0, x1, y1, z1} inclusive plus a last
	 *     element of {@link #CHANGED_SURFACE} and {@link #CHANGED_SURFACE_LIGHT} flags; null when
	 *     the whole chunk is new
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
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.topY, z0 + 15, true, true);
		} else {
			// Under a roof the sky view is lit by block light, so light is part of what's saved.
			boolean terrain = (changed[6] & CHANGED_SURFACE) != 0 || this.roofless() && (changed[6] & CHANGED_SURFACE_LIGHT) != 0;
			boolean surface = terrain || (changed[6] & CHANGED_SURFACE_LIGHT) != 0;
			this.invalidate(changed[0], changed[1], changed[1], changed[2], changed[3], changed[4], changed[5], surface, terrain);
		}
		this.playerChunkX = playerChunkX;
		this.playerChunkZ = playerChunkZ;
		if (this.chunks.size() > MAX_CHUNKS && !this.trimStuck) {
			this.trimChunks();
		}
	}

	/** Change flag: blocks the sky view can show changed, so its tiles re-bake and get saved. */
	public static final int CHANGED_SURFACE = 1;
	/** Change flag: only light changed, which the sky view shows only through spawn spots. */
	public static final int CHANGED_SURFACE_LIGHT = 2;

	/** Light from a changed block reaches this far, which matters for cave slices. */
	private static final int LIGHT_REACH = 15;

	/**
	 * Marks tile blocks that can look different after blocks in this box changed. {@code y0} is the
	 * bottom of the box for the sky view and {@code caveY0} for cave slices. {@code terrain} is
	 * false when only light changed: the sky view's pixels stay the same, so nothing to save.
	 */
	private void invalidate(int x0, int y0, int caveY0, int z0, int x1, int y1, int z1, boolean visibleFromSky, boolean terrain) {
		Set<Integer> layers = new HashSet<>();
		for (TileKey key : this.tiles.keySet()) {
			layers.add(key.layer());
		}
		for (TileKey key : this.inFlight) {
			layers.add(key.layer());
		}
		if (this.store != null) {
			layers.add(TileKey.SURFACE);
		}
		long now = System.currentTimeMillis();
		for (int layer : layers) {
			// Under a roof the sky view is lit by block light like a cave, so light reaches it the same way.
			boolean cave = layer != TileKey.SURFACE || this.roofless();
			if (!cave && !visibleFromSky) {
				continue;
			}
			// Under a roof light also falls from above, so shadows reach as far as on the surface.
			boolean shadows = !cave || this.roofless();
			int reachX = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_X : 0);
			int reachZ = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_Z : 0);
			int reachY = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_Y : 0);
			double top = y1 + 2 + (cave ? LIGHT_REACH : 0);
			double bottom = (cave ? caveY0 : y0) - 1 - reachY;
			if (layer != TileKey.SURFACE) {
				top = Math.min(top, layer);
				bottom = Math.max(bottom, layer - TileBaker.CAVE_DEPTH - 1);
				if (top < bottom) {
					continue;
				}
			} else if (cave) {
				top = Math.min(top, this.roof);
			}
			if (this.roofless() && visibleFromSky && terrain && y0 < this.roof) {
				// Opening or filling a pocket under the roof can move where a column's lifted rock
				// ends, far below the edit, and with it the shadows that rock casts. Re-bake down to
				// the floor. Light changes, newly seen blocks and edits above the roof can't.
				bottom = layer != TileKey.SURFACE ? layer - TileBaker.CAVE_DEPTH - 1 : this.minY - 1;
			}
			// The sky view's sky occlusion looks this far round every block, so a change reaches back.
			int around = cave ? LIGHT_REACH : TileBaker.SKY_REACH;
			if (!cave) {
				bottom = Math.min(bottom, (cave ? caveY0 : y0) - 1 - TileBaker.SKY_REACH);
			}
			double u0 = x0 - 1 - around;
			double u1 = x1 + 2 + Math.max(reachX, cave ? 0 : TileBaker.SKY_REACH);
			double v0 = Projection.v(top, z0 - 1 - Math.max(reachZ, cave ? 0 : TileBaker.SKY_REACH));
			double v1 = Projection.v(bottom, z1 + 2 + around);
			this.markDirty(layer, u0, v0, u1, v1, now, terrain);
		}
	}

	private void markDirty(int layer, double u0, double v0, double u1, double v1, long now, boolean terrain) {
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
				if (layer == TileKey.SURFACE && this.store != null && terrain) {
					this.unsaved.put(key, now);
				}
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

	private void trimChunks() {
		int playerChunkX = this.playerChunkX;
		int playerChunkZ = this.playerChunkZ;
		List<ChunkSnapshot> all = new ArrayList<>(this.chunks.values());
		all.sort(Comparator.comparingLong(s -> -(sq(s.chunkX - playerChunkX) + sq(s.chunkZ - playerChunkZ))));
		int excess = all.size() - MAX_CHUNKS * 3 / 4;
		for (int i = 0; i < all.size() && excess > 0; i++) {
			// A chunk under a tile still waiting for its background bake stays until it's saved.
			if (!this.waitingToSave(all.get(i))) {
				this.chunks.remove(all.get(i).key());
				excess--;
			}
		}
		this.trimStuck = excess > 0;
		this.lastTrimMs = System.currentTimeMillis();
	}

	/**
	 * Whether a surface tile this chunk shows in still has to be baked and saved: it changed since
	 * its last bake, or its bake is running and may still read this chunk.
	 */
	private boolean waitingToSave(ChunkSnapshot snapshot) {
		if (this.unsaved.isEmpty() && this.inFlight.isEmpty() || snapshot.maxY == Integer.MIN_VALUE) {
			return false;
		}
		double units = Projection.TILE_UNITS;
		int x0 = snapshot.chunkX << 4;
		int z0 = snapshot.chunkZ << 4;
		int tu0 = (int) Math.floor(x0 / units);
		int tu1 = (int) Math.floor((x0 + 16) / units);
		int tv0 = (int) Math.floor(Projection.v(snapshot.maxY + 1, z0) / units);
		int tv1 = (int) Math.floor(Projection.v(snapshot.floorY, z0 + 16) / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv);
				if (this.unsaved.containsKey(key) || this.inFlight.contains(key)) {
					return true;
				}
			}
		}
		return false;
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

	/** Whether overview tiles exist for zooming far out: only when the map is saved. */
	public boolean hasOverviews() {
		return this.store != null;
	}

	/** Uploads finished bakes and starts new ones. Call regularly on the render thread. */
	public void update() {
		this.frame++;
		TileKey changedOverview;
		while ((changedOverview = this.overviewChanged.poll()) != null) {
			this.emptyOverviews.remove(changedOverview);
			// A load still in flight may have read the old pixels: load it again after.
			if (this.tiles.containsKey(changedOverview) || this.inFlight.contains(changedOverview)) {
				this.dirty.computeIfAbsent(changedOverview, k -> new Dirty(0)).blocks.set(0);
			}
		}
		TileImage image;
		while ((image = this.finished.poll()) != null) {
			this.inFlight.remove(image.key());
			if (SLOT_STATS) {
				this.slotReleased(image.key());
			}
			boolean background = this.backgroundBakes.remove(image.key());
			if (this.closed) {
				continue;
			}
			if (image.generation() != this.generation(image.key())) {
				// Baked under old colours or the other cave mode; bake it again.
				MapTile stale = this.tiles.remove(image.key());
				if (stale != null) {
					stale.close();
					this.coverageVersion++;
				}
				if (background) {
					this.unsaved.put(image.key(), 0L);
				}
				continue;
			}
			MapTile tile = this.tiles.get(image.key());
			if (background && tile == null) {
				// Baked only to be saved; a map that wants it will bake it again.
				if (this.store != null) {
					this.store.baked(image);
				}
				continue;
			}
			if (tile == null) {
				if (image.key().lod() > 0 && !image.isFull()) {
					if (!this.dirty.containsKey(image.key())) {
						this.emptyOverviews.add(image.key());
					}
				}
				if (!image.isFull()) {
					// The tile was evicted while its patch baked.
					continue;
				}
				tile = new MapTile();
				this.tiles.put(image.key(), tile);
			}
			tile.upload(image);
			this.coverageVersion++;
			if (this.store != null && !image.key().isCave() && image.key().lod() == 0) {
				this.store.baked(image);
			}
		}
		if (this.trimStuck && System.currentTimeMillis() - this.lastTrimMs >= TRIM_RETRY_MS) {
			this.trimChunks();
		}

		BlockLooks looks = BlockLooks.get();
		if (looks != null && this.topY != Integer.MIN_VALUE) {
			long now = System.currentTimeMillis();
			int limit = 6;
			List<Map.Entry<TileKey, Double>> order = new ArrayList<>(this.wanted.entrySet());
			order.sort(Map.Entry.comparingByValue());
			for (Map.Entry<TileKey, Double> entry : order) {
				if (this.inFlight.size() >= limit) {
					if (SLOT_STATS) {
						this.slotsFull++;
					}
					break;
				}
				TileKey key = entry.getKey();
				if (this.inFlight.contains(key)) {
					continue;
				}
				boolean missing = !this.tiles.containsKey(key);
				if (key.lod() > 0) {
					// Overview tiles come from disk, shrunk from saved tiles; nothing to bake.
					if ((missing || this.dirty.containsKey(key)) && this.store != null && !this.emptyOverviews.contains(key)) {
						this.dirty.remove(key);
						this.loadOverview(key, this.store);
					}
					continue;
				}
				Dirty d = this.dirty.get(key);
				boolean ready = d != null && (now - d.last > QUIET_MS || now - d.first > MAX_WAIT_MS);
				if (missing || ready) {
					this.dirty.remove(key);
					this.unsaved.remove(key);
					this.bake(key, looks, missing || d.blocks.cardinality() > 160 ? null : d.blocks);
				}
			}
			this.bakeUnsaved(looks, now);
		}
		this.wanted.clear();
		this.evict();
	}

	/**
	 * Experiment only, with {@code -Ddiorama.slotStats=true}: how long tiles hold a bake slot, from
	 * being scheduled to their result reaching {@link #update}, and how often a wanted tile found
	 * every slot taken.
	 */
	private static final boolean SLOT_STATS = Boolean.getBoolean("diorama.slotStats");
	private final Map<TileKey, Long> slotTaken = new HashMap<>();
	private final long[] slotNanos = new long[2];
	private final int[] slotCount = new int[2];
	private int slotsFull;

	private void slotReleased(TileKey key) {
		Long taken = this.slotTaken.remove(key);
		if (taken == null) {
			return;
		}
		int kind = key.isCave() ? 1 : 0;
		this.slotNanos[kind] += System.nanoTime() - taken;
		this.slotCount[kind]++;
		if ((this.slotCount[0] + this.slotCount[1]) % 50 == 0) {
			Diorama.LOGGER.info("SLOTS held ms: surface {} over {}, cave {} over {}; updates with every slot taken: {}",
				this.slotCount[0] == 0 ? 0 : this.slotNanos[0] / this.slotCount[0] / 1_000_000, this.slotCount[0],
				this.slotCount[1] == 0 ? 0 : this.slotNanos[1] / this.slotCount[1] / 1_000_000, this.slotCount[1], this.slotsFull);
		}
	}

	/** Bakes one tile on a baker thread: all of it if {@code blocks} is null, else those blocks. */
	private void bake(TileKey key, BlockLooks looks, @Nullable BitSet blocks) {
		this.inFlight.add(key);
		if (SLOT_STATS) {
			this.slotTaken.put(key, System.nanoTime());
		}
		int generation = this.generation(key);
		boolean fog = this.fogOfWar;
		Overlays overlays = this.overlays;
		TileStore store = key.isCave() ? null : this.store;
		// As TileBaker decides: under a roof (the Nether) every view is lit as if the roof were
		// lifted off, cave slices too; elsewhere a cave slice is lit by block light. Fog of war only
		// changes those two.
		int view = this.roof() != dev.diorama.bake.ChunkSource.NO_ROOF ? dev.diorama.render.gpu.GpuBakeCheck.UNDER_ROOF
			: key.isCave() ? dev.diorama.render.gpu.GpuBakeCheck.CAVE : dev.diorama.render.gpu.GpuBakeCheck.SKY;
		dev.diorama.render.gpu.GpuBakeCheck.Mode gpu = dev.diorama.render.gpu.GpuBakeCheck.mode(view, view != dev.diorama.render.gpu.GpuBakeCheck.SKY && fog, overlays != null);
		if (gpu == dev.diorama.render.gpu.GpuBakeCheck.Mode.GPU) {
			this.bakeOnGpu(key, looks, generation, fog, overlays, store);
			return;
		}
		if (gpu == dev.diorama.render.gpu.GpuBakeCheck.Mode.CHECK) {
			// Traced for the map below, and checked on the side.
			this.checkOnGpu(key, looks, generation, fog, overlays, view);
		}
		BAKERS.execute(() -> {
			try {
				long start = System.nanoTime();
				int[] saved = store == null ? null : store.read(key);
				ChunkSource source = this.sourceFor(store);
				TileImage image = TileBaker.bake(key, source, looks, blocks, fog, saved, overlays);
				long spots = System.nanoTime();
				this.finished.add(this.withSpots(image, source, looks, fog, store).withGeneration(generation));
				logBakeTime(System.nanoTime() - start, System.nanoTime() - spots, blocks == null);
			} catch (Throwable t) {
				Diorama.LOGGER.error("Failed to bake tile {}", key, t);
				this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
			}
		});
	}

	/**
	 * GPU baking: the mesh is built on a baker thread, drawn on the render thread, and the pixels
	 * come back here a frame or two later. Always the whole tile, which costs the GPU little.
	 * Falls back to tracing if the GPU didn't draw it.
	 */
	private void bakeOnGpu(TileKey key, BlockLooks looks, int generation, boolean fog, @Nullable Overlays overlays, @Nullable TileStore store) {
		BAKERS.execute(() -> {
			try {
				int[] saved = store == null ? null : store.read(key);
				ChunkSource source = this.sourceFor(store);
				dev.diorama.bake.GpuMesher.Mesh mesh = dev.diorama.bake.GpuMesher.build(key, source, looks, fog, overlays);
				dev.diorama.render.gpu.GpuTileBaker.submit(mesh, result -> BAKERS.execute(() -> {
					try {
						TileImage image = result.pixels() == null
							? TileBaker.bake(key, source, looks, null, fog, saved, overlays)
							: TileBaker.gpuImage(key, result.pixels(), saved, result.info());
						this.finished.add(this.withSpots(image, source, looks, fog, store).withGeneration(generation));
					} catch (Throwable t) {
						Diorama.LOGGER.error("Failed to finish GPU tile {}", key, t);
						this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
					}
				}));
			} catch (Throwable t) {
				Diorama.LOGGER.error("Failed to mesh tile {}", key, t);
				this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
			}
		});
	}

	/**
	 * A self-check tile: baked on the GPU and traced the same way, from the same chunks, and
	 * compared (see {@link dev.diorama.render.gpu.GpuBakeCheck}). Nothing here reaches the map,
	 * which traces the tile as usual meanwhile.
	 */
	private void checkOnGpu(TileKey key, BlockLooks looks, int generation, boolean fog, @Nullable Overlays overlays, int view) {
		CHECKER.execute(() -> {
			boolean answered = false;
			try {
				// Both bakes read the same chunks, even while more are arriving, so only the GPU
				// can make them differ.
				dev.diorama.bake.ChunkSource source = new dev.diorama.bake.PinnedSource(this);
				// The mesher, the tracer and the GPU's uniforms each read the look, in that order:
				// if the last saw the same as before the first, the two bakes show one look.
				dev.diorama.MapStyle look = dev.diorama.MapStyle.get();
				dev.diorama.bake.GpuMesher.Mesh mesh = dev.diorama.bake.GpuMesher.build(key, source, looks, fog, overlays);
				byte[] info = overlays != null ? new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS] : null;
				int[] traced = TileBaker.reference(key, source, looks, fog, overlays, info);
				dev.diorama.render.gpu.GpuTileBaker.submit(mesh, result -> CHECKER.execute(() -> {
					if (result.pixels() == null) {
						dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, !result.declined());
					} else if (this.generation(key) == generation && look.equals(result.look())) {
						dev.diorama.render.gpu.GpuBakeCheck.compare(view, result.pixels(), traced, result.info(), info);
					} else {
						// The map's look changed while it was out: the two may have baked different looks.
						dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, false);
					}
				}));
				// From here the callback answers the check.
				answered = true;
			} catch (Throwable t) {
				if (!answered) {
					// The check tile never reached the GPU: hand its turn back.
					dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, false);
				}
				Diorama.LOGGER.error("Failed to check tile {} on the GPU", key, t);
			}
		});
	}

	/**
	 * What a bake reads chunks from. A tile to be saved gets its {@link TileSpots} too, in a second
	 * pass, and both passes have to see the same chunks, or a block changed in between would save
	 * pixels showing it with spots that don't.
	 */
	private ChunkSource sourceFor(@Nullable TileStore store) {
		return store == null ? this : new PinnedSource(this);
	}

	/**
	 * A bake of a tile to be saved, with its {@link TileSpots} worked out from the chunks the bake
	 * read, so the right-click menu still works there once they're gone. Baker threads.
	 */
	private TileImage withSpots(TileImage image, ChunkSource source, BlockLooks looks, boolean fog, @Nullable TileStore store) {
		if (store == null) {
			return image;
		}
		return image.withSpots(TileSpots.pick(image, source, looks, fog, store.spots(image.key())));
	}

	/**
	 * Where a player would stand at a map point by the spots saved with its tile, for where the
	 * chunks are gone. Reads the disk off the render thread; null if nothing was saved there.
	 */
	public CompletableFuture<@Nullable BlockPos> savedSpot(int layer, double u, double v) {
		TileStore store = this.store;
		if (store == null || layer != TileKey.SURFACE) {
			return CompletableFuture.completedFuture(null);
		}
		TileKey key = new TileKey(layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		return CompletableFuture.supplyAsync(() -> TileSpots.spot(key, store.spots(key), u, v)).exceptionally(t -> {
			Diorama.LOGGER.warn("Couldn't read the saved spots of tile {}", key, t);
			return null;
		});
	}

	private void loadOverview(TileKey key, TileStore store) {
		this.inFlight.add(key);
		int generation = this.generation(key);
		BAKERS.execute(() -> {
			int[] pixels = null;
			try {
				pixels = store.read(key);
			} catch (Throwable t) {
				Diorama.LOGGER.error("Failed to load overview tile {}", key, t);
			}
			this.finished.add((pixels == null ? TileImage.partial(key, List.of()) : TileBaker.image(key, pixels)).withGeneration(generation));
		});
	}

	/**
	 * Bakes changed surface tiles no map is showing, so they get saved. Leaves one baker free, so
	 * tiles a map is waiting for still come first.
	 */
	private void bakeUnsaved(BlockLooks looks, long now) {
		if (this.unsaved.isEmpty() || this.inFlight.size() >= Math.max(1, BAKER_COUNT - 1)) {
			return;
		}
		Iterator<Map.Entry<TileKey, Long>> it = this.unsaved.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, Long> e = it.next();
			TileKey key = e.getKey();
			if (now - e.getValue() < BACKGROUND_QUIET_MS || this.inFlight.contains(key)) {
				continue;
			}
			it.remove();
			this.dirty.remove(key);
			if (!this.tiles.containsKey(key)) {
				this.backgroundBakes.add(key);
			}
			this.bake(key, looks, null);
			return;
		}
	}

	private static final java.util.concurrent.atomic.AtomicLong BAKE_NANOS = new java.util.concurrent.atomic.AtomicLong();
	private static final AtomicInteger BAKE_COUNT = new AtomicInteger();
	/** The part of {@link #BAKE_NANOS} spent in {@link #withSpots}. */
	private static final java.util.concurrent.atomic.AtomicLong SPOT_NANOS = new java.util.concurrent.atomic.AtomicLong();

	private static final AtomicInteger PARTIAL_COUNT = new AtomicInteger();

	private static void logBakeTime(long nanos, long spotNanos, boolean full) {
		if (!full) {
			if (PARTIAL_COUNT.incrementAndGet() % 50 == 0) {
				Diorama.LOGGER.info("Re-baked {} partial tiles", PARTIAL_COUNT.get());
			}
			return;
		}
		long total = BAKE_NANOS.addAndGet(nanos);
		long spots = SPOT_NANOS.addAndGet(spotNanos);
		int count = BAKE_COUNT.incrementAndGet();
		if (count % 25 == 0) {
			Diorama.LOGGER.info("Baked {} full tiles, {} ms average, {} ms of it finding spots", count, total / count / 1_000_000, spots / count / 1_000_000);
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
			this.coverageVersion++;
			if (this.dirty.remove(e.getKey()) != null && this.store != null && !e.getKey().isCave() && e.getKey().lod() == 0) {
				// Changed since it was last baked: bake it once more in the background so the change is saved.
				this.unsaved.put(e.getKey(), 0L);
			}
		}
	}

	/** Saves fog-of-war progress if it changed. Call now and then; tiles save themselves. */
	public void saveSeen() {
		if (this.folder != null) {
			this.seen.save(this.folder.resolve("seen.bin"));
		}
	}

	public void close() {
		if (this.closed) {
			return;
		}
		if (this.store != null) {
			this.saveLastBakes(this.store);
		}
		this.closed = true;
		if (this.store != null) {
			this.store.close();
		}
		this.saveSeen();
		for (MapTile tile : this.tiles.values()) {
			tile.close();
		}
		this.tiles.clear();
		this.coverageVersion++;
		this.chunks.clear();
	}

	/**
	 * Saves surface bakes that finished since the last update, waiting a moment for the ones still
	 * running: they hold the latest look of their tiles.
	 */
	private void saveLastBakes(TileStore store) {
		long deadline = System.currentTimeMillis() + 3000;
		while (true) {
			// GPU bakes need frames this wait holds up: trace those tiles instead, including ones
			// whose mesh a baker thread finishes while it waits.
			dev.diorama.render.gpu.GpuTileBaker.cancelAll();
			TileImage image;
			while ((image = this.finished.poll()) != null) {
				this.inFlight.remove(image.key());
				if (!image.key().isCave() && image.key().lod() == 0 && image.generation() == this.generation(image.key())) {
					store.baked(image);
				}
			}
			boolean surfaceBaking = this.inFlight.stream().anyMatch(key -> !key.isCave() && key.lod() == 0);
			if (!surfaceBaking || System.currentTimeMillis() > deadline) {
				return;
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	public int bakingCount() {
		return this.inFlight.size();
	}
}
