package dev.diorama.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a rectangle of the map: baked tiles, then fog over everything the map has no data for.
 * Each on-screen map (minimap, fullscreen) owns one, because each keeps its own fog mask.
 */
public final class MapViewport {
	private static final int MASK = 256;
	private static final double MASK_MARGIN = 24;
	/** Zooming out past this (GUI pixels per block) switches to overview tiles. */
	public static final double DETAILED_ZOOM = 0.5;

	private @Nullable GpuTexture maskTexture;
	private @Nullable GpuTextureView maskView;
	private final int[] maskPixels = new int[MASK * MASK];
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, int layer, int lod, double originU, double originV, double texel, boolean rounded) {
	}

	/** Where and how big a map is drawn, and what part of the map it shows. */
	public record View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer) {
		public double minU() {
			return this.centerU - this.width / 2.0 / this.zoom;
		}

		public double minV() {
			return this.centerV - this.height / 2.0 / this.zoom;
		}

		public float screenX(double u) {
			return (float) (this.x + this.width / 2.0 + (u - this.centerU) * this.zoom);
		}

		public float screenY(double v) {
			return (float) (this.y + this.height / 2.0 + (v - this.centerV) * this.zoom);
		}
	}

	/** Below this zoom (GUI pixels per block) overlay lines and dots would blur into noise. */
	private static final double OVERLAY_ZOOM = 1.0;

	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		this.draw(graphics, level, view, 0);
	}

	/** Same, with these {@link dev.diorama.bake.Overlays} bits drawn over the tiles. */
	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays) {
		ScreenRectangle scissor = new ScreenRectangle(view.x(), view.y(), view.width(), view.height());
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		boolean cave = view.layer() != TileKey.SURFACE;
		graphics.fill(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), cave ? 0xFF0C0E12 : 0xFF8C99AB);

		this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0);
		this.drawFog(graphics, level, view, scissor);
		graphics.disableScissor();
	}

	/**
	 * The overview level to draw: the coarsest one that still has at least one texture pixel per
	 * screen pixel. Overviews only exist for the saved surface map, and are only used below
	 * {@link #DETAILED_ZOOM}: above it the live tiles' own mipmaps look the same and never lag
	 * behind recent changes.
	 */
	public static int lodFor(MapLevel level, View view) {
		if (view.layer() != TileKey.SURFACE || !level.hasOverviews() || view.zoom() >= DETAILED_ZOOM) {
			return 0;
		}
		double screenPixelsPerUnit = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale();
		int lod = 0;
		while (lod < TileKey.MAX_LOD && Projection.PIXELS_PER_UNIT / (double) (2 << lod) >= screenPixelsPerUnit) {
			lod++;
		}
		return lod;
	}

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		double u1 = u0 + view.width() / view.zoom();
		double v1 = v0 + view.height() / view.zoom();
		int tu0 = (int) Math.floor(u0 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv1 = (int) Math.floor(v1 / units);
		// Minified tiles blend across mips; magnified ones stay crisp so blocks keep their edges.
		GpuSampler sampler = RenderSystem.getSamplerCache().getSampler(
			AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, view.zoom() >= Projection.PIXELS_PER_UNIT ? FilterMode.NEAREST : FilterMode.LINEAR, true
		);
		float size = (float) (units * view.zoom());
		for (int tu = tu0 - 1; tu <= tu1 + 1; tu++) {
			for (int tv = tv0 - 1; tv <= tv1 + 1; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, lod);
				double du = (tu + 0.5) * units - view.centerU();
				double dv = (tv + 0.5) * units - view.centerV();
				boolean visible = tu >= tu0 && tu <= tu1 && tv >= tv0 && tv <= tv1;
				// Tiles just outside the view bake too, so panning finds them ready.
				MapTile tile = level.want(key, Math.sqrt(du * du + dv * dv) + (visible ? 0 : 100_000));
				if (!visible || tile == null || tile.view() == null) {
					continue;
				}
				float sx = view.screenX(tu * units);
				float sy = view.screenY(tv * units);
				graphics.guiRenderState.addGuiElement(QuadElement.rect(
					RenderPipelines.GUI_TEXTURED,
					TextureSetup.singleTexture(tile.view(), sampler),
					new Matrix3x2f(graphics.pose()),
					sx, sy, sx + size, sy + size,
					0, 0, 1, 1,
					-1,
					graphics.scissorStack.peek()
				));
				GpuTextureView overlay = overlays == 0 || lod > 0 ? null : tile.overlay(overlays);
				if (overlay != null) {
					graphics.guiRenderState.addGuiElement(QuadElement.rect(
						RenderPipelines.GUI_TEXTURED,
						TextureSetup.singleTexture(overlay, sampler),
						new Matrix3x2f(graphics.pose()),
						sx, sy, sx + size, sy + size,
						0, 0, 1, 1,
						-1,
						graphics.scissorStack.peek()
					));
				}
			}
		}
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view, ScreenRectangle scissor) {
		double viewW = view.width() / view.zoom();
		double viewH = view.height() / view.zoom();
		// Zoomed out past the detailed zoom, the fog's noise grows with the view so it keeps its
		// size on screen instead of repeating every few pixels. The scale is 2^(steps + fraction);
		// the shader blends the noise of two whole steps by the fraction.
		double scaleLog = Math.min(7.99, Math.log(Math.max(1, DETAILED_ZOOM / view.zoom())) / Math.log(2));
		int steps = (int) Math.floor(scaleLog);
		int fraction = (int) Math.floor((scaleLog - steps) * 16);
		// The noise offset is in eighths of the coarser noise's period, so the origin snaps to that.
		double snap = 64.0 * (1 << (steps + 1));
		double need = Math.max(viewW, viewH) + MASK_MARGIN * 2 + snap;
		int scaleCode = 0;
		double texel = 2;
		while (MASK * texel < need) {
			texel *= 2;
			scaleCode++;
		}
		double originU = Math.floor((view.minU() - MASK_MARGIN) / snap) * snap;
		double originV = Math.floor((view.minV() - MASK_MARGIN) / snap) * snap;
		this.buildMask(level, view, originU, originV, texel);

		double span = MASK * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / snap), 8);
		int noiseV = Math.floorMod((long) (originV / snap), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		int fogCode = (cave ? 128 : 0) | steps << 4 | fraction;
		int params = (scaleCode * 16) << 24 | (noiseU * 32) << 16 | (noiseV * 32) << 8 | fogCode;

		TextureSetup textures = TextureSetup.doubleTexture(
			this.maskView,
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
			FogTextures.noise(),
			RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
		);
		graphics.guiRenderState.addGuiElement(QuadElement.rect(
			DioramaPipelines.FOG,
			textures,
			new Matrix3x2f(graphics.pose()),
			view.x(), view.y(), view.x() + view.width(), view.y() + view.height(),
			mu0, mv0, mu1, mv1,
			params,
			scissor
		));
	}

	private void buildMask(MapLevel level, View view, double originU, double originV, double texel) {
		if (this.maskTexture == null) {
			this.maskTexture = FogTextures.createTexture("diorama fog mask", MASK);
			this.maskView = RenderSystem.getDevice().createTextureView(this.maskTexture);
		}
		int lod = lodFor(level, view);
		// Caves keep the sharp mask: rounding would erode narrow explored tunnels away.
		// Once a texel spans a chunk there are no chunk steps left to smooth, and rounding would
		// only eat small explored areas that are a few texels across.
		boolean rounded = MapStyle.get().roundedFog() && view.layer() == TileKey.SURFACE && texel < 16;
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), view.layer(), lod, originU, originV, texel, rounded);
		if (this.maskLevel.get() == level && inputs.equals(this.maskInputs)) {
			return;
		}
		this.maskInputs = inputs;
		this.maskLevel = new WeakReference<>(level);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		for (int j = 0; j < MASK; j++) {
			double v = originV + (j + 0.5) * texel;
			int tv = (int) Math.floor(v / units);
			int cellY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cellUnits));
			for (int i = 0; i < MASK; i++) {
				double u = originU + (i + 0.5) * texel;
				int tu = (int) Math.floor(u / units);
				if (tu != lastTu || tv != lastTv) {
					tile = level.peek(new TileKey(view.layer(), tu, tv, lod));
					lastTu = tu;
					lastTv = tv;
				}
				float known = 0;
				if (tile != null && tile.hasCoverage()) {
					int cellX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cellUnits));
					known = tile.coverage(cellX, cellY);
				}
				this.maskPixels[j * MASK + i] = 0xFF000000 | Math.round(known * 255);
			}
		}
		if (rounded) {
			this.roundOff(Math.max(1, (int) Math.round(10 / texel)));
		}
		FogTextures.write(this.maskTexture, this.maskPixels, MASK);
	}

	private final float[] blurA = new float[MASK * MASK];
	private final float[] blurB = new float[MASK * MASK];

	/**
	 * Rounds the known area off so the fog edge follows a smooth line instead of chunk steps.
	 * Blurs the mask, keeps only what stays solidly known, and takes the smaller of that and the
	 * original, so the fog only ever grows over the edge and never uncovers ground with no data.
	 */
	private void roundOff(int radius) {
		for (int i = 0; i < this.maskPixels.length; i++) {
			this.blurA[i] = (this.maskPixels[i] & 0xFF) / 255.0F;
		}
		boxBlur(this.blurA, this.blurB, radius, true);
		boxBlur(this.blurB, this.blurA, radius, false);
		boxBlur(this.blurA, this.blurB, radius, true);
		boxBlur(this.blurB, this.blurA, radius, false);
		for (int i = 0; i < this.maskPixels.length; i++) {
			float t = Math.max(0, Math.min(1, (this.blurA[i] - 0.5F) / 0.35F));
			float rounded = t * t * (3 - 2 * t);
			int known = Math.min(this.maskPixels[i] & 0xFF, Math.round(rounded * 255));
			this.maskPixels[i] = 0xFF000000 | known;
		}
	}

	private static void boxBlur(float[] from, float[] to, int radius, boolean horizontal) {
		float scale = 1.0F / (2 * radius + 1);
		for (int line = 0; line < MASK; line++) {
			float sum = 0;
			for (int k = -radius; k <= radius; k++) {
				sum += from[index(line, Math.max(0, Math.min(MASK - 1, k)), horizontal)];
			}
			for (int k = 0; k < MASK; k++) {
				to[index(line, k, horizontal)] = sum * scale;
				int add = Math.min(MASK - 1, k + radius + 1);
				int drop = Math.max(0, k - radius);
				sum += from[index(line, add, horizontal)] - from[index(line, drop, horizontal)];
			}
		}
	}

	private static int index(int line, int k, boolean horizontal) {
		return horizontal ? line * MASK + k : k * MASK + line;
	}

	public void close() {
		if (this.maskView != null) {
			this.maskView.close();
			this.maskView = null;
		}
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		this.maskInputs = null;
	}
}
