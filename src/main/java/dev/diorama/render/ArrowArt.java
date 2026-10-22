package dev.diorama.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The player arrow as a little voxel model: a dart shape built from one-unit blocks on the world
 * grid, seen through the map's own camera and lit by its sun, with its shadow on the ground and a
 * dark rim. Pure pixels at four texels per GUI pixel, so it stays sharp at GUI scale 4 and below.
 */
public final class ArrowArt {
	/** GUI pixels across the drawing; the arrow is centred on the player's spot in the middle. */
	public static final int GUI_SIZE = 36;
	/** Texels per GUI pixel in the result. */
	public static final int SCALE = 4;
	/** Supersampled pixels per GUI pixel while drawing. */
	private static final int SS = 16;
	private static final int WHITE = 0xFFFFFF;
	private static final int LIT_SIDE = 0xE8EEF4;
	private static final int DARK = 0x101418;
	/** The map's sun: from the west and a little south, 38 degrees up (the same light the tiles are baked with). */
	private static final double[] SUN = normalise(-0.878 * Math.cos(Math.toRadians(38)), Math.sin(Math.toRadians(38)), 0.479 * Math.cos(Math.toRadians(38)));

	private ArrowArt() {
	}

	/**
	 * The arrow facing {@code yawDegrees} (Minecraft's yaw: 0 south, 90 west), {@code size} GUI
	 * pixels from the centre to the tip of today's flat arrow, under a camera tilted so north-south
	 * distances shrink by {@code sin} and heights by {@code cos}.
	 */
	public static PinArt.Image voxel(double yawDegrees, float size, double sin, double cos, int accent) {
		// The sides in shade take a darker accent, as the blue arrow's did.
		int shade = PinArt.mix(accent, 0x000000, 0.33F);
		float s = size * 1.15F;
		float[] outer = turn(dart(s), yawDegrees);
		float[] inner = turn(dart(s * 0.5F), yawDegrees);
		int range = (int) (size * 1.3F) + 2;
		List<int[]> cells = new ArrayList<>();
		for (int i = -range; i <= range; i++) {
			for (int j = -range; j <= range; j++) {
				if (PinArt.inside(outer, i + 0.5F, j + 0.5F)) {
					cells.add(new int[] {i, j, PinArt.inside(inner, i + 0.5F, j + 0.5F) ? 1 : 0});
				}
			}
		}
		// Centre the drawn blocks on the canvas, so the arrow sits on the player's spot at every heading.
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		List<double[]> corners = new ArrayList<>();
		for (int[] c : cells) {
			for (int k = 0; k < 4; k++) {
				double x = c[0] + (k == 1 || k == 2 ? 1 : 0);
				double z = c[1] + (k >= 2 ? 1 : 0);
				for (int y = 0; y <= 1; y++) {
					corners.add(new double[] {x, y, z});
					double sx = x;
					double sy = z * sin - y * cos;
					minX = Math.min(minX, sx);
					maxX = Math.max(maxX, sx);
					minY = Math.min(minY, sy);
					maxY = Math.max(maxY, sy);
				}
			}
		}
		Canvas canvas = new Canvas(GUI_SIZE / 2.0 - (minX + maxX) / 2, GUI_SIZE / 2.0 - (minY + maxY) / 2, sin);
		// The shadow: every corner pushed down to the ground along the sunlight, then their outline.
		List<double[]> flat = new ArrayList<>();
		for (double[] p : corners) {
			double t = p[1] / SUN[1];
			flat.add(new double[] {p[0] - SUN[0] * t, p[2] - SUN[2] * t});
		}
		List<double[]> hull = hull(flat);
		double[] shadow = new double[hull.size() * 2];
		for (int i = 0; i < hull.size(); i++) {
			shadow[i * 2] = hull.get(i)[0];
			shadow[i * 2 + 1] = hull.get(i)[1] * sin;
		}
		canvas.cast(shadow);
		cells.sort(Comparator.comparingInt(c -> c[1]));
		for (int[] c : cells) {
			prism(canvas, c[0], c[1], c[2] == 1 ? accent : WHITE, sin, cos, shade);
		}
		return canvas.result(0.28F, 1.0F);
	}

	/** The arrow in the ground plane pointing +x: tip, right wing, notch, left wing. Its tip reaches well past its wings, so it never reads sideways. */
	private static float[] dart(float s) {
		return new float[] {1.3F * s, 0, -0.7F * s, 0.55F * s, -0.3F * s, 0, -0.7F * s, -0.55F * s};
	}

