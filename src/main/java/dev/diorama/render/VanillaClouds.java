package dev.diorama.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.diorama.Diorama;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

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

	private static volatile @Nullable Pattern current;
	private static long version;

	/**
	 * The fill orders worked out from one read of clouds.png, never changed after: a build takes one
	 * ({@link #prepare}) and keeps it, so a resource reload during it can't mix two patterns.
	 */
	public static final class Pattern {
		private final boolean[] texture;
		private float[][] middle;
		private float[][] top;

		private Pattern(boolean[] texture) {
			this.texture = texture;
		}

		/** The orders, worked out the first time a build asks, on its own thread. */
		private synchronized void orders() {
			if (this.middle != null) {
				return;
			}
			float[][] middle = new float[LARGEST + 1][];
			float[][] top = new float[LARGEST + 1][];
			for (int level = 0; level <= LARGEST; level++) {
				middle[level] = order(this.texture, level, level == 0 ? null : middle[level - 1], MIDDLE_U, MIDDLE_V);
				top[level] = order(this.texture, level, level == 0 ? null : top[level - 1], TOP_U, TOP_V);
			}
			this.top = top;
			this.middle = middle;
		}
	}

	private VanillaClouds() {
	}

	/**
	 * The code of the cell {@code (u, v)} of size {@code 2^k} times {@link #CELL}, counted from map 0,
	 * whose middle is {@code s} map units from explored land (positive over the unexplored).
	 */
	public static int code(Pattern pattern, double s, long u, long v, int k) {
		pattern.orders();
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
		if (far > -ramp && pattern.middle[level][j * period + i] <= middle) {
			code |= MIDDLE;
		}
		double inner = Math.max(0, Math.min(1, far / (48 * Math.sqrt(r))));
		if (far > 10 && pattern.top[level][j * period + i] <= 0.4 * inner * (0.25 + 1.5 * big)) {
			code |= TOP;
		}
		return code;
	}

	/** Forgets the pattern, so the next {@link #prepare} reads the current resource packs' clouds.png; maps then build their cells again. */
	public static synchronized void reload() {
		current = null;
		version++;
	}

	/**
	 * The pattern from clouds.png, read if not since the last reload. Called on the render thread
	 * and the result handed to a build on another thread, so no other thread reads the resource
	 * packs, and a build keeps one pattern whatever reloads meanwhile. Only the read is here: the
	 * build works out the orders.
	 */
	public static synchronized Pattern prepare() {
		Pattern pattern = current;
		if (pattern == null) {
			pattern = new Pattern(texture());
			current = pattern;
		}
		return pattern;
	}

	/** Changes on each {@link #reload}. */
	public static synchronized long version() {
		return version;
	}

	/**
	 * Each cell's place in the order the layer fills in, 0 to 1, over a {@link #REPEAT}: the cells
	 * of a layer at coverage c are those at c or under. At the smallest size vanilla's own clouds
	 * come first, then the cells next to them; at each bigger size, the four cells inside
	 * ({@code finer}) averaged and ranked again, so the same coverage fills the same share of cells
	 * at every size.
	 */
	private static float[] order(boolean[] tex, int level, float @Nullable [] finer, int ou, int ov) {
		int period = REPEAT >> level;
		double[] score = new double[period * period];
		if (finer == null) {
			for (int j = 0; j < period; j++) {
				for (int i = 0; i < period; i++) {
					double near = 0;
					for (int dj = -NEAR_REACH; dj <= NEAR_REACH; dj++) {
						for (int di = -NEAR_REACH; di <= NEAR_REACH; di++) {
							if (tex[texel(Math.floorMod(i + di, period) + ou, Math.floorMod(j + dj, period) + ov)]) {
								near += NEAR_WEIGHTS[(dj + NEAR_REACH) * (2 * NEAR_REACH + 1) + di + NEAR_REACH];
							}
						}
					}
					double total = 1;
					double h = hash(i * 127.1 + j * 311.7 + ou * 74.7);
					score[j * period + i] = tex[texel(i + ou, j + ov)] ? -1 + 0.01 * h : -near / total + 0.25 * (h - 0.5);
				}
			}
		} else {
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
		return ranked;
	}

	/** How much each cell around a cell counts toward its being next to vanilla's clouds: a Gaussian, summing to 1. */
	private static final int NEAR_REACH = 3;
	private static final double[] NEAR_WEIGHTS = nearWeights();

	private static double[] nearWeights() {
		int side = 2 * NEAR_REACH + 1;
		double[] weights = new double[side * side];
		double total = 0;
		for (int dj = -NEAR_REACH; dj <= NEAR_REACH; dj++) {
			for (int di = -NEAR_REACH; di <= NEAR_REACH; di++) {
				double w = Math.exp(-(di * di + dj * dj) / (2 * 1.2 * 1.2));
				weights[(dj + NEAR_REACH) * side + di + NEAR_REACH] = w;
				total += w;
			}
		}
		for (int k = 0; k < weights.length; k++) {
			weights[k] /= total;
		}
		return weights;
	}

	private static int texel(int u, int v) {
		return Math.floorMod(v, 256) * 256 + Math.floorMod(u, 256);
	}

	/** The texel of an image {@code size} across under texel {@code k} of 256: a bigger one shrunk, a smaller one tiled. */
	private static int sample(int k, int size) {
		return size >= 256 ? k * size / 256 + size / 512 : k % size;
	}

	/** Vanilla's clouds.png as cloud or not, 256 a side (a bigger one is shrunk to that, a smaller one tiled). */
	private static boolean[] texture() {
		boolean[] cells = new boolean[256 * 256];
		boolean read = false;
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(Identifier.withDefaultNamespace("textures/environment/clouds.png"));
			if (resource.isPresent()) {
				try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
					for (int y = 0; y < 256; y++) {
						for (int x = 0; x < 256; x++) {
							cells[y * 256 + x] = WaypointIcons.argb(image, sample(x, image.getWidth()), sample(y, image.getHeight())) >>> 24 > 0;
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
