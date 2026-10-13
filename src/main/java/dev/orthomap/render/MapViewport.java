package dev.orthomap.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.orthomap.Projection;
import dev.orthomap.bake.TileKey;
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

	private @Nullable GpuTexture maskTexture;
	private @Nullable GpuTextureView maskView;
	private final int[] maskPixels = new int[MASK * MASK];

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

	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		ScreenRectangle scissor = new ScreenRectangle(view.x(), view.y(), view.width(), view.height());
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		boolean cave = view.layer() != TileKey.SURFACE;
		graphics.fill(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), cave ? 0xFF0C0E12 : 0xFF8C99AB);

		this.drawTiles(graphics, level, view);
		this.drawFog(graphics, level, view, scissor);
		graphics.disableScissor();
	}

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		double units = Projection.TILE_UNITS;
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
				TileKey key = new TileKey(view.layer(), tu, tv);
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
			}
		}
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view, ScreenRectangle scissor) {
		double viewW = view.width() / view.zoom();
		double viewH = view.height() / view.zoom();
		double need = Math.max(viewW, viewH) + MASK_MARGIN * 2 + 64;
		int scaleCode = 0;
		double texel = 2;
		while (MASK * texel < need) {
			texel *= 2;
			scaleCode++;
		}
		double originU = Math.floor((view.minU() - MASK_MARGIN) / 64) * 64;
		double originV = Math.floor((view.minV() - MASK_MARGIN) / 64) * 64;
		this.buildMask(level, view, originU, originV, texel);

		double span = MASK * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / 64), 8);
		int noiseV = Math.floorMod((long) (originV / 64), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		int params = (scaleCode * 16) << 24 | (noiseU * 32) << 16 | (noiseV * 32) << 8 | (cave ? 255 : 0);

		TextureSetup textures = TextureSetup.doubleTexture(
			this.maskView,
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
			FogTextures.noise(),
			RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
		);
		graphics.guiRenderState.addGuiElement(QuadElement.rect(
			OrthomapPipelines.FOG,
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
			this.maskTexture = FogTextures.createTexture("orthomap fog mask", MASK);
			this.maskView = RenderSystem.getDevice().createTextureView(this.maskTexture);
		}
		double units = Projection.TILE_UNITS;
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
					tile = level.peek(new TileKey(view.layer(), tu, tv));
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
		FogTextures.write(this.maskTexture, this.maskPixels, MASK);
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
	}
}
