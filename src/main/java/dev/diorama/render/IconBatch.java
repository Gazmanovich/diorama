package dev.diorama.render;

import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
//? if >=1.21.6 {
import org.joml.Vector2f;
//?} else {
/*import org.joml.Matrix4f;
import org.joml.Vector3f;
*///?}

/**
 * Where the radar draws its icons: fills, game texture blits and the mod's own textures, each in
 * the GUI's current pose. A direct batch draws each one straight away. A batched one collects a
 * crowd of icons and draws them in a few draw calls instead of a handful per icon, which a mob farm
 * on the map turns into thousands a frame.
 *
 * <p>Batching keeps the picture: icons that overlap still draw in their order, and each icon's own
 * pieces still draw in theirs. Icons are put in layers, each one layer above the highest earlier
 * icon it overlaps, so no two icons in a layer overlap. Within a layer, the icons' pieces draw in
 * runs (an icon's first run of pieces of one texture, then its second, and so on), and pieces of
 * the same run and texture from different icons draw together, which only reorders pieces that
 * don't overlap.
 */
public final class IconBatch {
	/**
	 * The grid that finds overlaps, in GUI pixels per cell, and about the most cells it has; icons
	 * spread over a big screen get bigger cells. Coarser is cheaper and only adds layers.
	 */
	private static final float CELL = 2;
	private static final int MAX_CELLS = 1 << 16;
	/** The grid's storage, kept between frames. Render thread only. */
	private static int[] grid = new int[0];

	private final GuiGraphicsExtractor graphics;
	private final boolean batched;
	// Per quad: its four corners (x y z, already in GUI coordinates), UVs, colour, texture and run.
	private float[] xyz = new float[0];
	private float[] uvs = new float[0];
	private int[] colors = new int[0];
	private int[] textureIds = new int[0];
	private int[] runs = new int[0];
	private int quads;
	/** Index 0 is "no texture"; each texture is an Identifier or a MapTexture with its sampling. */
	private final List<Object> textures = new ArrayList<>();
	private final Map<Object, Integer> textureIndex = new HashMap<>();
	/** The first quad of each finished icon, then the end of the last one. */
	private final List<Integer> iconStarts = new ArrayList<>(List.of(0));
	private int run;
	private int lastTexture = -1;

	private record SampledTexture(MapTexture texture, Sampling sampling) {
	}

	private IconBatch(GuiGraphicsExtractor graphics, boolean batched) {
		this.graphics = graphics;
		this.batched = batched;
		this.textures.add(null);
	}

	/** Draws everything as it comes. */
	public static IconBatch direct(GuiGraphicsExtractor graphics) {
		return new IconBatch(graphics, false);
	}

	/** Collects icons until {@link #draw()}. */
	public static IconBatch batched(GuiGraphicsExtractor graphics) {
		return new IconBatch(graphics, true);
	}

	public GuiGraphicsExtractor graphics() {
		return this.graphics;
	}

	/** {@link GuiGraphicsExtractor#fill}. */
	public void fill(int x0, int y0, int x1, int y1, int argb) {
		if (!this.batched) {
			this.graphics.fill(x0, y0, x1, y1, argb);
			return;
		}
		this.add(0, x0, y0, x1, y1, 0, 0, 0, 0, argb);
	}

