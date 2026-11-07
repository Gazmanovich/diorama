package dev.diorama.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * The fog's clouds, built from Minecraft's own: vanilla's clouds.png (a resource pack's, if it has
 * one), one texel a cell, in three layers stacked like terraces. Each cell gets a code, three bits:
 * {@link #DECK}, the solid layer over land never explored; {@link #MIDDLE}, see-through cloud in
 * vanilla's own pattern, sparse far from the unexplored and denser close to it, and over the
 * unexplored half the cells; {@link #TOP}, a few cells stacked on that, gathered into big masses.
 * fog.fsh draws the codes; {@link ExportFog} paints them the same way.
 *
 * <p>Cells come in sizes of {@link #CELL} map units times a power of two, so zoomed out the map
 * draws bigger cells. A size's pattern is the smaller one's, four cells averaged into one, so a
 * bigger cell stands where the smaller ones cluster and one size can grow into the next.
 */
public final class VanillaClouds {
	/** The smallest cell, in map units: vanilla's clouds are 12 blocks a texel. */
	public static final double CELL = 12.8;
	public static final int DECK = 1;
	public static final int MIDDLE = 2;
	public static final int TOP = 4;
	/**
	 * Every size's pattern repeats over vanilla's whole texture, 256 of the smallest cells (3277 map
	 * units; vanilla's own sky repeats every 3072 blocks). Cells are numbered from map 0.
	 */
	private static final int REPEAT = 256;
	/** How far into explored land the see-through clouds reach, in map units, for the smallest cells; matches fog.fsh. */
	public static final double RAMP = 112;
	/**
	 * The biggest size with a pattern of its own (16 cells of the smallest, 16 a repeat). Bigger
	 * cells, only seen zoomed far out, take that pattern turned or flipped, a different way for each
	 * block of 16 cells, so it doesn't repeat across the screen.
	 */
	private static final int LARGEST = 4;
	/** Where in vanilla's texture the middle and top layers take their pattern. */
	private static final int MIDDLE_U = 37;
	private static final int MIDDLE_V = 151;
	private static final int TOP_U = 97;
	private static final int TOP_V = 41;

	private static boolean[] texture;
	private static long version;
	private static final Map<Long, float[]> ORDERS = new HashMap<>();

	private VanillaClouds() {
	}

	/**
	 * The code of the cell {@code (u, v)} of size {@code 2^k} times {@link #CELL}, counted from map 0,
	 * whose middle is {@code s} map units from explored land (positive over the unexplored).
	 */
	public static int code(double s, long u, long v, int k) {
		double size = CELL * (1 << k);
		int level = Math.min(k, LARGEST);
		int period = REPEAT >> level;
		int i = (int) Math.floorMod(u, (long) period);
		int j = (int) Math.floorMod(v, (long) period);
		if (k > LARGEST) {
			int way = (int) (hash(Math.floorDiv(u, period) * 12.9898 + Math.floorDiv(v, period) * 78.233 + k * 37.719) * 8);
			if ((way & 1) != 0) {
				i = period - 1 - i;
			}
			if ((way & 2) != 0) {
				j = period - 1 - j;
			}
			if ((way & 4) != 0) {
				int swap = i;
				i = j;
				j = swap;
			}
		}
		// Its furthest point from explored land, near enough: the middle plus most of a half
		// diagonal. Big cells reach less far, so zoomed out they don't eat into explored land; the
		// part of one over unexplored land they leave is covered by the no-map fill in fog.fsh.
		double r = size / CELL;
		double far = s + 0.6 * size * Math.min(1, 2 / r);
		// The see-through band narrows as cells grow, so zoomed out it doesn't cover explored land.
		double ramp = RAMP * Math.min(1, 1 / Math.sqrt(r));
		// Big masses: the top layer gathers where slow noise is high and thins where it's low.
		double big = bigNoise((double) i / period, (double) j / period);
		int code = 0;
		if (far > 2) {
			code |= DECK;
		}
		double toward = Math.max(0, Math.min(1, (far + ramp) / ramp));
		double middle = far > 2 ? 0.5 * (0.4 + 1.2 * big) : 0.15 + 0.55 * Math.pow(toward, 1.5);
		if (far > -ramp && order(k, MIDDLE_U, MIDDLE_V)[j * period + i] <= middle) {
			code |= MIDDLE;
		}
		double inner = Math.max(0, Math.min(1, far / (48 * Math.sqrt(r))));
		if (far > 10 && order(k, TOP_U, TOP_V)[j * period + i] <= 0.4 * inner * (0.25 + 1.5 * big)) {
			code |= TOP;
		}
		return code;
	}

	/**
	 * How far into explored land the clouds drawn with cells of {@code size} map units can reach, in
	 * map units: the see-through band, the cell over its edge and the cover's blend across a cell.
	 */
	public static double reachInto(double size) {
		double r = size / CELL;
		return RAMP * Math.min(1, 1 / Math.sqrt(r)) + 0.6 * size * Math.min(1, 2 / r) + size;
	}

	/** The biggest cells a view draws, in map units, with {@code unitsPerPixel} map units to a screen pixel (fog.fsh: about 40 pixels, a power of two). */
	public static double drawnCell(double unitsPerPixel) {
		return CELL * Math.pow(2, Math.max(0, Math.ceil(Math.log(40 * unitsPerPixel / CELL) / Math.log(2))));
	}

	/** Forgets the texture, so the next code reads the current resource packs' clouds.png; maps then build their cells again. */
	public static synchronized void reload() {
		texture = null;
		ORDERS.clear();
		version++;
	}

	/**
	 * Reads clouds.png and works out the fill orders, if not done since the last reload. Called on
	 * the render thread before a map's cells are built on its worker, so the worker never reads the
	 * resource packs while they reload.
	 */
	public static synchronized void prepare() {
		texture();
		for (int k = 0; k <= LARGEST; k++) {
			order(k, MIDDLE_U, MIDDLE_V);
			order(k, TOP_U, TOP_V);
		}
	}

	/** Changes on each {@link #reload}. */
	public static synchronized long version() {
		return version;
	}

	/**
	 * Each cell's place in the order the layer fills in, 0 to 1, over a {@link #REPEAT}: the cells
	 * of a layer at coverage c are those at c or under. At the smallest size vanilla's own clouds
	 * come first, then the cells next to them; at each bigger size, the four cells inside averaged
	 * and ranked again, so the same coverage fills the same share of cells at every size.
	 */
	private static synchronized float[] order(int k, int ou, int ov) {
		int level = Math.min(k, LARGEST);
		long key = (long) level << 40 | (long) ou << 20 | ov;
		float[] known = ORDERS.get(key);
		if (known != null) {
			return known;
		}
		int period = REPEAT >> level;
		double[] score = new double[period * period];
		if (level == 0) {
			boolean[] tex = texture();
			double sigma = 1.2;
			int reach = 3;
			for (int j = 0; j < period; j++) {
				for (int i = 0; i < period; i++) {
					double near = 0;
					double total = 0;
					for (int dj = -reach; dj <= reach; dj++) {
						for (int di = -reach; di <= reach; di++) {
							double w = Math.exp(-(di * di + dj * dj) / (2 * sigma * sigma));
							near += tex[texel(Math.floorMod(i + di, period) + ou, Math.floorMod(j + dj, period) + ov)] ? w : 0;
							total += w;
						}
					}
					double h = hash(i * 127.1 + j * 311.7 + ou * 74.7);
					score[j * period + i] = tex[texel(i + ou, j + ov)] ? -1 + 0.01 * h : -near / total + 0.25 * (h - 0.5);
				}
			}
		} else {
			float[] finer = order(level - 1, ou, ov);
			int fp = period * 2;
			for (int j = 0; j < period; j++) {
				for (int i = 0; i < period; i++) {
					score[j * period + i] = finer[2 * j * fp + 2 * i] + finer[2 * j * fp + 2 * i + 1] + finer[(2 * j + 1) * fp + 2 * i] + finer[(2 * j + 1) * fp + 2 * i + 1];
				}
			}
		}
		Integer[] index = new Integer[score.length];
		for (int n = 0; n < index.length; n++) {
			index[n] = n;
		}
		Arrays.sort(index, (a, b) -> Double.compare(score[a], score[b]));
		float[] ranked = new float[score.length];
		for (int n = 0; n < index.length; n++) {
			ranked[index[n]] = (n + 0.5F) / index.length;
		}
		ORDERS.put(key, ranked);
		return ranked;
	}

	private static int texel(int u, int v) {
		return Math.floorMod(v, 256) * 256 + Math.floorMod(u, 256);
	}

	/** Vanilla's clouds.png as cloud or not, 256 a side (a bigger or smaller one is tiled to that). */
	private static boolean[] texture() {
		if (texture != null) {
			return texture;
		}
		boolean[] cells = new boolean[256 * 256];
		boolean read = false;
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(Identifier.withDefaultNamespace("textures/environment/clouds.png"));
			if (resource.isPresent()) {
				try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
					for (int y = 0; y < 256; y++) {
						for (int x = 0; x < 256; x++) {
							cells[y * 256 + x] = WaypointIcons.argb(image, x % image.getWidth(), y % image.getHeight()) >>> 24 > 0;
						}
					}
					read = true;
				}
			}
		} catch (Exception e) {
			Diorama.LOGGER.warn("Can't read Minecraft's clouds.png for the map's clouds", e);
		}
		if (!read) {
			// No texture (a test without resources, a broken pack): a scattered stand-in, about as much cloud.
			for (int k = 0; k < cells.length; k++) {
				cells[k] = hash(k * 0.731) < 0.27;
			}
		}
		texture = cells;
		return cells;
	}

	/** Smooth value noise over the repeat, ten lattice steps across it, 0 to 1. */
	private static double bigNoise(double u, double v) {
		double gu = u * 10;
		double gv = v * 10;
		int i = (int) Math.floor(gu);
		int j = (int) Math.floor(gv);
		double fu = gu - i;
		double fv = gv - j;
		fu = fu * fu * (3 - 2 * fu);
		fv = fv * fv * (3 - 2 * fv);
		double a = lattice(i, j);
		double b = lattice(i + 1, j);
		double c = lattice(i, j + 1);
		double d = lattice(i + 1, j + 1);
		return (a + (b - a) * fu) * (1 - fv) + (c + (d - c) * fu) * fv;
	}

	private static double lattice(int i, int j) {
		return hash(Math.floorMod(i, 10) * 17.3 + Math.floorMod(j, 10) * 91.1);
	}

	private static double hash(double v) {
		double s = Math.sin(v) * 43758.5453;
		return s - Math.floor(s);
	}
}
