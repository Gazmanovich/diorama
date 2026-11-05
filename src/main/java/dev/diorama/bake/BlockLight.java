package dev.diorama.bake;

import dev.diorama.Projection;
import org.jspecify.annotations.Nullable;

/**
 * The block light on a sky view tile: torches, lamps and lava, which light the map at night. The
 * day picture holds the sun's light; night is drawn from it and this when the map is shown, so no
 * second picture is baked or stored.
 *
 * <p>Kept at a quarter of the tile's resolution, {@link #EDGE} a side: a value per block across, the
 * average of the pixels under it, which is as fine as the game's light is and draws as a soft glow.
 * A value is the block light level (0..15) where the pixels' samples landed, scaled by 17, so 255
 * is level 15 everywhere. A tile with no light anywhere has all zeros; one saved before block light
 * was kept has none at all, and draws at night with no light of its own until it's baked again.
 */
public final class BlockLight {
	/** Pixels per light value each way. */
	public static final int SCALE = 4;
	public static final int EDGE = Projection.TILE_PIXELS / SCALE;

	private BlockLight() {
	}

	/** A pixel's value from the sum of its known samples' levels. */
	static int encode(int sum, int known) {
		return known == 0 ? 0 : (sum * 17 + known / 2) / known;
	}

	/**
	 * The light of {@code edge} x {@code edge} freshly baked pixels at (px0, py0) of the tile, from
	 * their own light {@code pixelLight}: where the saved pixel is better known and stays (as
	 * {@code TileBaker.merge} keeps it; call before that merge, while {@code pixels} is the fresh
	 * bake), the saved light under it, else the pixel's own; then shrunk to {@link #SCALE} times
	 * fewer each way, weighted by how much of each pixel is known.
	 */
	static byte[] shrink(byte[] pixelLight, int[] pixels, int @Nullable [] saved, byte @Nullable [] savedLight, int px0, int py0, int edge) {
		int size = Projection.TILE_PIXELS;
		int out = edge / SCALE;
		byte[] light = new byte[out * out];
		for (int ly = 0; ly < out; ly++) {
			for (int lx = 0; lx < out; lx++) {
				int sum = 0;
				int weight = 0;
				for (int dy = 0; dy < SCALE; dy++) {
					for (int dx = 0; dx < SCALE; dx++) {
						int x = lx * SCALE + dx;
						int y = ly * SCALE + dy;
						int i = y * edge + x;
						int alpha = pixels[i] >>> 24;
						int value = pixelLight[i] & 0xFF;
						if (saved != null) {
							int s = (py0 + y) * size + px0 + x;
							int savedAlpha = saved[s] >>> 24;
							if (savedAlpha > alpha) {
								alpha = savedAlpha;
								value = savedLight == null ? 0 : savedLight[((py0 + y) / SCALE) * EDGE + (px0 + x) / SCALE] & 0xFF;
							}
						}
						sum += value * alpha;
						weight += alpha;
					}
				}
				light[ly * out + lx] = (byte) (weight == 0 ? 0 : (sum + weight / 2) / weight);
			}
		}
		return light;
	}

	/**
	 * An overview tile's light from its four children's, each shrunk to a quarter, null where a
	 * child has none; the result is null if none has any. Each value is weighted by how much of the
	 * child's pixels under it are known, so unknown ground doesn't darken the glow at its edge.
	 */
	public static byte @Nullable [] overview(byte @Nullable [][] children, int @Nullable [][] childPixels) {
		byte[] out = null;
		int half = EDGE / 2;
		int size = Projection.TILE_PIXELS;
		for (int c = 0; c < 4; c++) {
			byte[] l = children[c];
			int[] p = childPixels[c];
			if (l == null || p == null) {
				continue;
			}
			if (out == null) {
				out = new byte[EDGE * EDGE];
			}
			int ox = (c & 1) * half;
			int oy = (c >> 1) * half;
			for (int y = 0; y < half; y++) {
				for (int x = 0; x < half; x++) {
					int sum = 0;
					int weight = 0;
					for (int d = 0; d < 4; d++) {
						int lx = 2 * x + (d & 1);
						int ly = 2 * y + (d >> 1);
						int w = known(p, lx, ly, size);
						sum += (l[ly * EDGE + lx] & 0xFF) * w;
						weight += w;
					}
					out[(oy + y) * EDGE + ox + x] = (byte) (weight == 0 ? 0 : (sum + weight / 2) / weight);
				}
			}
		}
		return out;
	}

	/** How much of the pixels under light value (lx, ly) is known: their alphas added up. */
	private static int known(int[] pixels, int lx, int ly, int size) {
		int sum = 0;
		for (int dy = 0; dy < SCALE; dy++) {
			int row = (ly * SCALE + dy) * size + lx * SCALE;
			for (int dx = 0; dx < SCALE; dx++) {
				sum += pixels[row + dx] >>> 24;
			}
		}
		return sum;
	}

	/** Mip levels of the light, each value the plain average of the four under it, as many as {@code levels}. */
	public static byte[][] mips(byte[] base, int levels) {
		byte[][] out = new byte[levels][];
		out[0] = base;
		for (int level = 1; level < levels; level++) {
			int src = EDGE >> (level - 1);
			int dst = src >> 1;
			byte[] from = out[level - 1];
			byte[] to = new byte[dst * dst];
			for (int y = 0; y < dst; y++) {
				for (int x = 0; x < dst; x++) {
					int i = (y * 2) * src + x * 2;
					to[y * dst + x] = (byte) (((from[i] & 0xFF) + (from[i + 1] & 0xFF) + (from[i + src] & 0xFF) + (from[i + src + 1] & 0xFF) + 2) / 4);
				}
			}
			out[level] = to;
		}
		return out;
	}

	/** Whether any value has light. */
	public static boolean any(byte @Nullable [] light) {
		if (light == null) {
			return false;
		}
		for (byte b : light) {
			if (b != 0) {
				return true;
			}
		}
		return false;
	}
}