	/** {@link MapCanvas#blit}: a {@code w} x {@code h} rectangle of a game texture at (u, v), drawn at (x, y) the same size. */
	public void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		if (!this.batched) {
			MapCanvas.of(this.graphics).blit(texture, x, y, u, v, w, h, texW, texH, argb);
			return;
		}
		this.add(this.textureId(texture), x, y, x + w, y + h, (float) u / texW, (float) v / texH, (float) (u + w) / texW, (float) (v + h) / texH, argb);
	}

	/** {@link MapCanvas#rect}: an axis-aligned rectangle of one of the mod's textures. */
	public void rect(MapTexture texture, Sampling sampling, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		if (!this.batched) {
			MapCanvas.of(this.graphics).rect(texture, sampling, x0, y0, x1, y1, u0, v0, u1, v1, argb);
			return;
		}
		this.add(this.textureId(new SampledTexture(texture, sampling)), x0, y0, x1, y1, u0, v0, u1, v1, argb);
	}

	/** Ends an icon: everything since the last call is one icon. */
	public void endIcon() {
		if (this.batched && this.quads > this.iconStarts.getLast()) {
			this.iconStarts.add(this.quads);
		}
		this.run = 0;
		this.lastTexture = -1;
	}

	private int textureId(Object texture) {
		return this.textureIndex.computeIfAbsent(texture, key -> {
			this.textures.add(key);
			return this.textures.size() - 1;
		});
	}

	// The corners in the order MapCanvas.rect gives them, through the current pose the way the GUI's
	// own fills and blits put them through it.
	private void add(int texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		if (this.quads == this.colors.length) {
			int n = Math.max(64, this.quads * 2);
			this.xyz = Arrays.copyOf(this.xyz, n * 12);
			this.uvs = Arrays.copyOf(this.uvs, n * 8);
			this.colors = Arrays.copyOf(this.colors, n);
			this.textureIds = Arrays.copyOf(this.textureIds, n);
			this.runs = Arrays.copyOf(this.runs, n);
		}
		if (this.lastTexture >= 0 && texture != this.lastTexture) {
			this.run++;
		}
		this.lastTexture = texture;
		int q = this.quads++;
		this.colors[q] = argb;
		this.textureIds[q] = texture;
		this.runs[q] = this.run;
		this.corner(q, 0, x0, y0, u0, v0);
		this.corner(q, 1, x0, y1, u0, v1);
		this.corner(q, 2, x1, y1, u1, v1);
		this.corner(q, 3, x1, y0, u1, v0);
	}

	private void corner(int q, int i, float x, float y, float u, float v) {
		int p = (q * 4 + i) * 3;
		//? if >=1.21.6 {
		Vector2f at = this.graphics.pose().transformPosition(x, y, new Vector2f());
		this.xyz[p] = at.x;
		this.xyz[p + 1] = at.y;
		//?} else {
		/*Matrix4f pose = this.graphics.pose().last().pose();
		Vector3f at = pose.transformPosition(x, y, 0, new Vector3f());
		this.xyz[p] = at.x;
		this.xyz[p + 1] = at.y;
		this.xyz[p + 2] = at.z;
		*///?}
		int t = (q * 4 + i) * 2;
		this.uvs[t] = u;
		this.uvs[t + 1] = v;
	}

	/** Draws what was collected, in as few draws as the layers and textures allow. Call it inside the same scissor as the icons. */
	public void draw() {
		this.endIcon();
		int icons = this.iconStarts.size() - 1;
		if (!this.batched || icons == 0) {
			return;
		}
		int[] layers = this.layers(icons);
		int maxLayer = Arrays.stream(layers).max().orElse(0);
		int maxRun = Arrays.stream(this.runs, 0, this.quads).max().orElse(0);
		// Sort the quads by layer, run and texture, keeping their order otherwise, as one long each:
		// layer, run, texture and quad in 15, 8, 16 and 24 bits. A crowd too big for that (tens of
		// thousands of icons stacked on one spot) draws in its own order, a draw per texture change.
		boolean sorted = maxLayer <= 0x7FFF && maxRun <= 0xFF && this.textures.size() <= 0x10000 && this.quads <= 0x1000000;
		long[] order = new long[this.quads];
		for (int icon = 0; icon < icons; icon++) {
			for (int q = this.iconStarts.get(icon); q < this.iconStarts.get(icon + 1); q++) {
				order[q] = sorted ? (long) layers[icon] << 48 | (long) this.runs[q] << 40 | (long) this.textureIds[q] << 24 | q : q;
			}
		}
		if (sorted) {
			Arrays.sort(order);
		}
		MapCanvas canvas = MapCanvas.of(this.graphics);
		float[] groupXyz = new float[this.quads * 12];
		float[] groupUvs = new float[this.quads * 8];
		int[] groupColors = new int[this.quads];
		int start = 0;
		while (start < order.length) {
			int texture0 = this.textureIds[quad(order[start], sorted)];
			int count = 0;
			for (int i = start; i < order.length && (sorted ? order[i] >>> 24 == order[start] >>> 24 : this.textureIds[(int) order[i]] == texture0); i++) {
				int q = quad(order[i], sorted);
				System.arraycopy(this.xyz, q * 12, groupXyz, count * 12, 12);
				System.arraycopy(this.uvs, q * 8, groupUvs, count * 8, 8);
				groupColors[count++] = this.colors[q];
			}
			Object texture = this.textures.get(texture0);
			if (texture == null) {
				canvas.solids(groupXyz, groupColors, count);
			} else if (texture instanceof Identifier id) {
				canvas.blits(id, groupXyz, groupUvs, groupColors, count);
			} else {
				SampledTexture sampled = (SampledTexture) texture;
				canvas.rects(sampled.texture(), sampled.sampling(), groupXyz, groupUvs, groupColors, count);
			}
			start += count;
		}
	}

	private static int quad(long key, boolean sorted) {
		return sorted ? (int) (key & 0xFFFFFF) : (int) key;
	}

	/** Each icon's layer: one above the highest earlier icon it overlaps, found on a grid over the icons' bounds. */
	private int[] layers(int icons) {
		float[] bounds = new float[icons * 4];
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int icon = 0; icon < icons; icon++) {
			float x0 = Float.MAX_VALUE;
			float y0 = Float.MAX_VALUE;
			float x1 = -Float.MAX_VALUE;
			float y1 = -Float.MAX_VALUE;
			for (int p = this.iconStarts.get(icon) * 4; p < this.iconStarts.get(icon + 1) * 4; p++) {
				x0 = Math.min(x0, this.xyz[p * 3]);
				x1 = Math.max(x1, this.xyz[p * 3]);
				y0 = Math.min(y0, this.xyz[p * 3 + 1]);
				y1 = Math.max(y1, this.xyz[p * 3 + 1]);
			}
			bounds[icon * 4] = x0;
			bounds[icon * 4 + 1] = y0;
			bounds[icon * 4 + 2] = x1;
			bounds[icon * 4 + 3] = y1;
			minX = Math.min(minX, x0);
			minY = Math.min(minY, y0);
			maxX = Math.max(maxX, x1);
			maxY = Math.max(maxY, y1);
		}
		float cell = Math.max(CELL, (float) Math.sqrt((maxX - minX) * (maxY - minY) / MAX_CELLS));
		int columns = (int) ((maxX - minX) / cell) + 1;
		int rows = (int) ((maxY - minY) / cell) + 1;
		if (grid.length < columns * rows) {
			grid = new int[columns * rows];
		} else {
			Arrays.fill(grid, 0, columns * rows, 0);
		}
		// The layer above the highest icon touching each cell so far, so 0 where none has.
		int[] above = grid;
		int[] layers = new int[icons];
		for (int icon = 0; icon < icons; icon++) {
			int c0 = (int) ((bounds[icon * 4] - minX) / cell);
			int r0 = (int) ((bounds[icon * 4 + 1] - minY) / cell);
			int c1 = (int) ((bounds[icon * 4 + 2] - minX) / cell);
			int r1 = (int) ((bounds[icon * 4 + 3] - minY) / cell);
			int layer = 0;
			for (int r = r0; r <= r1; r++) {
				for (int c = c0; c <= c1; c++) {
					layer = Math.max(layer, above[r * columns + c]);
				}
			}
			layers[icon] = layer;
			for (int r = r0; r <= r1; r++) {
				Arrays.fill(above, r * columns + c0, r * columns + c1 + 1, layer + 1);
			}
		}
		return layers;
	}
}
