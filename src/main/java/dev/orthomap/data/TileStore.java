package dev.orthomap.data;

import dev.orthomap.Orthomap;
import dev.orthomap.Projection;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * saved. Writes happen on one background thread, a few seconds after a tile first changes.
 *
 * <p>File format: {@code OMT1}, the edge length, then the level-0 ABGR pixels deflated.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int CACHED_TILES = 24;
	private static final long WRITE_DELAY_SECONDS = 3;
	/** After a failed write (a full disk, say), try again this much later. */
	private static final long RETRY_DELAY_SECONDS = 30;

	private final Path folder;
	/** The only thread that writes tiles, so two writes of one tile can never interleave. */
	private final ScheduledThreadPoolExecutor writer = new ScheduledThreadPoolExecutor(1, task -> {
		Thread thread = new Thread(task, "orthomap-tile-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});
	/** Recently used tiles, decompressed. Guarded by {@code this}. */
	private final LinkedHashMap<TileKey, int[]> cache = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<TileKey, int[]> eldest) {
			return this.size() > CACHED_TILES && !TileStore.this.unwritten.containsKey(eldest.getKey());
		}
	};
	/** Tiles changed since they were last written. Guarded by {@code this}. */
	private final Map<TileKey, int[]> unwritten = new HashMap<>();

	public TileStore(Path folder) {
		this.folder = folder;
		// close() writes everything itself; delayed writes still waiting then are dropped.
		this.writer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/** The saved pixels of a tile, or null if there are none. Any thread. Don't modify the array. */
	public synchronized int @Nullable [] read(TileKey key) {
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
			this.writer.schedule(() -> this.write(key), WRITE_DELAY_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** Writes everything pending on the writer thread, then stops it. Call when leaving the world. */
	public void close() {
		if (this.writer.isShutdown()) {
			return;
		}
		this.writer.execute(() -> {
			Map<TileKey, int[]> pending;
			synchronized (this) {
				pending = new HashMap<>(this.unwritten);
				this.unwritten.clear();
			}
			for (Map.Entry<TileKey, int[]> entry : pending.entrySet()) {
				this.store(entry.getKey(), entry.getValue());
			}
		});
		this.writer.shutdown();
		try {
			if (!this.writer.awaitTermination(15, TimeUnit.SECONDS)) {
				Orthomap.LOGGER.warn("Saving map tiles took too long; some may not be saved");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void write(TileKey key) {
		int[] pixels;
		synchronized (this) {
			pixels = this.unwritten.remove(key);
		}
		if (pixels != null && !this.store(key, pixels)) {
			synchronized (this) {
				// Keep it pending, unless a newer bake already replaced it.
				if (this.unwritten.putIfAbsent(key, pixels) == null && !this.writer.isShutdown()) {
					this.writer.schedule(() -> this.write(key), RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
				}
			}
		}
		synchronized (this) {
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
		return this.folder.resolve(key.tileU() + "_" + key.tileV() + ".omt");
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
			Files.createDirectories(this.folder);
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
