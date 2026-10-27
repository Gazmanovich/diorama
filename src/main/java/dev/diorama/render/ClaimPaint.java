package dev.diorama.render;

import dev.diorama.Claims;
import dev.diorama.Projection;
import dev.diorama.bake.TileChunks;
import dev.diorama.bake.TileKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Paints land claims over a tile, on the ground they cover: every pixel showing a claimed chunk is
 * tinted its owner's colour, more strongly along the edges where the owner changes, as Xaero's
 * maps do. A force-loaded chunk is a claim of its own kind, tinted a little more, so it gets an
 * edge where it meets the owner's other chunks.
 */
final class ClaimPaint {
	// Light enough that the ground still reads through a claim (Dara's pick, 2026-10-26): 20%, 32%
	// force loaded, 80% edges.
	static final int FILL_ALPHA = 0x33;
	static final int FORCE_LOADED_ALPHA = 0x52;
	static final int EDGE_ALPHA = 0xCC;

	private ClaimPaint() {
	}

	/**
	 * ABGR pixels for the tile's overlay, or null when it shows no claimed ground. {@code north}
	 * and {@code south} are the chunks of the tiles above and below, where saved, so an edge
	 * running along the tile's top or bottom shows.
	 */
	static int @Nullable [] paint(TileKey key, byte[] chunks, byte @Nullable [] north, byte @Nullable [] south, Claims.Snapshot claims) {
		int size = Projection.TILE_PIXELS;
		int[] chunkX = new int[size];
		for (int px = 0; px < size; px++) {
			chunkX[px] = TileChunks.chunkX(key, px);
		}
		// Each pixel's claim as an index into owners, 0 for none.
		int[] ids = new int[size * size];
		List<Claims.Claim> owners = new ArrayList<>();
		owners.add(null);
		Map<Claims.Claim, Integer> idOf = new HashMap<>();
		boolean any = false;
		for (int py = 0; py < size; py++) {
			int base = TileChunks.base(key, py);
			int lastX = Integer.MIN_VALUE;
			int lastZ = Integer.MIN_VALUE;
			int lastId = 0;
			for (int px = 0; px < size; px++) {
				int i = py * size + px;
				byte value = chunks[i];
				if (value == TileChunks.NONE) {
					continue;
				}
				int cx = chunkX[px];
				int cz = base + value;
				if (cx != lastX || cz != lastZ) {
					lastX = cx;
					lastZ = cz;
					Claims.Claim claim = claims.at(cx, cz);
					if (claim == null) {
						lastId = 0;
					} else {
						Integer id = idOf.get(claim);
						if (id == null) {
							id = owners.size();
							owners.add(claim);
							idOf.put(claim, id);
						}
						lastId = id;
					}
				}
				ids[i] = lastId;
				any |= lastId != 0;
			}
		}
		if (!any) {
			return null;
		}
		// Past the tile's east and west sides, which are chunk borders: the next chunk over, at the
		// row's ground. Its z can differ on a slope, but a claim's edge along a side still shows.
		int[] west = new int[size];
		int[] east = new int[size];
		int westX = TileChunks.chunkX(key, -1);
		int eastX = TileChunks.chunkX(key, size);
		for (int py = 0; py < size; py++) {
			int base = TileChunks.base(key, py);
			west[py] = side(chunks[py * size], ids[py * size], westX, base, claims, owners, idOf);
			east[py] = side(chunks[py * size + size - 1], ids[py * size + size - 1], eastX, base, claims, owners, idOf);
		}
		// Edges about half a block wide on detailed tiles, a pixel on overview tiles.
		int edge = key.lod() == 0 ? 2 : 1;
		// The rows past the top and bottom, nearest first; -1 where nothing is known there.
		int[][] above = new int[edge][];
		int[][] below = new int[edge][];
		TileKey northKey = new TileKey(key.layer(), key.tileU(), key.tileV() - 1, key.lod());
		TileKey southKey = new TileKey(key.layer(), key.tileU(), key.tileV() + 1, key.lod());
		for (int k = 0; k < edge; k++) {
			above[k] = row(north, northKey, size - 1 - k, chunkX, claims, owners, idOf);
			below[k] = row(south, southKey, k, chunkX, claims, owners, idOf);
		}
		int[] fill = new int[owners.size()];
		int[] line = new int[owners.size()];
		for (int id = 1; id < owners.size(); id++) {
			Claims.Claim claim = owners.get(id);
			fill[id] = abgr(claim.forceLoaded() ? FORCE_LOADED_ALPHA : FILL_ALPHA, claim.rgb());
			line[id] = abgr(EDGE_ALPHA, claim.rgb());
		}
		int[] pixels = new int[size * size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int i = py * size + px;
				int id = ids[i];
				if (id == 0) {
					continue;
				}
				pixels[i] = onEdge(ids, west, east, above, below, size, px, py, id, edge) ? line[id] : fill[id];
			}
		}
		return pixels;
	}

	/**
	 * The claim beside a side pixel, in the chunk column {@code chunkX} past the side; the pixel's
	 * own where it shows nothing known.
	 */
	private static int side(byte value, int own, int chunkX, int base, Claims.Snapshot claims, List<Claims.Claim> owners, Map<Claims.Claim, Integer> idOf) {
		if (value == TileChunks.NONE) {
			return own;
		}
		Claims.Claim claim = claims.at(chunkX, base + value);
		if (claim == null) {
			return 0;
		}
		Integer id = idOf.get(claim);
		if (id == null) {
			id = owners.size();
			owners.add(claim);
			idOf.put(claim, id);
		}
		return id;
	}

	/** The claims of one row of a neighbouring tile, as ids; -1 where it shows nothing known or wasn't saved. */
	private static int[] row(byte @Nullable [] chunks, TileKey key, int py, int[] chunkX, Claims.Snapshot claims, List<Claims.Claim> owners, Map<Claims.Claim, Integer> idOf) {
		int size = Projection.TILE_PIXELS;
		int[] row = new int[size];
		if (chunks == null) {
			Arrays.fill(row, -1);
			return row;
		}
		int base = TileChunks.base(key, py);
		for (int px = 0; px < size; px++) {
			byte value = chunks[py * size + px];
			row[px] = value == TileChunks.NONE ? -1 : side(value, -1, chunkX[px], base, claims, owners, idOf);
		}
		return row;
	}

	/**
	 * Whether a pixel of claim {@code id} is within {@code edge} pixels of another claim's, or of
	 * unclaimed ground: inside the tile, or past any of its sides. Past the top and bottom, ground
	 * nothing is known about counts as the same claim.
	 */
	private static boolean onEdge(int[] ids, int[] west, int[] east, int[][] above, int[][] below, int size, int px, int py, int id, int edge) {
		for (int d = 1; d <= edge; d++) {
			if ((px - d >= 0 ? ids[py * size + px - d] : west[py]) != id
				|| (px + d < size ? ids[py * size + px + d] : east[py]) != id
				|| differs(py - d >= 0 ? ids[(py - d) * size + px] : above[d - py - 1][px], id)
				|| differs(py + d < size ? ids[(py + d) * size + px] : below[py + d - size][px], id)) {
				return true;
			}
		}
		return false;
	}

	private static boolean differs(int other, int id) {
		return other != -1 && other != id;
	}

	private static int abgr(int alpha, int rgb) {
		return alpha << 24 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
	}
}
