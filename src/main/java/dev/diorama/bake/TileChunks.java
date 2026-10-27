package dev.diorama.bake;

import dev.diorama.Projection;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Which chunk each pixel of a surface tile shows, so land claims can be drawn on the ground they
 * cover. The map looks down at an angle, so one pixel row runs over many chunks from north to
 * south, one at each height, and only the ground the pixel shows says which.
 *
 * <p>A pixel's chunk x follows from its column. Its chunk z is kept as a byte: how many chunks it
 * is from the chunk where the pixel's row crosses {@link #REFERENCE_Y}, which the ground is never
 * more than a couple of dozen chunks from. {@link #NONE} where the tile shows nothing known.
 * Level-0 tiles work theirs out from their {@link TileSpots}; overview tiles keep their own, shrunk
 * from their children's like their pixels.
 */
public final class TileChunks {
	public static final byte NONE = Byte.MIN_VALUE;
	private static final int REFERENCE_Y = 64;
	private static final int SIZE = Projection.TILE_PIXELS;

	private TileChunks() {
	}

	/** The chunk x the pixels in column {@code px} show. */
	public static int chunkX(TileKey key, int px) {
		return Math.floorDiv((int) Math.floor(key.minU() + (px + 0.5) * key.units() / SIZE), 16);
	}

	/** The chunk z a pixel in row {@code py} shows, from its value (not {@link #NONE}). */
	public static int chunkZ(TileKey key, int py, byte value) {
		return base(key, py) + value;
	}

	/** The chunk where pixel row {@code py} crosses {@link #REFERENCE_Y}. */
	public static int base(TileKey key, int py) {
		double v = key.minV() + (py + 0.5) * key.units() / SIZE;
		return Math.floorDiv((int) Math.floor(Projection.zAt(v, REFERENCE_Y)), 16);
	}

	private static byte relative(int chunkZ, int base) {
		int d = chunkZ - base;
		return d > Byte.MAX_VALUE || d <= NONE ? NONE : (byte) d;
	}

	/** A level-0 tile's, from its spots; null if it has none. */
	public static byte @Nullable [] fromSpots(TileKey key, int @Nullable [] spots) {
		if (spots == null) {
			return null;
		}
		byte[] out = new byte[SIZE * SIZE];
		int perColumn = SIZE / TileSpots.COLUMNS;
		for (int py = 0; py < SIZE; py++) {
			int base = base(key, py);
			for (int column = 0; column < TileSpots.COLUMNS; column++) {
				int z = TileSpots.z(key, spots, column, py);
				byte value = z == Integer.MIN_VALUE ? NONE : relative(Math.floorDiv(z, 16), base);
				int i = py * SIZE + column * perColumn;
				Arrays.fill(out, i, i + perColumn, value);
			}
		}
		return out;
	}

	/**
	 * An overview tile's, from its four children's, in the order (0, 0), (1, 0), (0, 1), (1, 1);
	 * null if none of them has any. Each pixel takes one of the four it covers.
	 */
	public static byte @Nullable [] shrink(TileKey key, byte @Nullable [][] children) {
		int half = SIZE / 2;
		byte[] out = null;
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				byte[] child = children[dy * 2 + dx];
				if (child == null) {
					continue;
				}
				if (out == null) {
					out = new byte[SIZE * SIZE];
					Arrays.fill(out, NONE);
				}
				TileKey childKey = new TileKey(key.layer(), key.tileU() * 2 + dx, key.tileV() * 2 + dy, key.lod() - 1);
				for (int y = 0; y < half; y++) {
					int childRow = 2 * y + 1;
					int childBase = base(childKey, childRow);
					int row = dy * half + y;
					int base = base(key, row);
					for (int x = 0; x < half; x++) {
						byte value = child[childRow * SIZE + 2 * x + 1];
						out[row * SIZE + dx * half + x] = value == NONE ? NONE : relative(childBase + value, base);
					}
				}
			}
		}
		return out;
	}
}