	/** Turns ground points (forward = +x) to face Minecraft yaw, as (x, z) pairs. */
	private static float[] turn(float[] pts, double yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double px = -fz;
		double pz = fx;
		float[] out = new float[pts.length];
		for (int i = 0; i < pts.length; i += 2) {
			out[i] = (float) (fx * pts[i] + px * pts[i + 1]);
			out[i + 1] = (float) (fz * pts[i] + pz * pts[i + 1]);
		}
		return out;
	}

	/** One block: its top and the sides the camera sees, far ones first. */
	private static void prism(Canvas canvas, int i, int j, int top, double sin, double cos, int shade) {
		double[][] base = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
		List<Object[]> faces = new ArrayList<>();
		faces.add(new Object[] {new double[][] {{i, 1, j}, {i + 1, 1, j}, {i + 1, 1, j + 1}, {i, 1, j + 1}}, top, new double[] {0, 1, 0}});
		double area = 0;
		for (int k = 0; k < 4; k++) {
			area += base[k][0] * base[(k + 1) % 4][1] - base[(k + 1) % 4][0] * base[k][1];
		}
		int sign = area > 0 ? 1 : -1;
		for (int k = 0; k < 4; k++) {
			double x0 = base[k][0], z0 = base[k][1], x1 = base[(k + 1) % 4][0], z1 = base[(k + 1) % 4][1];
			double[] n = {sign * (z1 - z0), 0, -sign * (x1 - x0)};
			faces.add(new Object[] {new double[][] {{x0, 0, z0}, {x1, 0, z1}, {x1, 1, z1}, {x0, 1, z0}}, material(n, shade), n});
		}
		List<Object[]> shown = new ArrayList<>();
		for (Object[] f : faces) {
			double[] n = (double[]) f[2];
			// The camera looks along (0, -sin, -cos): a face shows when its normal points back up it.
			if (n[1] * sin + n[2] * cos > 1e-9) {
				shown.add(f);
			}
		}
		shown.sort(Comparator.comparingDouble(f -> {
			double depth = 0;
			for (double[] p : (double[][]) f[0]) {
				depth += -p[1] * sin - p[2] * cos;
			}
			return -depth;
		}));
		for (Object[] f : shown) {
			double[][] pts = (double[][]) f[0];
			double[] poly = new double[pts.length * 2];
			for (int k = 0; k < pts.length; k++) {
				poly[k * 2] = pts[k][0];
				poly[k * 2 + 1] = pts[k][2] * sin - pts[k][1] * cos;
			}
			canvas.poly(poly, (int) f[1]);
		}
	}

	/** Warm light on faces toward the sun, the cool shade colour away from it, like the map's grade. */
	private static int material(double[] n, int shade) {
		double[] u = normalise(n[0], n[1], n[2]);
		double lit = Math.max(0, u[0] * SUN[0] + u[1] * SUN[1] + u[2] * SUN[2]) / SUN[1];
		return PinArt.mix(shade, LIT_SIDE, (float) Math.min(1, lit));
	}

	private static double[] normalise(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		return new double[] {x / l, y / l, z / l};
	}

