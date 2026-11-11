package dev.diorama.render;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.Looks;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ImageExport;
import dev.diorama.data.SeenBlocks;
import dev.diorama.data.TileStore;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>The image is the slice as it was when this was made: the chunks held then are kept, so
 * leaving the world partway doesn't empty the rest of it. Turning the map or changing its look
 * partway moves every pixel, and stops the export instead.
 */
public final class CaveSliceTiles implements ImageExport.Tiles {
	private static final int[] EMPTY = new int[0];

	private final ChunkSource source;
	private final int layer;
	private final Looks looks;
	private final boolean fogOfWar;
	/** How the map was turned and drawn when the export began. */
	private final Facing facing = Projection.facing();
	private final MapStyle style = MapStyle.get();
	/** The map units the level's chunks can show at this slice, {u0, v0, u1, v1}; null with no chunks. */
	private final double @Nullable [] bounds;
	/**
	 * The tiles a chunk can show in, by {@link #tileKey}. Chunks kept from somewhere far off leave
	 * empty ground between them and the rest, inside the bounds, which isn't worth tracing.
	 */
	private final Set<Long> withChunks = new HashSet<>();
	/**
	 * Tiles drawn so far, {@link #EMPTY} for one with nothing in it. Softly held: a big export under
	 * a small heap draws a tile again rather than run out of memory.
	 */
	private final ConcurrentHashMap<Long, SoftReference<int[]>> drawn = new ConcurrentHashMap<>();

	/** On the render thread, so the chunks, the bounds and the facing are of one moment. */
	public CaveSliceTiles(MapLevel level, int layer, Looks looks) {
		this.layer = layer;
		this.looks = looks;
		this.fogOfWar = level.fogOfWar();
		this.bounds = level.sliceBounds(layer);
		Map<Long, ChunkSnapshot> chunks = level.chunksNow();
		for (ChunkSnapshot chunk : chunks.values()) {
			// A unit over, for faces drawn past a block's own square.
			double[] box = MapLevel.sliceBox(chunk, layer);
			for (int tv = (int) Math.floor((box[1] - 1) / Projection.TILE_UNITS); tv <= (int) Math.floor((box[3] + 1) / Projection.TILE_UNITS); tv++) {
				for (int tu = (int) Math.floor((box[0] - 1) / Projection.TILE_UNITS); tu <= (int) Math.floor((box[2] + 1) / Projection.TILE_UNITS); tu++) {
					this.withChunks.add(tileKey(tu, tv));
				}
			}
		}
		Facing facing = this.facing;
		int minY = level.minY();
		int maxY = level.maxY();
		int topY = level.topY();
		int roof = level.roof();
		boolean depthCaves = level.depthCaves();
		SeenBlocks seen = level.seen();
		// As MapLevel answers a bake, from the chunks and the facing kept here.
		this.source = new ChunkSource() {
			@Override
			public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
				ChunkSnapshot chunk = chunks.get(ChunkSnapshot.key(facing.worldBlockX(chunkX, chunkZ), facing.worldBlockZ(chunkX, chunkZ)));
				return chunk == null ? null : chunk.turned(facing);
			}

			@Override
			public int minY() {
				return minY;
			}

			@Override
			public int maxY() {
				return maxY;
			}

			@Override
			public int topY() {
				return topY;
			}

			@Override
			public int roof() {
				return roof;
			}

			@Override
			public boolean depthCaves() {
				return depthCaves;
			}

			@Override
			public boolean isSeen(int x, int y, int z) {
				return seen.isSeen(facing.worldBlockX(x, z), y, facing.worldBlockZ(x, z));
			}
		};
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
		long key = tileKey(tileU, tileV);
		SoftReference<int[]> held = this.drawn.get(key);
		int[] pixels = held == null ? null : held.get();
		if (pixels == null) {
			pixels = TileBaker.reference(new TileKey(this.layer, tileU, tileV), this.source, this.looks, this.fogOfWar, null, null);
			// A bake reads the map's facing and angle as they are now: one traced across a change is wrong.
			if (Projection.facing() != this.facing || !MapStyle.get().equals(this.style)) {
				throw new IllegalStateException("the map was turned or its look changed while the image was being saved");
			}
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

	private static long tileKey(int tileU, int tileV) {
		return ((long) tileU << 32) | (tileV & 0xFFFFFFFFL);
	}

	/** Whether any chunk can show in a tile. Outside them there's nothing to trace. */
	private boolean inBounds(int tileU, int tileV, int lod) {
		if (this.bounds == null) {
			return false;
		}
		if (lod == 0) {
			return this.withChunks.contains(tileKey(tileU, tileV));
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
				// Nothing returned: a future would hold its tile's pixels, and only the soft cache may.
				Runnable draw = () -> this.pixels(tile[0], tile[1], 0);
				waiting.add(pool.submit(draw));
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
