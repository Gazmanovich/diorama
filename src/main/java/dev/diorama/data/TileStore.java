package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileSpots;
import java.io.ByteArrayOutputStream;
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
 * once the chunks are gone.
 *
 * <p>File format: {@code OMT1}, the edge length, then deflated: the ABGR pixels, and on level-0
 * tiles {@code SPT1} and the spots. Versions from before spots read the pixels and stop there.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int SPOTS_MAGIC = 0x53505431;
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
	/** A saved tile: its pixels, and its spots where it has any. Never modified. */
	private record Tile(int[] pixels, int @Nullable [] spots) {
		boolean same(Tile other) {
			return java.util.Arrays.equals(this.pixels, other.pixels) && java.util.Arrays.equals(this.spots, other.spots);
		}
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

	/** Level-1 overview tiles above tiles written since the last rebuild. Guarded by {@code this}. */
	private final Set<TileKey> staleOverviews = new HashSet<>();
	private boolean rebuildScheduled;
	/** Told about overview tiles the writer rebuilt. Called on the writer thread. */
	private volatile Consumer<TileKey> overviewChanged = key -> {
	};

	public TileStore(Path folder) {
		this.folder = folder;
	}

	public void onOverviewChanged(Consumer<TileKey> listener) {
		this.overviewChanged = listener;
	}

	/** One level up from four children, each shrunk to a quarter; null if none exist. */
	private int @Nullable [] shrinkChildren(TileKey key) {
		int size = Projection.TILE_PIXELS;
		int half = size / 2;
		int[] out = null;
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + dx, key.tileV() * 2 + dy, key.lod() - 1);
				int[] c = this.read(child);
				if (c == null) {
					continue;
				}
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
		return out;
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

	/** Rebuilds and writes every out-of-date overview tile, one level at a time. Writer thread. */
	private void rebuildOverviews() {
		Set<TileKey> level;
		synchronized (this) {
			level = new HashSet<>(this.staleOverviews);
			this.staleOverviews.clear();
			this.rebuildScheduled = false;
		}
		while (!level.isEmpty()) {
			Set<TileKey> above = new HashSet<>();
			for (TileKey key : level) {
				int[] pixels = this.shrinkChildren(key);
				if (pixels == null) {
					continue;
				}
				Tile tile = new Tile(pixels, null);
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
			level = above;
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
				if (image.isFull()) {
					pixels = image.mips()[0].clone();
				} else {
					pixels = saved == null ? new int[size * size] : saved.pixels.clone();
					for (TileImage.Patch patch : image.patches()) {
						int[] block = patch.mips()[0];
						int edge = TileImage.BLOCK;
						for (int y = 0; y < edge; y++) {
							System.arraycopy(block, y * edge, pixels, (patch.blockY() * edge + y) * size + patch.blockX() * edge, edge);
						}
					}
				}
				Tile merged = new Tile(pixels, mergeSpots(saved == null ? null : saved.spots, image));
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
			return fresh.clone();
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
			byte[] bytes = inflated.readNBytes(size * size * 4);
			if (bytes.length != size * size * 4) {
				return null;
			}
			int[] pixels = new int[size * size];
			ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
			// Tiles saved before spots, and overview tiles, end here.
			int[] spots = null;
			byte[] more = inflated.readNBytes(4 + TileSpots.COLUMNS * TileSpots.ROWS * 4);
			ByteBuffer rest = ByteBuffer.wrap(more).order(ByteOrder.LITTLE_ENDIAN);
			if (more.length == 4 + TileSpots.COLUMNS * TileSpots.ROWS * 4 && rest.getInt() == SPOTS_MAGIC) {
				spots = new int[TileSpots.COLUMNS * TileSpots.ROWS];
				rest.asIntBuffer().get(spots);
			}
			return new Tile(pixels, spots);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read saved tile {}", file, e);
			return null;
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
		try {
			Files.createDirectories(file.getParent());
			ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 4 + (spots == null ? 0 : 4 + spots.length * 4)).order(ByteOrder.LITTLE_ENDIAN);
			buffer.asIntBuffer().put(pixels);
			if (spots != null) {
				buffer.position(pixels.length * 4);
				buffer.putInt(SPOTS_MAGIC);
				buffer.asIntBuffer().put(spots);
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(pixels.length);
			try (DataOutputStream data = new DataOutputStream(bytes)) {
				data.writeInt(MAGIC);
				data.writeInt(Projection.TILE_PIXELS);
				try (OutputStream deflate = new DeflaterOutputStream(data)) {
					deflate.write(buffer.array());
				}
			}
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(temp, bytes.toByteArray());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.error("Couldn't save tile {}", file, e);
			return false;
		}
	}
}