	private static List<double[]> hull(List<double[]> points) {
		List<double[]> pts = new ArrayList<>(points);
		pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
		List<double[]> lower = new ArrayList<>();
		for (double[] p : pts) {
			while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
				lower.remove(lower.size() - 1);
			}
			lower.add(p);
		}
		List<double[]> upper = new ArrayList<>();
		for (int i = pts.size() - 1; i >= 0; i--) {
			double[] p = pts.get(i);
			while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
				upper.remove(upper.size() - 1);
			}
			upper.add(p);
		}
		lower.remove(lower.size() - 1);
		upper.remove(upper.size() - 1);
		lower.addAll(upper);
		return lower;
	}

	private static double cross(double[] o, double[] a, double[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	/** Supersampled drawing in GUI pixels; the shape and its ground shadow are kept apart. */
	private static final class Canvas {
		private final int size = GUI_SIZE * SS;
		private final float[] r = new float[this.size * this.size];
		private final float[] g = new float[this.size * this.size];
		private final float[] b = new float[this.size * this.size];
		private final boolean[] covered = new boolean[this.size * this.size];
		private final boolean[] shadow = new boolean[this.size * this.size];
		private final double ox;
		private final double oy;

		Canvas(double ox, double oy, double sin) {
			this.ox = ox;
			this.oy = oy;
		}

		void poly(double[] pts, int rgb) {
			this.fill(pts, (i) -> {
				this.r[i] = (rgb >> 16) & 0xFF;
				this.g[i] = (rgb >> 8) & 0xFF;
				this.b[i] = rgb & 0xFF;
				this.covered[i] = true;
			});
		}

		void cast(double[] pts) {
			this.fill(pts, (i) -> this.shadow[i] = true);
		}

		private void fill(double[] pts, java.util.function.IntConsumer pixel) {
			float[] p = new float[pts.length];
			float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			for (int k = 0; k < pts.length; k += 2) {
				p[k] = (float) ((pts[k] + this.ox) * SS);
				p[k + 1] = (float) ((pts[k + 1] + this.oy) * SS);
				minX = Math.min(minX, p[k]);
				maxX = Math.max(maxX, p[k]);
				minY = Math.min(minY, p[k + 1]);
				maxY = Math.max(maxY, p[k + 1]);
			}
			int x0 = Math.max(0, (int) Math.floor(minX));
			int x1 = Math.min(this.size - 1, (int) Math.ceil(maxX));
			int y0 = Math.max(0, (int) Math.floor(minY));
			int y1 = Math.min(this.size - 1, (int) Math.ceil(maxY));
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					if (PinArt.inside(p, x + 0.5F, y + 0.5F)) {
						pixel.accept(y * this.size + x);
					}
				}
			}
		}

		/** Down to {@link #SCALE} texels per GUI pixel: the ground shadow, then a dark rim round the shape, then the shape. */
		PinArt.Image result(float shadowAlpha, float rim) {
			int w = GUI_SIZE * SCALE;
			int f = SS / SCALE;
			float[] sa = new float[w * w];
			float[] sr = new float[w * w];
			float[] sg = new float[w * w];
			float[] sb = new float[w * w];
			float[] sh = new float[w * w];
			for (int y = 0; y < w; y++) {
				for (int x = 0; x < w; x++) {
					float cov = 0, rr = 0, gg = 0, bb = 0, shade = 0;
					for (int dy = 0; dy < f; dy++) {
						for (int dx = 0; dx < f; dx++) {
							int i = (y * f + dy) * this.size + x * f + dx;
							if (this.covered[i]) {
								cov++;
								rr += this.r[i];
								gg += this.g[i];
								bb += this.b[i];
							}
							if (this.shadow[i]) {
								shade++;
							}
						}
					}
					int i = y * w + x;
					sa[i] = cov / (f * f);
					if (cov > 0) {
						sr[i] = rr / cov;
						sg[i] = gg / cov;
						sb[i] = bb / cov;
					}
					sh[i] = shade / (f * f) * shadowAlpha;
				}
			}
			float reach = rim * SCALE;
			int span = (int) reach + 1;
			// The rim's reach as offsets and how much each one counts, worked out once.
			int[] kx = new int[(span * 2 + 1) * (span * 2 + 1)];
			int[] ky = new int[kx.length];
			float[] kw = new float[kx.length];
			int kn = 0;
			for (int dy = -span; dy <= span; dy++) {
				for (int dx = -span; dx <= span; dx++) {
					double d = Math.hypot(dx, dy);
					if (d <= reach + 0.5) {
						kx[kn] = dx;
						ky[kn] = dy;
						kw[kn] = (float) Math.min(1, reach + 0.5 - d);
						kn++;
					}
				}
			}
			PinArt.Image out = new PinArt.Image(w, w);
			for (int y = 0; y < w; y++) {
				for (int x = 0; x < w; x++) {
					float grown = 0;
					for (int k = 0; k < kn; k++) {
						int xx = x - kx[k];
						int yy = y - ky[k];
						if (xx >= 0 && yy >= 0 && xx < w && yy < w) {
							float v = sa[yy * w + xx] * kw[k];
							if (v > grown) {
								grown = v;
							}
						}
					}
					int i = y * w + x;
					// shadow, then the rim over it, then the shape over both
					float a = sh[i];
					float cr = 0, cg = 0, cb = 0;
					float ringAlpha = grown * 0.9F;
					float na = ringAlpha + a * (1 - ringAlpha);
					if (na > 0) {
						cr = (((DARK >> 16) & 0xFF) * ringAlpha + cr * a * (1 - ringAlpha)) / na;
						cg = (((DARK >> 8) & 0xFF) * ringAlpha + cg * a * (1 - ringAlpha)) / na;
						cb = ((DARK & 0xFF) * ringAlpha + cb * a * (1 - ringAlpha)) / na;
					}
					a = na;
					float top = sa[i];
					na = top + a * (1 - top);
					if (na > 0) {
						cr = (sr[i] * top + cr * a * (1 - top)) / na;
						cg = (sg[i] * top + cg * a * (1 - top)) / na;
						cb = (sb[i] * top + cb * a * (1 - top)) / na;
					}
					out.r[i] = cr;
					out.g[i] = cg;
					out.b[i] = cb;
					out.a[i] = na * 255;
				}
			}
			return out;
		}
	}
}
