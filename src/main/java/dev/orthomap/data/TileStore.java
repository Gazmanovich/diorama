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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * saved. Writes happen on a background thread a few seconds after a tile last changed.
 *
 * <p>File format: {@code OMT1}, the edge length, then the level-0 ABGR pixels deflated.
 */
public final class TileStore {
	private static final int MAGIC = 0x4F4D5431;
	private static final int CACHED_TILES = 24;
	private static final long WRITE_DELAY_SECONDS = 3;

	private final Path folder;
	private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
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

	/** Writes everything pending now and stops the writer. Call when leaving the world. */
	public void close() {
		this.writer.shutdown();
		Map<TileKey, int[]> pending;
		synchronized (this) {
			pending = new HashMap<>(this.unwritten);
			this.unwritten.clear();
		}
		try {
			this.writer.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		for (Map.Entry<TileKey, int[]> entry : pending.entrySet()) {
			this.store(entry.getKey(), entry.getValue());
		}
	}

	private void write(TileKey key) {
		int[] pixels;
		synchronized (this) {
			pixels = this.unwritten.remove(key);
		}
		if (pixels != null) {
			this.store(key, pixels);
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

	/** Writes to a temporary file and moves it over the old one, so a crash can't leave half a tile. */
	private void store(TileKey key, int[] pixels) {
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
		} catch (IOException | RuntimeException e) {
			Orthomap.LOGGER.error("Couldn't save tile {}", file, e);
		}
	}
}
