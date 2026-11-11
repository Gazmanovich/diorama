package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.bake.Looks;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ImageExport;
import dev.diorama.data.TileStore;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleConsumer;
import org.jspecify.annotations.Nullable;

/**
 * A cave slice's tiles for an exported image. Cave slices aren't saved: the map draws them from
 * the chunks it holds, so an export draws them the same way, from those chunks, for as much of the
 * slice as they cover. Traced on the CPU at full quality whatever the map's settings, on a few
 * quiet threads of its own.
 */
public final class CaveSliceTiles implements ImageExport.Tiles {
	private static final int[] EMPTY = new int[0];

	private final MapLevel level;
	private final int layer;
	private final Looks looks;
	private final boolean fogOfWar;
	/** The map units the level's chunks can show at this slice, {u0, v0, u1, v1}; null with no chunks. */
	private final double @Nullable [] bounds;
	/**
	 * Tiles drawn so far, {@link #EMPTY} for one with nothing in it. Softly held: a big export under
	 * a small heap draws a tile again rather than run out of memory.
	 */
	private final ConcurrentHashMap<Long, SoftReference<int[]>> drawn = new ConcurrentHashMap<>();

	public CaveSliceTiles(MapLevel level, int layer, Looks looks) {
		this.level = level;
		this.layer = layer;
		this.looks = looks;
		this.fogOfWar = level.fogOfWar();
		this.bounds = level.sliceBounds(layer);
	}

	@Override
	public TileStore.@Nullable Saved tile(int tileU, int tileV, int lod) {
		int[] pixels = this.pixels(tileU, tileV, lod);
		return pixels == null ? null : new TileStore.Saved(pixels, null, null);
	}

	private int @Nullable [] pixels(int tileU, int tileV, int lod) {
		if (!this.inBounds(tileU, tileV, lod)) {
			return null;
		}
		if (lod > 0) {
			return this.shrunk(tileU, tileV, lod);
		}
		long key = ((long) tileU << 32) | (tileV & 0xFFFFFFFFL);
		SoftReference<int[]> held = this.drawn.get(key);
		int[] pixels = held == null ? null : held.get();
		if (pixels == null) {
			pixels = TileBaker.reference(new TileKey(this.layer, tileU, tileV), this.level, this.looks, this.fogOfWar, null, null);
			boolean empty = true;
			for (int i = 0; i < pixels.length && empty; i++) {
				empty = (pixels[i] >>> 24) == 0;
			}
			if (empty) {
				pixels = EMPTY;
			}
			this.drawn.put(key, new SoftReference<>(pixels));
		}
		return pixels == EMPTY ? null : pixels;
	}

	/** One level up from four children, each shrunk to a quarter, as the saved map's overview tiles are made. */
	private int @Nullable [] shrunk(int tileU, int tileV, int lod) {
		int size = Projection.TILE_PIXELS;
		int half = size / 2;
		int[] out = null;
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				int[] c = this.pixels(tileU * 2 + dx, tileV * 2 + dy, lod - 1);
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

	/** Whether any chunk can show in a tile. Outside them there's nothing to trace. */
	private boolean inBounds(int tileU, int tileV, int lod) {
		if (this.bounds == null) {
			return false;
		}
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		return (tileU + 1) * units > this.bounds[0] && tileU * units < this.bounds[2] && (tileV + 1) * units > this.bounds[1] && tileV * units < this.bounds[3];
	}

	/**
	 * Draws every tile under {@code area} and the margin the fog reads around it, several at a time.
	 * Nearly all of a cave export's time goes here.
	 */
	@Override
	public void prepare(ImageExport.Area area, DoubleConsumer progress) {
		double[] units = area.units();
		double margin = ExportFog.margin(area.lod());
		int size = Projection.TILE_UNITS;
		List<int[]> tiles = new ArrayList<>();
		for (int tv = (int) Math.floor((units[1] - margin) / size); tv <= (int) Math.floor((units[3] + margin) / size); tv++) {
			for (int tu = (int) Math.floor((units[0] - margin) / size); tu <= (int) Math.floor((units[2] + margin) / size); tu++) {
				if (this.inBounds(tu, tv, 0)) {
					tiles.add(new int[] {tu, tv});
				}
			}
		}
		if (tiles.isEmpty()) {
			return;
		}
		// Half the processors, under the game's threads, so the game stays smooth meanwhile.
		int threads = Math.max(1, Math.min(tiles.size(), Runtime.getRuntime().availableProcessors() / 2));
		ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
			Thread thread = new Thread(task, "diorama-export-cave");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		});
		try {
			List<Future<?>> waiting = new ArrayList<>();
			for (int[] tile : tiles) {
				waiting.add(pool.submit(() -> this.pixels(tile[0], tile[1], 0)));
			}
			for (int i = 0; i < waiting.size(); i++) {
				waiting.get(i).get();
				progress.accept((i + 1.0) / waiting.size());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while drawing the cave", e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof RuntimeException failed ? failed : new IllegalStateException(e.getCause());
		} finally {
			pool.shutdownNow();
		}
	}

	@Override
	public double prepareShare() {
		return 0.9;
	}
}
