package dev.orthomap.data;

import dev.orthomap.Orthomap;
import dev.orthomap.Projection;
import dev.orthomap.bake.Mipmaps;
import dev.orthomap.bake.TileImage;
import dev.orthomap.bake.TileKey;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>File format: {@code OMT1}, the edge length, then the ABGR pixels deflated.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int CACHED_TILES = 24;
	private static final long WRITE_DELAY_SECONDS = 3;
	/** After a failed write (a full disk, say), try again this much later. */
	private static final long RETRY_DELAY_SECONDS = 30;
	/** Overview tiles are rebuilt this long after the first tile under them is written. */
	private static final long OVERVIEW_DELAY_SECONDS = 2;

	/** The only thread that writes tiles. */
	private static final ScheduledThreadPoolExecutor WRITER = new ScheduledThreadPoolExecutor(1, task -> {
		Thread thread = new Thread(task, "orthomap-tile-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private final Path folder;
	/** Set by close(), whose flush writes everything; writes still scheduled then do nothing. */
	private volatile boolean closed;
	/** Recently used tiles, decompressed. Guarded by {@code this}. */
	private final LinkedHashMap<TileKey, int[]> cache = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<TileKey, int[]> eldest) {
			return this.size() > CACHED_TILES && !TileStore.this.unwritten.containsKey(eldest.getKey());
		}
	};
	/**
	 * Tiles changed since they were last written, kept until a write of exactly these pixels
	 * succeeds. They're never evicted from the cache meanwhile. Guarded by {@code this}.
	 */
	private final Map<TileKey, int[]> unwritten = new HashMap<>();

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
				synchronized (this) {
					this.cache.put(key, pixels);
					// Pending until written, like a bake.
					this.unwritten.put(key, pixels);
				}
				boolean saved = this.store(key, pixels);
				synchronized (this) {
					if (this.unwritten.get(key) == pixels) {
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

	/** The saved pixels of a tile, or null if there are none. Any thread. Don't modify the array. */
	public synchronized int @Nullable [] read(TileKey key) {
		int[] pending = this.unwritten.get(key);
		if (pending != null) {
			return pending;
		}
		int[] pixels = this.cache.get(key);
		if (pixels == null && !this.cache.containsKey(key)) {
			pixels = this.load(key);
			this.cache.put(key, pixels);
		}
		return pixels;
	}

	/** Records a fresh bake, full or partial, and schedules the write. Any thread. */
	public synchronized void baked(TileImage image) {
		TileKey key = image.key();
		int size = Projection.TILE_PIXELS;
		int[] pixels;
		if (image.isFull()) {
			pixels = image.mips()[0].clone();
		} else {
			int[] saved = this.read(key);
			pixels = saved == null ? new int[size * size] : saved.clone();
			for (TileImage.Patch patch : image.patches()) {
				int[] block = patch.mips()[0];
				int edge = TileImage.BLOCK;
				for (int y = 0; y < edge; y++) {
					System.arraycopy(block, y * edge, pixels, (patch.blockY() * edge + y) * size + patch.blockX() * edge, edge);
				}
			}
		}
		this.cache.put(key, pixels);
		boolean scheduled = this.unwritten.containsKey(key);
		this.unwritten.put(key, pixels);
		if (!scheduled) {
			WRITER.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** Writes everything pending and waits until it's on disk. Call when leaving the world. */
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		Future<?> flush = WRITER.submit(() -> {
			Map<TileKey, int[]> pending;
			synchronized (this) {
				pending = new HashMap<>(this.unwritten);
			}
			for (Map.Entry<TileKey, int[]> entry : pending.entrySet()) {
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
			Orthomap.LOGGER.error("Couldn't save map tiles", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Writes the newest pixels of a tile. Writer thread. */
	private void write(TileKey key) {
		if (this.closed) {
			return;
		}
		int[] pixels;
		synchronized (this) {
			pixels = this.unwritten.get(key);
		}
		if (pixels == null) {
			return;
		}
		boolean saved = this.store(key, pixels);
		if (saved && key.lod() == 0) {
			this.overviewsStale(key);
		}
		synchronized (this) {
			if (this.unwritten.get(key) != pixels) {
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

	private int @Nullable [] load(TileKey key) {
		Path file = this.file(key);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
			if (data.readInt() != MAGIC || data.readInt() != Projection.TILE_PIXELS) {
				return null;
			}
			int size = Projection.TILE_PIXELS;
			byte[] bytes = new InflaterInputStream(in).readNBytes(size * size * 4);
			if (bytes.length != size * size * 4) {
				return null;
			}
			int[] pixels = new int[size * size];
			ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
			return pixels;
		} catch (IOException e) {
			Orthomap.LOGGER.warn("Couldn't read saved tile {}", file, e);
			return null;
		}
	}

	/**
	 * Writes to a temporary file and moves it over the old one, so a crash can't leave half a tile.
	 * Returns whether it worked.
	 */
	private boolean store(TileKey key, int[] pixels) {
		Path file = this.file(key);
		try {
			Files.createDirectories(file.getParent());
			ByteBuffer buffer = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			buffer.asIntBuffer().put(pixels);
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
			Orthomap.LOGGER.error("Couldn't save tile {}", file, e);
			return false;
		}
	}
}
