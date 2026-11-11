package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLight;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileChunks;
import dev.diorama.bake.TileSpots;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Baked surface tiles saved on disk, one file per tile, so the map remembers places after their
 * chunks unload or the game restarts.
 *
 * <p>Saved tiles are merged in when baking: wherever a fresh bake has no data for a pixel, the
 * saved pixel is used. Opening a part of the map whose chunks aren't loaded is then just a bake
 * where everything is unknown, and re-baking near the edge of loaded chunks never erases what was
 * saved. Writes happen a few seconds after a tile first changes, on one background thread.
 * Closing waits until everything is on disk, like Minecraft saving the world when you leave, so a
 * store reopened on the same folder always reads what the last one wrote.
 *
 * <p>Overview tiles for zooming far out ({@link TileKey#lod()} above 0) live in {@code lod<n>}
 * folders. Each is its four children shrunk to a quarter. A couple of seconds after tiles are
 * written, the writer rebuilds the overview tiles above them, each once however many of its
 * children changed, so zooming out shows everything explored without baking anything. Only the
 * writer builds them: a missing overview tile means nothing under it was ever mapped.
 *
 * <p>A level-0 tile also keeps its {@link TileSpots}, so the right-click menu still works there
 * once the chunks are gone. An overview tile keeps its {@link TileChunks}, so land claims can be
 * drawn on it; a level-0 tile's come from its spots.
 *
 * <p>A sky view tile also keeps its {@link BlockLight}, which lights the map at night. Tiles saved
 * before it was kept have none until they're baked again.
 *
 * <p>File format: {@code OMT1}, the edge length, then deflated: the ABGR pixels, then on level-0
 * tiles {@code SPT1} and the spots, and on overview tiles {@code CHK1} and the chunks, then
 * {@code LIT1} and the block light, {@link BlockLight#EDGE} a side, a byte each. Versions from
 * before spots read the pixels and stop there; versions from before block light stop before it.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int SPOTS_MAGIC = 0x53505431;
	private static final int CHUNKS_MAGIC = 0x43484B31;
	private static final int LIGHT_MAGIC = 0x4C495431;
	private static final int CACHED_TILES = 24;
	private static final long WRITE_DELAY_SECONDS = 3;
	/** After a failed write (a full disk, say), try again this much later. */
	private static final long RETRY_DELAY_SECONDS = 30;
	/** Overview tiles are rebuilt this long after the first tile under them is written. */
	private static final long OVERVIEW_DELAY_SECONDS = 2;

	/** The only thread that writes tiles. */
	private static final ScheduledThreadPoolExecutor WRITER = new ScheduledThreadPoolExecutor(1, task -> {
		Thread thread = new Thread(task, "diorama-tile-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private final Path folder;
	/** Set by close(), whose flush writes everything; writes still scheduled then do nothing. */
	private volatile boolean closed;
	/**
	 * Held while reading a tile from disk or merging bakes into it, so those happen one at a time
	 * and in order. The render thread never takes it: it only hands bakes over. Take before
	 * {@code this}, never the other way round.
	 */
	private final Object io = new Object();
	/**
	 * Bakes handed over by {@link #baked} and not yet merged into their tile, oldest first. Guarded
	 * by {@code this}.
	 */
	private final Map<TileKey, ArrayDeque<TileImage>> handedOver = new HashMap<>();
	/** Tiles with a merge waiting on the writer thread, so a run of bakes queues one. Guarded by {@code this}. */
	private final Set<TileKey> mergeQueued = new HashSet<>();
	/**
	 * A saved tile: its pixels, its spots (level 0) or chunks (overviews) where it has any, and its
	 * block light where it was baked with it. Never modified.
	 */
	private record Tile(int[] pixels, int @Nullable [] spots, byte @Nullable [] chunks, byte @Nullable [] blockLight) {
		boolean same(Tile other) {
			return java.util.Arrays.equals(this.pixels, other.pixels) && java.util.Arrays.equals(this.spots, other.spots)
				&& java.util.Arrays.equals(this.chunks, other.chunks) && java.util.Arrays.equals(this.blockLight, other.blockLight);
		}
	}

	/** A saved tile's pixels, its {@link BlockLight} and its {@link dev.diorama.bake.TileSpots}, either null where it was saved without. */
	public record Saved(int[] pixels, byte @Nullable [] blockLight, int @Nullable [] spots) {
	}

	/** Recently used tiles, decompressed. Guarded by {@code this}. */
	private final LinkedHashMap<TileKey, Tile> cache = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<TileKey, Tile> eldest) {
			return this.size() > CACHED_TILES && !TileStore.this.unwritten.containsKey(eldest.getKey());
		}
	};
	/**
	 * Tiles changed since they were last written, kept until a write of exactly these pixels
	 * succeeds. They're never evicted from the cache meanwhile. Guarded by {@code this}.
	 */
	private final Map<TileKey, Tile> unwritten = new HashMap<>();

	/**
	 * Overview tiles to rebuild: level 1 above tiles written since the last rebuild, and the level-1
	 * tiles under an overview saved before chunks were kept. Guarded by {@code this}.
	 */
	private final Set<TileKey> staleOverviews = new HashSet<>();
	/** Overview tiles saved without chunks whose rebuild has been asked for. Guarded by {@code this}. */
	private final Set<TileKey> chunksAsked = new HashSet<>();
	private boolean rebuildScheduled;
	/** Told about overview tiles the writer rebuilt. Called on the writer thread. */
	private volatile Consumer<TileKey> overviewChanged = key -> {
	};

	public TileStore(Path folder) {
		this.folder = folder;
	}

	/** Where the tiles are saved: one folder for each map of each dimension of each world. */
	public Path folder() {
		return this.folder;
	}

	public void onOverviewChanged(Consumer<TileKey> listener) {
		this.overviewChanged = listener;
	}

	/** Tiles held in memory until they reach the disk, for a long fill to keep behind the writer. */
	public synchronized int unwrittenTiles() {
		return this.unwritten.size() + this.mergeQueued.size();
	}

	/**
	 * One level up from four children, each shrunk to a quarter; null if none exist. Its block
	 * light is theirs shrunk the same way, null if none of them has any; a child saved without
	 * gives its quarter none.
	 */
	private @Nullable Saved shrinkChildren(TileKey key) {
		int size = Projection.TILE_PIXELS;
		int half = size / 2;
		int[] out = null;
		byte[][] childLight = new byte[4][];
		int[][] childPixels = new int[4][];
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + dx, key.tileV() * 2 + dy, key.lod() - 1);
				Saved saved = this.readSaved(child);
				if (saved == null) {
					continue;
				}
				int[] c = saved.pixels();
				childPixels[dy * 2 + dx] = c;
				childLight[dy * 2 + dx] = saved.blockLight();
				if (out == null) {
					out = new int[size * size];
				}
				for (int y = 0; y < half; y++) {
					int row = (2 * y) * size;
					for (int x = 0; x < half; x++) {
						int i = row + 2 * x;
						out[(dy * half + y) * size + dx * half + x] = Mipmaps.average(c[i], c[i + 1], c[i + size], c[i + size + 1]);
					}
				}
			}
		}
		return out == null ? null : new Saved(out, BlockLight.overview(childLight, childPixels), null);
	}

	/** A tile was written: the overview tiles above it are now out of date. */
	private synchronized void overviewsStale(TileKey key) {
		if (key.lod() >= TileKey.MAX_LOD) {
			return;
		}
		this.staleOverviews.add(key.parent());
		if (!this.rebuildScheduled && !this.closed) {
			this.rebuildScheduled = true;
			WRITER.schedule(this::rebuildOverviews, OVERVIEW_DELAY_SECONDS, TimeUnit.SECONDS);
		}
	}

	/**
	 * Rebuilds and writes every out-of-date overview tile, one level at a time from the most
	 * detailed up, each once, so a tile's children are rebuilt before it. Writer thread.
	 */
	private void rebuildOverviews() {
		java.util.TreeMap<Integer, Set<TileKey>> levels = new java.util.TreeMap<>();
		synchronized (this) {
			for (TileKey key : this.staleOverviews) {
				levels.computeIfAbsent(key.lod(), lod -> new HashSet<>()).add(key);
			}
			this.staleOverviews.clear();
			this.rebuildScheduled = false;
		}
		while (!levels.isEmpty()) {
			Set<TileKey> level = levels.pollFirstEntry().getValue();
			Set<TileKey> above = new HashSet<>();
			for (TileKey key : level) {
				Saved shrunk = this.shrinkChildren(key);
				if (shrunk == null) {
					continue;
				}
				Tile tile = new Tile(shrunk.pixels(), null, this.shrinkChunks(key), shrunk.blockLight());
				// Under io too, so a read loading this tile from disk can't cache the old pixels over these.
				synchronized (this.io) {
					synchronized (this) {
						this.cache.put(key, tile);
						// Pending until written, like a bake.
						this.unwritten.put(key, tile);
					}
				}
				boolean saved = this.store(key, tile);
				synchronized (this) {
					if (this.unwritten.get(key) == tile) {
						if (saved) {
							this.unwritten.remove(key);
						} else if (!this.closed) {
							WRITER.schedule(() -> this.write(key), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
						}
					}
				}
				this.overviewChanged.accept(key);
				if (key.lod() < TileKey.MAX_LOD) {
					above.add(key.parent());
				}
				synchronized (this) {
					this.trimCache();
				}
			}
			if (!above.isEmpty()) {
				levels.computeIfAbsent(above.iterator().next().lod(), lod -> new HashSet<>()).addAll(above);
			}
		}
	}

	/**
	 * The saved pixels of a tile, or null if there are none, including every bake handed over so
	 * far. Not on the render thread: it can wait for the disk. Don't modify the array.
	 */
	public int @Nullable [] read(TileKey key) {
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			return tile == null ? null : tile.pixels;
		}
	}

	/** The same with the tile's block light, read together so the two always match. Don't modify the arrays. */
	public @Nullable Saved readSaved(TileKey key) {
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			return tile == null ? null : new Saved(tile.pixels, tile.blockLight, tile.spots);
		}
	}

	/**
	 * An overview tile's chunks, from its children's as saved; null if none of them has any, or if
	 * a child overview was saved before chunks were kept. That child is rebuilt first, and this
	 * tile again after it, so a tile never keeps chunks with holes where its children had none.
	 */
	private byte @Nullable [] shrinkChunks(TileKey key) {
		byte[][] children = new byte[4][];
		boolean complete = true;
		boolean anyTile = false;
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + dx, key.tileV() * 2 + dy, key.lod() - 1);
				Tile tile;
				synchronized (this.io) {
					this.merge(child);
					tile = this.saved(child);
				}
				if (tile == null) {
					continue;
				}
				anyTile = true;
				if (child.lod() == 0) {
					children[dy * 2 + dx] = TileChunks.fromSpots(child, tile.spots);
				} else if (tile.chunks == null) {
					complete = false;
					this.askChunks(child);
				} else {
					children[dy * 2 + dx] = tile.chunks;
				}
			}
		}
		if (!complete) {
			return null;
		}
		byte[] chunks = TileChunks.shrink(key, children);
		if (chunks == null && anyTile) {
			// Children saved before spots were kept say nothing about chunks: all unknown, but
			// done, so the tiles above don't wait on this one.
			chunks = new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
			java.util.Arrays.fill(chunks, TileChunks.NONE);
		}
		return chunks;
	}

	/**
	 * The {@link TileChunks} of a saved tile, or null if there are none. Like {@link #read}. An
	 * overview tile saved before they were kept has none yet: this asks for it to be rebuilt, once,
	 * from the level-1 tiles under it up.
	 */
	public byte @Nullable [] chunks(TileKey key) {
		Tile tile;
		synchronized (this.io) {
			this.merge(key);
			tile = this.saved(key);
		}
		if (tile == null) {
			return null;
		}
		if (key.lod() == 0) {
			return TileChunks.fromSpots(key, tile.spots);
		}
		if (tile.chunks == null) {
			this.askChunks(key);
		}
		return tile.chunks;
	}

	/** Rebuilds an overview tile saved without chunks, and every level-1 tile under it, so each gets them. */
	private synchronized void askChunks(TileKey key) {
		if (this.closed || !this.chunksAsked.add(key)) {
			return;
		}
		int span = 1 << (key.lod() - 1);
		for (int du = 0; du < span; du++) {
			for (int dv = 0; dv < span; dv++) {
				this.staleOverviews.add(new TileKey(key.layer(), key.tileU() * span + du, key.tileV() * span + dv, 1));
			}
		}
		if (!this.rebuildScheduled) {
			this.rebuildScheduled = true;
			WRITER.schedule(this::rebuildOverviews, OVERVIEW_DELAY_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** The saved {@link TileSpots} of a level-0 tile, or null if there are none. Like {@link #read}. */
	public int @Nullable [] spots(TileKey key) {
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			return tile == null ? null : tile.spots;
		}
	}

	/** The tile in memory, else on disk, leaving out bakes not merged yet. Hold {@link #io}. */
	private @Nullable Tile saved(TileKey key) {
		synchronized (this) {
			Tile pending = this.unwritten.get(key);
			if (pending != null) {
				return pending;
			}
			if (this.cache.containsKey(key)) {
				return this.cache.get(key);
			}
		}
		Tile tile = this.load(key);
		synchronized (this) {
			this.cache.put(key, tile);
		}
		return tile;
	}

	/**
	 * Records a fresh bake, full or partial. The writer thread merges it into the saved pixels and
	 * schedules the write. Returns without waiting for the disk or for another thread's read, so
	 * the render thread can call it.
	 */
	public void baked(TileImage image) {
		TileKey key = image.key();
		synchronized (this) {
			this.handedOver.computeIfAbsent(key, k -> new ArrayDeque<>()).add(image);
			if (!this.mergeQueued.add(key)) {
				return;
			}
		}
		WRITER.execute(() -> {
			// Cleared first, so a bake handed over during this merge queues another.
			synchronized (this) {
				this.mergeQueued.remove(key);
			}
			this.merge(key);
		});
	}

	/**
	 * Merges the bakes handed over for a tile into its pixels, oldest first, and schedules the
	 * write, unless the pixels come out the same as what's saved already: most re-bakes after a
	 * light change or a block that looks the same change nothing.
	 */
	private void merge(TileKey key) {
		synchronized (this.io) {
			List<TileImage> images;
			synchronized (this) {
				ArrayDeque<TileImage> queue = this.handedOver.get(key);
				if (queue == null) {
					return;
				}
				images = List.copyOf(queue);
			}
			int size = Projection.TILE_PIXELS;
			Tile saved = this.saved(key);
			boolean changed = false;
			for (TileImage image : images) {
				int[] pixels;
				byte[] light;
				if (image.isFull()) {
					// A bake's pixels and light never change once made, so the saved tile can be them: a
					// copy was another tile-sized array for every bake, kept for seconds.
					pixels = image.mips()[0];
					light = image.blockLight();
				} else {
					pixels = saved == null ? new int[size * size] : saved.pixels.clone();
					light = saved == null || saved.blockLight == null ? null : saved.blockLight.clone();
					for (TileImage.Patch patch : image.patches()) {
						int[] block = patch.mips()[0];
						int edge = TileImage.BLOCK;
						for (int y = 0; y < edge; y++) {
							System.arraycopy(block, y * edge, pixels, (patch.blockY() * edge + y) * size + patch.blockX() * edge, edge);
						}
						byte[] patchLight = patch.blockLight();
						if (patchLight != null) {
							if (light == null) {
								// The rest of a tile saved without light gets none.
								light = new byte[BlockLight.EDGE * BlockLight.EDGE];
							}
							int lightEdge = edge / BlockLight.SCALE;
							for (int y = 0; y < lightEdge; y++) {
								System.arraycopy(patchLight, y * lightEdge, light, (patch.blockY() * lightEdge + y) * BlockLight.EDGE + patch.blockX() * lightEdge, lightEdge);
							}
						}
					}
				}
				Tile merged = new Tile(pixels, mergeSpots(saved == null ? null : saved.spots, image), null, light);
				if (saved != null ? saved.same(merged) : nothingKnown(pixels)) {
					count(true);
					continue;
				}
				count(false);
				saved = merged;
				changed = true;
			}
			synchronized (this) {
				// Bakes handed over meanwhile stay queued; their own merge comes next.
				ArrayDeque<TileImage> queue = this.handedOver.get(key);
				for (int i = 0; i < images.size(); i++) {
					queue.poll();
				}
				if (queue.isEmpty()) {
					this.handedOver.remove(key);
				}
				if (changed) {
					this.cache.put(key, saved);
					boolean scheduled = this.unwritten.containsKey(key);
					this.unwritten.put(key, saved);
					if (!scheduled) {
						WRITER.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
					}
				}
			}
		}
	}

	/** The spots after a bake: its own where it has them, the saved ones elsewhere. */
	private static int @Nullable [] mergeSpots(int @Nullable [] saved, TileImage image) {
		int[] fresh = image.spots();
		if (fresh == null) {
			return saved;
		}
		if (image.isFull()) {
			// Like the pixels, a bake's spots are made for it and never changed.
			return fresh;
		}
		int[] spots = saved == null ? new int[fresh.length] : saved.clone();
		for (TileImage.Patch patch : image.patches()) {
			for (int row = patch.blockY() * TileImage.BLOCK; row < (patch.blockY() + 1) * TileImage.BLOCK; row++) {
				int i = row * TileSpots.COLUMNS + TileSpots.patchColumn(patch);
				System.arraycopy(fresh, i, spots, i, TileSpots.PATCH_COLUMNS);
			}
		}
		return spots;
	}

	/** For tests: makes an overview tile look like one saved before chunks were kept, on disk too. */
	public void forgetChunks(TileKey key) {
		Tile old;
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			if (tile == null || key.lod() == 0) {
				return;
			}
			old = new Tile(tile.pixels, null, null, tile.blockLight);
			synchronized (this) {
				this.cache.put(key, old);
				this.chunksAsked.remove(key);
			}
		}
		this.store(key, old);
	}

	/** For tests: makes a level-0 tile look like one saved before block light was kept, on disk too. */
	public void forgetBlockLight(TileKey key) {
		Tile old;
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			if (tile == null || key.lod() != 0) {
				return;
			}
			old = new Tile(tile.pixels, tile.spots, null, null);
			synchronized (this) {
				this.cache.put(key, old);
				// A write still waiting would put the light back.
				this.unwritten.replace(key, old);
			}
		}
		this.store(key, old);
	}

	/** For tests: makes a level-0 tile look like one saved before spots were kept, on disk too. */
	public void forgetSpots(TileKey key) {
		Tile old;
		synchronized (this.io) {
			this.merge(key);
			Tile tile = this.saved(key);
			if (tile == null || key.lod() != 0) {
				return;
			}
			old = new Tile(tile.pixels, null, null, tile.blockLight);
			synchronized (this) {
				this.cache.put(key, old);
				// A write still waiting would put the spots back.
				this.unwritten.replace(key, old);
			}
		}
		this.store(key, old);
	}

	/**
	 * Merges every bake handed over so far and rebuilds the overview tiles above them, and any
	 * left out of date when the game last quit, so reads at every level show everything mapped up
	 * to now. Waits for the writer; not on the render thread.
	 */
	public void catchUp() throws InterruptedException {
		Future<?> done = WRITER.submit(() -> {
			Set<TileKey> handed;
			synchronized (this) {
				handed = new HashSet<>(this.handedOver.keySet());
			}
			for (TileKey key : handed) {
				this.merge(key);
			}
			synchronized (this) {
				for (TileKey key : this.unwritten.keySet()) {
					if (key.lod() < TileKey.MAX_LOD) {
						this.staleOverviews.add(key.parent());
					}
				}
			}
			this.findStaleOverviews();
			this.rebuildOverviews();
		});
		try {
			done.get();
		} catch (ExecutionException e) {
			Diorama.LOGGER.error("Couldn't bring the saved map up to date", e);
		}
	}

	/**
	 * Marks overview tiles missing or older on disk than a tile under them. A game that quit
	 * between saving a tile and rebuilding the overviews above it, a couple of seconds later, leaves
	 * them so, and nothing else would rebuild them. Writer thread.
	 */
	private void findStaleOverviews() {
		for (int lod = 0; lod < TileKey.MAX_LOD; lod++) {
			for (TileKey key : this.saved(lod)) {
				Path child = this.file(key);
				Path parent = this.file(key.parent());
				try {
					if (Files.isRegularFile(child) && (!Files.isRegularFile(parent)
						|| Files.getLastModifiedTime(parent).compareTo(Files.getLastModifiedTime(child)) < 0)) {
						synchronized (this) {
							this.staleOverviews.add(key.parent());
						}
					}
				} catch (IOException e) {
					// Can't tell: leave it as it is.
				}
			}
		}
	}

	/** Every saved tile at an overview level, 0 for the detailed tiles, on disk or about to be. */
	public Set<TileKey> saved(int lod) {
		Set<TileKey> keys = new HashSet<>();
		Path dir = lod == 0 ? this.folder : this.folder.resolve("lod" + lod);
		if (Files.isDirectory(dir)) {
			try (java.util.stream.Stream<Path> files = Files.list(dir)) {
				files.forEach(file -> {
					String name = file.getFileName().toString();
					int split = name.indexOf('_');
					if (!name.endsWith(".omt") || split < 0) {
						return;
					}
					try {
						int u = Integer.parseInt(name.substring(0, split));
						int v = Integer.parseInt(name.substring(split + 1, name.length() - 4));
						keys.add(new TileKey(TileKey.SURFACE, u, v, lod));
					} catch (NumberFormatException e) {
						// Not a tile.
					}
				});
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't list saved tiles in {}", dir, e);
			}
		}
		synchronized (this) {
			for (TileKey key : this.unwritten.keySet()) {
				if (key.lod() == lod) {
					keys.add(key);
				}
			}
		}
		return keys;
	}

	/**
	 * Every saved tile at an overview level with when its file was last written, in milliseconds;
	 * one not on disk yet has the time now. For a viewer that fetches a tile again when this changes.
	 */
	public Map<TileKey, Long> savedTimes(int lod) {
		Map<TileKey, Long> times = new HashMap<>();
		Path dir = lod == 0 ? this.folder : this.folder.resolve("lod" + lod);
		if (Files.isDirectory(dir)) {
			// The times come with the listing, without opening each file.
			try (java.util.stream.Stream<Path> files = Files.find(dir, 1, (file, attributes) -> {
				String name = file.getFileName().toString();
				int split = name.indexOf('_');
				if (!attributes.isRegularFile() || !name.endsWith(".omt") || split < 0) {
					return false;
				}
				try {
					int u = Integer.parseInt(name.substring(0, split));
					int v = Integer.parseInt(name.substring(split + 1, name.length() - 4));
					times.put(new TileKey(TileKey.SURFACE, u, v, lod), attributes.lastModifiedTime().toMillis());
				} catch (NumberFormatException e) {
					// Not a tile.
				}
				return false;
			})) {
				files.forEach(file -> {
				});
			} catch (IOException | java.io.UncheckedIOException e) {
				Diorama.LOGGER.warn("Couldn't list saved tiles in {}", dir, e);
			}
		}
		long now = System.currentTimeMillis();
		synchronized (this) {
			for (TileKey key : this.unwritten.keySet()) {
				if (key.lod() == lod) {
					times.put(key, now);
				}
			}
		}
		return times;
	}

	/** Writes everything pending and waits until it's on disk. Call when leaving the world. */
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		Future<?> flush = WRITER.submit(() -> {
			Set<TileKey> handed;
			synchronized (this) {
				handed = new HashSet<>(this.handedOver.keySet());
			}
			for (TileKey key : handed) {
				this.merge(key);
			}
			Map<TileKey, Tile> pending;
			synchronized (this) {
				pending = new HashMap<>(this.unwritten);
			}
			for (Map.Entry<TileKey, Tile> entry : pending.entrySet()) {
				// Nothing to retry with once the world is closed; store() has logged the failure.
				if (this.store(entry.getKey(), entry.getValue()) && entry.getKey().lod() == 0) {
					synchronized (this) {
						this.staleOverviews.add(entry.getKey().parent());
					}
				}
			}
			this.rebuildOverviews();
			synchronized (this) {
				this.unwritten.clear();
				this.cache.clear();
			}
		});
		try {
			flush.get();
		} catch (ExecutionException e) {
			Diorama.LOGGER.error("Couldn't save map tiles", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Writes the newest pixels of a tile. Writer thread. */
	private void write(TileKey key) {
		if (this.closed) {
			return;
		}
		Tile tile;
		synchronized (this) {
			tile = this.unwritten.get(key);
		}
		if (tile == null) {
			return;
		}
		boolean saved = this.store(key, tile);
		if (saved && key.lod() == 0) {
			this.overviewsStale(key);
		}
		synchronized (this) {
			if (this.unwritten.get(key) != tile) {
				// A newer bake arrived while this one was being written: write that too.
				WRITER.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
			} else if (saved) {
				this.unwritten.remove(key);
			} else {
				// A full disk, say: keep it pending and try again.
				WRITER.schedule(() -> this.write(key), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			this.trimCache();
		}
	}

	private static final java.util.concurrent.atomic.AtomicInteger CHANGED = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger UNCHANGED = new java.util.concurrent.atomic.AtomicInteger();

	private static void count(boolean unchanged) {
		if (!Diorama.DEBUG) {
			return;
		}
		int changed = unchanged ? CHANGED.get() : CHANGED.incrementAndGet();
		int same = unchanged ? UNCHANGED.incrementAndGet() : UNCHANGED.get();
		if ((changed + same) % 100 == 0) {
			Diorama.LOGGER.info("Bakes saved: {} changed, {} unchanged and skipped", changed, same);
		}
	}

	private static boolean nothingKnown(int[] pixels) {
		for (int pixel : pixels) {
			if ((pixel >>> 24) != 0) {
				return false;
			}
		}
		return true;
	}

	/** Drops the least recently used written tiles until the cache is back under its limit. */
	private void trimCache() {
		Iterator<TileKey> it = this.cache.keySet().iterator();
		while (this.cache.size() > CACHED_TILES && it.hasNext()) {
			if (!this.unwritten.containsKey(it.next())) {
				it.remove();
			}
		}
	}

	private Path file(TileKey key) {
		Path dir = key.lod() == 0 ? this.folder : this.folder.resolve("lod" + key.lod());
		return dir.resolve(key.tileU() + "_" + key.tileV() + ".omt");
	}

	private @Nullable Tile load(TileKey key) {
		Path file = this.file(key);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
			if (data.readInt() != MAGIC || data.readInt() != Projection.TILE_PIXELS) {
				return null;
			}
			int size = Projection.TILE_PIXELS;
			InflaterInputStream inflated = new InflaterInputStream(in);
			// Read through a small buffer, not a byte copy of the whole tile.
			ByteBuffer buffer = ByteBuffer.allocate(IO_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
			int[] pixels = new int[size * size];
			if (!readInts(inflated, buffer, pixels)) {
				return null;
			}
			// Tiles saved before spots, and overview tiles saved before chunks, end here. Each block
			// after the pixels starts with its name, so one left out is just missing; a block is read
			// in full before its name counts.
			int name = readInt(inflated);
			byte[] chunks = null;
			int[] spots = null;
			if (key.lod() > 0 && name == CHUNKS_MAGIC) {
				chunks = new byte[size * size];
				if (inflated.readNBytes(chunks, 0, chunks.length) != chunks.length) {
					chunks = null;
				}
				name = chunks == null ? 0 : readInt(inflated);
			} else if (key.lod() == 0 && name == SPOTS_MAGIC) {
				spots = new int[TileSpots.COLUMNS * TileSpots.ROWS];
				if (!readInts(inflated, buffer, spots)) {
					spots = null;
				}
				name = spots == null ? 0 : readInt(inflated);
			}
			byte[] light = null;
			if (name == LIGHT_MAGIC) {
				light = new byte[BlockLight.EDGE * BlockLight.EDGE];
				if (inflated.readNBytes(light, 0, light.length) != light.length) {
					light = null;
				}
			}
			// The stream's checksum is only checked at its end: reading on to it makes damage anywhere
			// above, a block's name included, throw instead of reading as a block left out.
			inflated.transferTo(OutputStream.nullOutputStream());
			return new Tile(pixels, spots, chunks, light);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read saved tile {}", file, e);
			return null;
		}
	}

	/** Bytes read or written at a time: a tile file goes through this, not a copy of itself. */
	private static final int IO_BUFFER = 1 << 16;

	/** Fills {@code out} with little-endian ints from {@code in}, through {@code buffer}; false if the stream ends first. */
	private static boolean readInts(InputStream in, ByteBuffer buffer, int[] out) throws IOException {
		int perRead = buffer.capacity() / 4;
		for (int at = 0; at < out.length; at += perRead) {
			int n = Math.min(perRead, out.length - at);
			if (in.readNBytes(buffer.array(), 0, n * 4) != n * 4) {
				return false;
			}
			buffer.asIntBuffer().get(out, at, n);
		}
		return true;
	}

	/** A little-endian int from {@code in}, or 0 if the stream ends first; no magic number is 0. */
	private static int readInt(InputStream in) throws IOException {
		byte[] bytes = in.readNBytes(4);
		return bytes.length == 4 ? ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
	}

	/** Writes {@code ints} little-endian to {@code out}, through {@code buffer}. */
	private static void writeInts(OutputStream out, ByteBuffer buffer, int[] ints) throws IOException {
		int perWrite = buffer.capacity() / 4;
		for (int at = 0; at < ints.length; at += perWrite) {
			int n = Math.min(perWrite, ints.length - at);
			buffer.clear();
			buffer.asIntBuffer().put(ints, at, n);
			out.write(buffer.array(), 0, n * 4);
		}
	}

	/**
	 * Writes to a temporary file and moves it over the old one, so a crash can't leave half a tile.
	 * Returns whether it worked.
	 */
	private boolean store(TileKey key, Tile tile) {
		Path file = this.file(key);
		int[] pixels = tile.pixels;
		int[] spots = tile.spots;
		byte[] chunks = tile.chunks;
		byte[] light = tile.blockLight;
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			// Straight into the file through a small buffer: building the whole file in memory
			// first took several tile-sized arrays a write.
			ByteBuffer buffer = ByteBuffer.allocate(IO_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
			try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), IO_BUFFER))) {
				data.writeInt(MAGIC);
				data.writeInt(Projection.TILE_PIXELS);
				try (OutputStream deflate = new DeflaterOutputStream(data)) {
					writeInts(deflate, buffer, pixels);
					if (spots != null) {
						writeInts(deflate, buffer, new int[] {SPOTS_MAGIC});
						writeInts(deflate, buffer, spots);
					} else if (chunks != null) {
						writeInts(deflate, buffer, new int[] {CHUNKS_MAGIC});
						deflate.write(chunks);
					}
					if (light != null) {
						writeInts(deflate, buffer, new int[] {LIGHT_MAGIC});
						deflate.write(light);
					}
				}
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't save tile {}", file, e);
			return false;
		}
	}
}
