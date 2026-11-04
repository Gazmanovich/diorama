package dev.diorama.render;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

	private @Nullable MapTexture maskTexture;
	private final int[] maskPixels = new int[MASK * MASK];
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, long memoryVersion, int layer, int lod, int memoryLod, double originU, double originV, double texel, boolean rounded, int scaleSteps, int cellScale, boolean fast, int nearU, int nearV) {
	}

	/**
	 * Where and how big a map is drawn, and what part of the map it shows. A {@code round} view
	 * shows only the circle filling it (the round minimap); a {@code follows} view is on the player,
	 * so what's around its middle has to stay up to date (the minimap again).
	 */
	public record View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer, boolean round, boolean follows) {
		public View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer) {
			this(x, y, width, height, centerU, centerV, zoom, layer, false, false);
		}

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

	/** Screen pixels per tile pixel from which the tiles are drawn crisp; see {@link #sampling}. */
	private static final double CRISP_FROM = 1.5;

	/** Below this zoom (GUI pixels per block) overlay lines and dots would blur into noise. */
	private static final double OVERLAY_ZOOM = 1.0;

	/**
	 * How many tiles each way from the middle a {@link View#follows following} view draws detailed
	 * tiles over its overviews. Overviews are made from saved tiles, seconds behind, so the ground
	 * under a moving player would still be fog. This is as far as the game usually has chunks.
	 */
	private static final int NEAR_TILES = 2;
	private static final int NEAR_TILE_COUNT = (2 * NEAR_TILES + 1) * (2 * NEAR_TILES + 1);

	/** The most the fog shader's opacity code can say: it has five spare bits. */
	private static final int OPACITY_CODES = 31;

	/** How opaque the map draws, 0 to 1. */
	private float opacity = 1;

	/**
	 * Sets how opaque this map is drawn: the tiles and the fog, and not the backdrop behind them, so
	 * the game shows through. The fullscreen map leaves it at 1; the minimap takes it from the settings.
	 */
	public void opacity(float opacity) {
		this.opacity = Math.max(0, Math.min(1, opacity));
	}

	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		this.draw(graphics, level, view, 0, false, null);
	}

	/**
	 * Same, with these {@link dev.diorama.bake.Overlays} bits drawn over the tiles, land claims if
	 * {@code claims}, and {@code lit} drawn over the tiles but under those and the fog.
	 */
	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		boolean cave = view.layer() != TileKey.SURFACE;
		// Behind the tiles, a colour from the fog that covers where there's no data, so the ragged
		// edge of known ground blends into it: clouds, smoke under a roof, or the dark of caves.
		int background = cave ? 0xFF0C0E12 : level.roofless() ? 0xFF29211F : 0xFF8C99AB;
		// A see-through map has no backdrop: drawn under tiles that are themselves see-through, it
		// would show through them and tint the game behind.
		if (this.opacity < 1) {
			// Nothing to draw.
		} else if (view.round()) {
			MapCanvas.of(graphics).solid(Circle.in(view).disc(), background);
		} else {
			graphics.fill(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), background);
		}

		MapMemory memory = cave ? null : level.memory();
		if (memory != null) {
			memory.draw(MapCanvas.of(graphics), view, Math.round(this.opacity * 255) << 24 | 0xFFFFFF, lodFor(level, view), tileSampling(sampling(view)));
		}
		this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0, claims, lit);
		this.drawFog(graphics, level, view);
		graphics.disableScissor();
	}

	/**
	 * The overview level to draw: the coarsest one that still has at least one texture pixel per
	 * screen pixel, or one finer where that many tiles fit in memory, as a tile looks sharper shrunk
	 * further. Overviews only exist for the saved surface map, and are only used below
	 * {@link #DETAILED_ZOOM}: above it the live tiles' own mipmaps look the same and never lag
	 * behind recent changes. Stepping one finer, the full map can reach the live tiles a little below
	 * it too.
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
		// A following view stays on overviews: detailed tiles all over a minimap would bake again as
		// the player moves, and the middle, which needs them, gets them from drawNear.
		boolean finer = lod > 1 || lod == 1 && !view.follows();
		return finer && mostTilesFor(view, lod - 1) <= MapLevel.KEPT_TILES - NEAR_TILE_COUNT ? lod - 1 : lod;
	}

	/**
	 * The most tiles of an overview level a view of this size asks for, wherever it pans: those it
	 * shows, up to one more each way than it spans, and a tile all round, as {@link #drawTiles} asks.
	 */
	private static double mostTilesFor(View view, int lod) {
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		return (Math.ceil(view.width() / view.zoom() / units) + 3) * (Math.ceil(view.height() / view.zoom() / units) + 3);
	}

	/**
	 * How this view samples the tiles and anything drawn on their pixel grid. Shown larger than
	 * their pixels, they stay crisp so blocks keep their edges; magnified by too little for that,
	 * crisp pixels would come out uneven, one screen pixel wide here and two there, so they blend.
	 * Measured in screen pixels, as a GUI pixel is several on a high-density display; a whole GUI
	 * pixel per tile pixel or more is always crisp.
	 */
	public static Sampling sampling(View view) {
		double screenPixelsPerPixel = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale() / Projection.PIXELS_PER_UNIT;
		return view.zoom() >= Projection.PIXELS_PER_UNIT || screenPixelsPerPixel >= CRISP_FROM ? Sampling.TILE_CRISP : Sampling.TILE_SMOOTH;
	}

	/** How a tile's own pixels are sampled; what's drawn over them has no mip levels for the tile shader. */
	static Sampling tileSampling(Sampling sampling) {
		return sampling == Sampling.TILE_SMOOTH ? Sampling.TILE_AREA : sampling;
	}

	/** Whether every tile this view shows has baked at least once. Asks for the ones that haven't. */
	public static boolean ready(MapLevel level, View view) {
		boolean ready = true;
		for (TileKey key : tilesIn(level, view)) {
			ready &= level.want(key, distance(key, view)) != null;
		}
		return ready;
	}

	/** Whether any tile this view shows is baked and has something on it. Asks for nothing. */
	public static boolean showsAnything(MapLevel level, View view) {
		for (TileKey key : tilesIn(level, view)) {
			MapTile tile = level.peek(key);
			if (tile != null && !tile.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static List<TileKey> tilesIn(MapLevel level, View view) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		int tu1 = (int) Math.floor((u0 + view.width() / view.zoom()) / units);
		int tv1 = (int) Math.floor((v0 + view.height() / view.zoom()) / units);
		List<TileKey> keys = new ArrayList<>();
		for (int tu = (int) Math.floor(u0 / units); tu <= tu1; tu++) {
			for (int tv = (int) Math.floor(v0 / units); tv <= tv1; tv++) {
				keys.add(new TileKey(view.layer(), tu, tv, lod));
			}
		}
		return keys;
	}

	private static double distance(TileKey key, View view) {
		double units = (double) Projection.TILE_UNITS * (1 << key.lod());
		double du = (key.tileU() + 0.5) * units - view.centerU();
		double dv = (key.tileV() + 0.5) * units - view.centerV();
		return Math.sqrt(du * du + dv * dv);
	}

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit) {
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
		Sampling sampling = sampling(view);
		Sampling tiles = tileSampling(sampling);
		MapCanvas canvas = MapCanvas.of(graphics);
		float size = (float) (units * view.zoom());
		Circle circle = view.round() ? Circle.in(view) : null;
		// White, as see-through as the map is: the tiles are multiplied by it.
		int tint = Math.round(this.opacity * 255) << 24 | 0xFFFFFF;
		// Claims and overlays go over anything lit, so a light doesn't hide them.
		List<Runnable> above = new ArrayList<>();
		for (int tu = tu0 - 1; tu <= tu1 + 1; tu++) {
			for (int tv = tv0 - 1; tv <= tv1 + 1; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, lod);
				double du = (tu + 0.5) * units - view.centerU();
				double dv = (tv + 0.5) * units - view.centerV();
				boolean visible = tu >= tu0 && tu <= tu1 && tv >= tv0 && tv <= tv1;
				// Tiles just outside the view bake too, so panning finds them ready.
				MapTile tile = level.want(key, Math.sqrt(du * du + dv * dv) + (visible ? 0 : MapLevel.OUTSIDE_VIEW));
				float sx = view.screenX(tu * units);
				float sy = view.screenY(tv * units);
				if (visible && (tile == null || tile.texture() == null)) {
					// Not loaded yet, as after zooming to another level: show what's loaded of the same ground.
					drawStandIn(level, key, canvas, tiles, circle, sx, sy, size, tint);
					continue;
				}
				if (!visible || tile == null || tile.texture() == null) {
					continue;
				}
				MapTexture overlay = overlays == 0 || lod > 0 ? null : tile.overlay(overlays);
				MapTexture claimed = claims ? level.claims(key, tile) : null;
				if (circle != null) {
					Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, 0, 0, 1, 1);
					if (inside != null) {
						canvas.quad(tile.texture(), tiles, inside.corners(), inside.uvs(), tint);
						if (claimed != null) {
							above.add(() -> canvas.quad(claimed, sampling, inside.corners(), inside.uvs(), tint));
						}
						if (overlay != null) {
							above.add(() -> canvas.quad(overlay, sampling, inside.corners(), inside.uvs(), tint));
						}
					}
					continue;
				}
				canvas.rect(tile.texture(), tiles, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint);
				if (claimed != null) {
					above.add(() -> canvas.rect(claimed, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
				if (overlay != null) {
					above.add(() -> canvas.rect(overlay, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
			}
		}
		if (this.drawsNear(view, lod)) {
			drawNear(level, view, canvas, tiles, circle, tint);
		}
		if (lit != null) {
			lit.run();
		}
		above.forEach(Runnable::run);
	}

	/**
	 * Draws a tile that hasn't loaded from one that has: the coarser tile over the same ground,
	 * magnified, else the finer tiles under it, shrunk, from up to {@link #FINER_STAND_INS} levels
	 * down. Blurry or rough, but the ground doesn't vanish while the right tile loads, which on a big
	 * map takes a while. Draws nothing if none is in memory.
	 */
	private static void drawStandIn(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint) {
		TileKey above = key;
		for (int up = 1; key.lod() + up <= TileKey.MAX_LOD; up++) {
			above = above.parent();
			MapTile tile = level.standIn(above);
			if (tile != null && tile.texture() != null) {
				int span = 1 << up;
				float u0 = Math.floorMod(key.tileU(), span) / (float) span;
				float v0 = Math.floorMod(key.tileV(), span) / (float) span;
				drawPart(canvas, tile, sampling, circle, sx, sy, size, u0, v0, u0 + 1F / span, v0 + 1F / span, tint);
				return;
			}
		}
		drawFiner(level, key, canvas, sampling, circle, sx, sy, size, tint, FINER_STAND_INS);
	}

	/**
	 * How many levels finer a stand-in can come from. One notch of the wheel zooms 1.25 times and a
	 * level is twice the zoom, so a quick scroll crosses two levels before the one between has loaded,
	 * and the tiles last drawn are then two levels finer.
	 */
	private static final int FINER_STAND_INS = 2;

	/** Draws the four tiles one level finer than {@code key}, and in place of any not in memory, theirs. */
	private static void drawFiner(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint, int levels) {
		if (key.lod() == 0 || levels == 0) {
			return;
		}
		float half = size / 2;
		for (int dv = 0; dv < 2; dv++) {
			for (int du = 0; du < 2; du++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + du, key.tileV() * 2 + dv, key.lod() - 1);
				MapTile tile = level.standIn(child);
				if (tile != null && tile.texture() != null) {
					drawPart(canvas, tile, sampling, circle, sx + du * half, sy + dv * half, half, 0, 0, 1, 1, tint);
				} else if (tile == null) {
					drawFiner(level, child, canvas, sampling, circle, sx + du * half, sy + dv * half, half, tint, levels - 1);
				}
			}
		}
	}

	/** Draws the part of a tile's texture from (u0, v0) to (u1, v1) in a square, inside the circle if the view is round. */
	private static void drawPart(MapCanvas canvas, MapTile tile, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, float u0, float v0, float u1, float v1, int tint) {
		if (circle != null) {
			Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, u0, v0, u1, v1);
			if (inside != null) {
				canvas.quad(tile.texture(), sampling, inside.corners(), inside.uvs(), tint);
			}
			return;
		}
		canvas.rect(tile.texture(), sampling, sx, sy, sx + size, sy + size, u0, v0, u1, v1, tint);
	}

	/**
	 * Whether detailed tiles go over this view's overviews. Not on a see-through map: the ground
	 * would show through twice, as a brighter square, so it waits for the overviews instead.
	 */
	private boolean drawsNear(View view, int lod) {
		return lod > 0 && view.follows() && this.opacity >= 1;
	}

	private static void drawNear(MapLevel level, View view, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, int tint) {
		int units = Projection.TILE_UNITS;
		float size = (float) (units * view.zoom());
		int nearU = (int) Math.floor(view.centerU() / units);
		int nearV = (int) Math.floor(view.centerV() / units);
		for (int tu = nearU - NEAR_TILES; tu <= nearU + NEAR_TILES; tu++) {
			for (int tv = nearV - NEAR_TILES; tv <= nearV + NEAR_TILES; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, 0);
				float sx = view.screenX((double) tu * units);
				float sy = view.screenY((double) tv * units);
				boolean visible = sx < view.x() + view.width() && sx + size > view.x() && sy < view.y() + view.height() && sy + size > view.y();
				// As in drawTiles: those just outside the view bake too, after everything in it.
				MapTile tile = level.want(key, distance(key, view) + (visible ? 0 : 100_000));
				if (!visible || tile == null || tile.texture() == null) {
					continue;
				}
				if (circle == null) {
					canvas.rect(tile.texture(), sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint);
					continue;
				}
				Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, 0, 0, 1, 1);
				if (inside != null) {
					canvas.quad(tile.texture(), sampling, inside.corners(), inside.uvs(), tint);
				}
			}
		}
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view) {
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
		boolean fast = TileBaker.fast();
		// Cloud cells double halfway to the next step; while they hand over, both sizes are drawn.
		this.buildMask(level, view, originU, originV, texel, steps, fraction >= 7 ? 2 : 1, fast);

		double span = MASK * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / snap), 8);
		int noiseV = Math.floorMod((long) (originV / snap), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		int fogCode = (cave ? 128 : 0) | steps << 4 | fraction;
		// The mask scale code takes the alpha byte's high nibble; 4 in the low nibble asks for fast
		// fog, and 1 for smoke instead of clouds over the sky view under a roof.
		int quality = (fast ? 4 : 0) | (level.roofless() ? 1 : 0);
		// The low five bits of the noise offset's red byte hold how see-through the fog is, 0 for
		// not at all, so a caller that sets nothing there gets the fog it always did.
		int veil = Math.round((1 - this.opacity) * OPACITY_CODES);
		int params = (scaleCode * 16 | quality) << 24 | (noiseU * 32 | veil) << 16 | (noiseV * 32) << 8 | fogCode;

		MapCanvas canvas = MapCanvas.of(graphics);
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), mu0, mv0, mu1, mv1);
			if (disc != null) {
				canvas.fog(this.maskTexture, FogTextures.noise(), disc.corners(), disc.uvs(), view.x(), view.y(), view.width(), view.height(), params);
			}
			return;
		}
		canvas.fog(
			this.maskTexture, FogTextures.noise(),
			view.x(), view.y(), view.width(), view.height(),
			mu0, mv0, mu1, mv1,
			params
		);
	}

	private void buildMask(MapLevel level, View view, double originU, double originV, double texel, int scaleSteps, int cellScale, boolean fast) {
		if (this.maskTexture == null) {
			this.maskTexture = MapTexture.create("diorama fog mask", MASK, 1);
		}
		int lod = lodFor(level, view);
		// Caves keep the sharp mask: rounding would erode narrow explored tunnels away.
		// Once a texel spans a chunk there are no chunk steps left to smooth, and rounding would
		// only eat small explored areas that are a few texels across.
		boolean rounded = MapStyle.get().roundedFog() && view.layer() == TileKey.SURFACE && texel < 16;
		MapMemory memory = view.layer() == TileKey.SURFACE ? level.memory() : null;
		boolean drawsNear = this.drawsNear(view, lod);
		int nearU = drawsNear ? (int) Math.floor(view.centerU() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		int nearV = drawsNear ? (int) Math.floor(view.centerV() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		// The memory's level can change with the zoom while the real tiles' doesn't.
		int memoryLod = memory == null ? -1 : memory.lod(view, lod);
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), memory == null ? 0 : memory.version(), view.layer(), lod, memoryLod, originU, originV, texel, rounded, scaleSteps, cellScale, fast, nearU, nearV);
		if (this.maskLevel.get() == level && inputs.equals(this.maskInputs)) {
			if (this.lifting) {
				this.easeLift();
			}
			return;
		}
		MaskInputs before = this.maskInputs;
		boolean sameLevel = this.maskLevel.get() == level;
		this.maskInputs = inputs;
		this.maskLevel = new WeakReference<>(level);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		MapTile above = null;
		TileKey aboveKey = null;
		// The finer tiles under the last point read, one level down and the next, as drawTiles stands them in.
		MapTile[] finer = new MapTile[FINER_STAND_INS];
		int[] lastFinerTu = new int[FINER_STAND_INS];
		int[] lastFinerTv = new int[FINER_STAND_INS];
		Arrays.fill(lastFinerTu, Integer.MIN_VALUE);
		Arrays.fill(lastFinerTv, Integer.MIN_VALUE);
		int lastNearTu = Integer.MIN_VALUE;
		int lastNearTv = Integer.MIN_VALUE;
		MapTile nearTile = null;
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
					above = null;
					aboveKey = null;
					if (tile == null || !tile.hasCoverage()) {
						// As drawTiles stands a coarser tile in for one that hasn't loaded: it knows as much.
						TileKey up = new TileKey(view.layer(), tu, tv, lod);
						for (int l = lod + 1; l <= TileKey.MAX_LOD && above == null; l++) {
							up = up.parent();
							MapTile candidate = level.peek(up);
							if (candidate != null && candidate.hasCoverage()) {
								above = candidate;
								aboveKey = up;
							}
						}
					}
				}
				float known = 0;
				if (tile != null && tile.hasCoverage()) {
					int cellX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cellUnits));
					known = tile.coverage(cellX, cellY);
				} else if (above != null) {
					double aboveSpan = aboveKey.units();
					double aboveCell = aboveSpan / Projection.COVERAGE_CELLS;
					int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - aboveKey.tileU() * aboveSpan) / aboveCell)));
					int aboveY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - aboveKey.tileV() * aboveSpan) / aboveCell)));
					known = above.coverage(cellX, aboveY);
				} else {
					// Or a finer tile under this spot, as drawTiles shrinks it in: the next level down
					// if it's in memory, else the one below that.
					for (int down = 1; down <= FINER_STAND_INS && lod - down >= 0; down++) {
						double span = units / (1 << down);
						int finerTu = (int) Math.floor(u / span);
						int finerTv = (int) Math.floor(v / span);
						if (finerTu != lastFinerTu[down - 1] || finerTv != lastFinerTv[down - 1]) {
							finer[down - 1] = level.peek(new TileKey(view.layer(), finerTu, finerTv, lod - down));
							lastFinerTu[down - 1] = finerTu;
							lastFinerTv[down - 1] = finerTv;
						}
						MapTile under = finer[down - 1];
						if (under != null) {
							if (under.hasCoverage()) {
								double finerCell = span / Projection.COVERAGE_CELLS;
								int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - finerTu * span) / finerCell)));
								int finerY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - finerTv * span) / finerCell)));
								known = under.coverage(cellX, finerY);
							}
							break;
						}
					}
				}
				// Ground the overviews don't have yet can be on a detailed tile drawn over them.
				if (drawsNear && known < 1) {
					int nearTu = (int) Math.floor(u / Projection.TILE_UNITS);
					int nearTv = (int) Math.floor(v / Projection.TILE_UNITS);
					if (Math.abs(nearTu - nearU) <= NEAR_TILES && Math.abs(nearTv - nearV) <= NEAR_TILES) {
						if (nearTu != lastNearTu || nearTv != lastNearTv) {
							nearTile = level.peek(new TileKey(view.layer(), nearTu, nearTv, 0));
							lastNearTu = nearTu;
							lastNearTv = nearTv;
						}
						if (nearTile != null && nearTile.hasCoverage()) {
							double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
							int nearX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - nearTu * (double) Projection.TILE_UNITS) / cell));
							int nearY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - nearTv * (double) Projection.TILE_UNITS) / cell));
							known = Math.max(known, nearTile.coverage(nearX, nearY));
						}
					}
				}
				this.maskPixels[j * MASK + i] = 0xFF000000 | Math.round(known * 255);
			}
		}
		if (rounded) {
			this.roundOff(Math.max(1, (int) Math.round(10 / texel)));
		}
		this.lifting = false;
		boolean surface = view.layer() == TileKey.SURFACE;
		boolean clouds = surface && !level.roofless();
		this.reach = fogReach(texel, !surface, fast, scaleSteps, clouds ? cellScale : 0);
		this.cloudReach = cloudReach(texel, scaleSteps, cellScale);
		if (surface) {
			if (memory != null) {
				// The whole mask, not just the view: the clouds climb toward land the old map never
				// had, and past the view's edge is no such land.
				memory.ask(memoryLod, originU, originV, originU + MASK * texel, originV + MASK * texel);
				for (int j = 0; j < MASK; j++) {
					double v = originV + (j + 0.5) * texel;
					for (int i = 0; i < MASK; i++) {
						float known = memory.known(memoryLod, originU + (i + 0.5) * texel, v);
						// A remembered tile not read yet is taken as all known: read a moment later, it
						// rarely has much less, while taking it for unexplored stands the clouds up to
						// full height until it lands.
						this.remembered[j * MASK + i] = Float.isNaN(known) ? 1 : known;
					}
				}
			} else {
				Arrays.fill(this.remembered, 0);
			}
		}
		if (!clouds) {
			// Caves and smoke keep the fog as it always was: full height (alpha 255), but over a
			// remembered map, whose share alpha is 255 less, so the fog lies low there.
			this.markReach(this.reach, false);
			if (surface) {
				for (int k = 0; k < MASK * MASK; k++) {
					this.maskPixels[k] = this.maskPixels[k] & 0xFFFFFF | 255 - Math.round(this.remembered[k] * 255) << 24;
				}
			}
		} else {
			int centerI = (int) Math.floor((view.centerU() - originU) / texel);
			int centerJ = (int) Math.floor((view.centerV() - originV) / texel);
			this.liftTargets(centerI, centerJ, texel, scaleSteps);
			// The heights ease to new ones, as tiles land (as often as every frame in a redraw) or the
			// view pans; a pan moves them with the ground. The mask can't see explored land past its
			// edge, so heights near the edge are only a guess, corrected as a pan brings that land in:
			// eased, that reads as the clouds settling, not jumping. A new mask scale or another map
			// has nothing to ease from.
			boolean fresh = !sameLevel || before == null || before.texel() != texel || before.lod() != lod || before.layer() != view.layer()
				|| before.scaleSteps() != scaleSteps;
			int di = fresh ? 0 : (int) Math.round((originU - before.originU()) / texel);
			int dj = fresh ? 0 : (int) Math.round((originV - before.originV()) / texel);
			if (fresh || Math.abs(di) >= MASK || Math.abs(dj) >= MASK) {
				System.arraycopy(this.liftTarget, 0, this.liftShown, 0, this.liftShown.length);
				this.liftMs = System.currentTimeMillis();
			} else {
				if (di != 0 || dj != 0) {
					for (int j = 0; j < MASK; j++) {
						for (int i = 0; i < MASK; i++) {
							int fromI = i + di;
							int fromJ = j + dj;
							boolean inside = fromI >= 0 && fromI < MASK && fromJ >= 0 && fromJ < MASK;
							this.liftMoved[j * MASK + i] = inside ? this.liftShown[fromJ * MASK + fromI] : this.liftTarget[j * MASK + i];
						}
					}
					System.arraycopy(this.liftMoved, 0, this.liftShown, 0, this.liftShown.length);
				}
				this.lifting = !this.stepLift();
			}
			this.markReach(this.reach, true);
			this.writeRemembered();
			this.writeLift();
			this.writeCloudTops();
		}
		this.maskTexture.write(0, 0, 0, MASK, this.maskPixels);
	}

	/**
	 * The remembered share into green, at 0.4 for all of it, where green isn't the clear flag: the
	 * shader lets that much of the old map show through the clouds.
	 */
	private void writeRemembered() {
		for (int k = 0; k < MASK * MASK; k++) {
			int pixel = this.maskPixels[k];
			if ((pixel & 0xFF00) == 0) {
				this.maskPixels[k] = pixel | Math.round(this.remembered[k] * 0.4F * 255) << 8;
			}
		}
	}

	/** Cloud levels, and how far from explored land the clouds over never-explored land reach full height, per level in cells. Match fog.fsh. */
	private static final double CLOUD_LEVELS = 5;
	private static final double CLOUD_CELL = 12.8;
	private static final double CELLS_PER_LEVEL = 5;

	/** How much of a remembered map is under each mask texel, 0 to 1. */
	private final float[] remembered = new float[MASK * MASK];
	/** How tall the fog stands over each texel, as a share of its full height: {@link #liftTargets}. */
	private final float[] liftTarget = new float[MASK * MASK];
	/** The heights drawn, easing toward the targets. */
	private final float[] liftShown = new float[MASK * MASK];
	private final float[] liftMoved = new float[MASK * MASK];
	private final int[] toRedrawn = new int[MASK * MASK];
	private final int[] toUnexplored = new int[MASK * MASK];
	private boolean lifting;
	/** How far the shader reads around a pixel, for the mask last built: {@link #fogReach}. */
	private int[] reach = new int[4];
	private long liftMs;
	/** Lift a second the fog's height can change by while easing. */
	private static final float LIFT_PER_SECOND = 0.6F;

	/**
	 * The fog over a remembered map rolls up from the land already redrawn to the full height of
	 * the fog over land never explored: a texel's lift is its distance from the redrawn land over
	 * that plus its distance from the unexplored. Lift 1 is the fog as it always is; a texel with
	 * no remembered map under it has 1. With nothing unexplored in the view the slope runs out
	 * over the view's width; with nothing redrawn yet it starts at the middle of the view, where
	 * the redraw starts.
	 */
	private void liftTargets(int centerI, int centerJ, double texel, int scaleSteps) {
		int far = MASK * 3;
		boolean anyRedrawn = false;
		boolean anyRemembered = false;
		for (int k = 0; k < MASK * MASK; k++) {
			boolean redrawn = (this.maskPixels[k] & 0xFF) >= 128;
			anyRedrawn |= redrawn;
			anyRemembered |= this.remembered[k] >= 0.5F;
			this.toRedrawn[k] = redrawn ? 0 : Integer.MAX_VALUE / 2;
			this.toUnexplored[k] = !redrawn && this.remembered[k] < 0.5F ? 0 : Integer.MAX_VALUE / 2;
		}
		if (!anyRedrawn && anyRemembered) {
			this.toRedrawn[Math.max(0, Math.min(MASK - 1, centerJ)) * MASK + Math.max(0, Math.min(MASK - 1, centerI))] = 0;
		}
		chamfer(this.toRedrawn);
		chamfer(this.toUnexplored);
		// Over never-explored land the clouds climb a level every few cells away from explored land.
		double climbThirds = 3 * CLOUD_LEVELS * CELLS_PER_LEVEL * CLOUD_CELL * (1 << scaleSteps) / texel;
		for (int k = 0; k < MASK * MASK; k++) {
			float redrawn = Math.min(this.toRedrawn[k], far);
			float unexplored = Math.min(this.toUnexplored[k], far);
			float ramp = redrawn + unexplored == 0 ? 1 : redrawn / (redrawn + unexplored);
			float climb = anyRedrawn ? (float) Math.min(1, this.toRedrawn[k] / climbThirds) : 1;
			this.liftTarget[k] = this.remembered[k] * ramp + (1 - this.remembered[k]) * climb;
		}
	}

	/** Distances in thirds of a texel to the nearest 0, by the 3-4 chamfer: two passes over the grid. */
	private static void chamfer(int[] d) {
		for (int j = 0; j < MASK; j++) {
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int v = d[k];
				if (i > 0) {
					v = Math.min(v, d[k - 1] + 3);
				}
				if (j > 0) {
					v = Math.min(v, d[k - MASK] + 3);
					if (i > 0) {
						v = Math.min(v, d[k - MASK - 1] + 4);
					}
					if (i < MASK - 1) {
						v = Math.min(v, d[k - MASK + 1] + 4);
					}
				}
				d[k] = v;
			}
		}
		for (int j = MASK - 1; j >= 0; j--) {
			for (int i = MASK - 1; i >= 0; i--) {
				int k = j * MASK + i;
				int v = d[k];
				if (i < MASK - 1) {
					v = Math.min(v, d[k + 1] + 3);
				}
				if (j < MASK - 1) {
					v = Math.min(v, d[k + MASK] + 3);
					if (i < MASK - 1) {
						v = Math.min(v, d[k + MASK + 1] + 4);
					}
					if (i > 0) {
						v = Math.min(v, d[k + MASK - 1] + 4);
					}
				}
				d[k] = v;
			}
		}
	}

	/** Steps the drawn heights toward the targets, for the time since the last step. Whether they're all there. */
	private boolean stepLift() {
		long now = System.currentTimeMillis();
		// At most a frame's worth, as if from the last frame: after a quiet spell the heights still ease.
		float step = Math.max(0.001F, Math.min(100, now - this.liftMs) / 1000.0F * LIFT_PER_SECOND);
		this.liftMs = now;
		boolean done = true;
		for (int k = 0; k < MASK * MASK; k++) {
			float diff = this.liftTarget[k] - this.liftShown[k];
			if (Math.abs(diff) <= step) {
				this.liftShown[k] = this.liftTarget[k];
			} else {
				this.liftShown[k] += Math.signum(diff) * step;
				done = false;
			}
		}
		return done;
	}

	/** Eases the drawn heights on a frame the mask isn't rebuilt. */
	private void easeLift() {
		this.lifting = !this.stepLift();
		if (!this.lifting) {
			// Settled: clouds that have reached the full height can have the flat top now.
			for (int k = 0; k < MASK * MASK; k++) {
				this.maskPixels[k] &= 0xFF0000FF;
			}
			this.markReach(this.reach, true);
			this.writeRemembered();
		}
		this.writeLift();
		this.writeCloudTops();
		if (this.maskTexture != null) {
			this.maskTexture.write(0, 0, 0, MASK, this.maskPixels);
		}
	}

	/** The drawn heights into the mask's alpha. */
	private void writeLift() {
		for (int k = 0; k < MASK * MASK; k++) {
			this.maskPixels[k] = this.maskPixels[k] & 0xFFFFFF | Math.round(this.liftShown[k] * 255) << 24;
		}
	}

	/** A cloud level's thickness in cells; match fog.fsh. */
	private static final double LEVEL_THICK = 0.9;
	/** Blue a cloud level, where blue isn't the deep flag (255): fog.fsh reads the highest level within reach. */
	private static final int TOP_CODE = 32;

	/**
	 * How far, in mask texels, the clouds can read from a pixel: {west, east, north, south}. Mirrors
	 * fog.fsh: a level's cover reads the cells within one of a point, at a point the curling edges
	 * push up to 1.45 cells away; the looks toward the sun and the ground shadow reach less. The line
	 * of sight leans south over the levels' height. Cells are {@code cellScale} times their size
	 * while the zoom hands over to the next step. One texel more for linear filtering, half a texel
	 * for where in its own texel the pixel sits.
	 */
	private static int[] cloudReach(double texel, int scaleSteps, int cellScale) {
		double cell = CLOUD_CELL * (1 << scaleSteps) * cellScale / texel;
		double around = 2.45 * cell + 1.5;
		double lean = CLOUD_LEVELS * LEVEL_THICK * cell * 0.643;
		int side = (int) Math.ceil(around);
		return new int[] {side, side, side, (int) Math.ceil(around + lean)};
	}

	private int[] cloudReach = new int[4];
	private final int[] cellTop = new int[MASK * MASK];
	private final int[] topScratch = new int[MASK * MASK];
	private final int[] topCounts = new int[(MASK + 1) * (MASK + 1)];

	/**
	 * Into blue, where it isn't the deep flag: the highest cloud level any cell within the clouds'
	 * reach can stand at, 0 for none. The shader starts its march down through the levels just above
	 * that, and skips it with none, which draws the same as marching from the top.
	 */
	private void writeCloudTops() {
		// The most a cell can stand, from a texel: fog.fsh reads a cell's middle filtered, between
		// four texels, at least one of them under half known if the cell has clouds at all; its height
		// is then at most the highest of the texels around that one, plus the stairs' noise (and a hair
		// for the graphics card's rounding).
		int[] alpha = this.topScratch;
		for (int j = 0; j < MASK; j++) {
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int a = this.maskPixels[k] >>> 24;
				if (i > 0) {
					a = Math.max(a, this.maskPixels[k - 1] >>> 24);
				}
				if (i < MASK - 1) {
					a = Math.max(a, this.maskPixels[k + 1] >>> 24);
				}
				alpha[k] = a;
			}
		}
		for (int j = 0; j < MASK; j++) {
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int a = alpha[k];
				if (j > 0) {
					a = Math.max(a, alpha[k - MASK]);
				}
				if (j < MASK - 1) {
					a = Math.max(a, alpha[k + MASK]);
				}
				boolean cloudy = (this.maskPixels[k] & 0xFF) < 128;
				this.cellTop[k] = cloudy ? (int) Math.max(1, Math.min(CLOUD_LEVELS, Math.ceil(a / 255.0 * CLOUD_LEVELS + 0.31))) : 0;
			}
		}
		// For each level, how many texels within reach can stand that high: the highest with any.
		int row = MASK + 1;
		int[] counts = this.topCounts;
		int[] top = alpha;
		Arrays.fill(top, 0);
		int[] reach = this.cloudReach;
		for (int level = 1; level <= CLOUD_LEVELS; level++) {
			for (int j = 0; j < MASK; j++) {
				int line = 0;
				for (int i = 0; i < MASK; i++) {
					line += this.cellTop[j * MASK + i] >= level ? 1 : 0;
					counts[(j + 1) * row + i + 1] = counts[j * row + i + 1] + line;
				}
			}
			for (int j = 0; j < MASK; j++) {
				int t = Math.max(0, j - reach[2]);
				int b = Math.min(MASK - 1, j + reach[3]) + 1;
				for (int i = 0; i < MASK; i++) {
					int l = Math.max(0, i - reach[0]);
					int r = Math.min(MASK - 1, i + reach[1]) + 1;
					if (counts[b * row + r] - counts[t * row + r] - counts[b * row + l] + counts[t * row + l] > 0) {
						top[j * MASK + i] = level;
					}
				}
			}
		}
		for (int k = 0; k < MASK * MASK; k++) {
			int pixel = this.maskPixels[k];
			if ((pixel & 0xFF0000) != 0xFF0000) {
				this.maskPixels[k] = pixel & 0xFF00FFFF | top[k] * TOP_CODE << 16;
			}
		}
	}

	/**
	 * How far, in mask texels, the fog shader can read from a pixel: {west, east, north, south}.
	 * Mirrors fog.fsh: the blur taps (the wide surface blur, 1.9x on the diagonals, never under a
	 * texel), the height layers sitting up to fogHeight * COS_PITCH further south, and the sun probe
	 * 9 west and 3.6 north times the noise scale. On top of that one texel for linear filtering,
	 * and half a texel for where in its own texel the pixel sits.
	 */
	private static int[] fogReach(double texel, boolean cave, boolean fast, int scaleSteps, int cellScale) {
		double fogHeight = cave ? 4 : 10;
		double blurUnits = cave ? 2.5 : 7;
		if (blurUnits >= 5 && !fast) {
			blurUnits *= 2.4;
		}
		double blur = 1.9 * Math.max(blurUnits / texel, 1);
		double layerShift = fogHeight * 0.643 / texel;
		// The noise scale is below 2^(steps + 1).
		double sun = fast ? 0 : 9.0 * (1 << (scaleSteps + 1)) / texel;
		double slack = 1.5;
		// The clouds' reach, cellScale 0 for none: see cloudReach.
		int[] clouds = cellScale == 0 ? new int[4] : cloudReach(texel, scaleSteps, cellScale);
		return new int[] {
			Math.max((int) Math.ceil(Math.max(blur, sun) + slack), clouds[0]),
			Math.max((int) Math.ceil(blur + slack), clouds[1]),
			Math.max((int) Math.ceil(Math.max(blur, sun * 0.4) + slack), clouds[2]),
			Math.max((int) Math.ceil(blur + layerShift + slack), clouds[3])
		};
	}

	private final int[] notKnownCounts = new int[(MASK + 1) * (MASK + 1)];
	private final int[] notUnknownCounts = new int[(MASK + 1) * (MASK + 1)];

	/**
	 * Sets the green channel where every texel within reach is fully known, and the blue channel
	 * where every one is fully unknown. The shader skips its mask reads there, because it knows
	 * what they would return: over clear ground there is no fog thickness anywhere it looks, so it
	 * skips the noise too; deep in the fog only the noise is left to read. Texels past the mask's
	 * edge read as the edge texel, so the reach just stops at the edge.
	 */
	private void markReach(int[] reach, boolean surface) {
		int row = MASK + 1;
		// counts[(j + 1) * row + i + 1]: texels short of fully known (or with any known share) in the
		// rectangle from (0, 0) to (i, j).
		int[] notKnown = this.notKnownCounts;
		int[] notUnknown = this.notUnknownCounts;
		for (int j = 0; j < MASK; j++) {
			int lineNotKnown = 0;
			int lineNotUnknown = 0;
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int known = this.maskPixels[k] & 0xFF;
				lineNotKnown += known == 0xFF ? 0 : 1;
				// On the surface "fully unknown" is plain too: the clouds at full height, now and as
				// they ease, and no old map under them. The shader draws a flat cloud top there.
				boolean plain = !surface || this.liftTarget[k] >= 1 && this.liftShown[k] >= 1 && this.remembered[k] <= 0;
				lineNotUnknown += known == 0 && plain ? 0 : 1;
				notKnown[(j + 1) * row + i + 1] = notKnown[j * row + i + 1] + lineNotKnown;
				notUnknown[(j + 1) * row + i + 1] = notUnknown[j * row + i + 1] + lineNotUnknown;
			}
		}
		for (int j = 0; j < MASK; j++) {
			int top = Math.max(0, j - reach[2]);
			int bottom = Math.min(MASK - 1, j + reach[3]) + 1;
			for (int i = 0; i < MASK; i++) {
				int left = Math.max(0, i - reach[0]);
				int right = Math.min(MASK - 1, i + reach[1]) + 1;
				int a = bottom * row + right;
				int b = top * row + right;
				int c = bottom * row + left;
				int d = top * row + left;
				if (notKnown[a] - notKnown[b] - notKnown[c] + notKnown[d] == 0) {
					this.maskPixels[j * MASK + i] |= 0xFF00;
				} else if (notUnknown[a] - notUnknown[b] - notUnknown[c] + notUnknown[d] == 0) {
					this.maskPixels[j * MASK + i] |= 0xFF0000;
				}
			}
		}
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
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		this.maskInputs = null;
	}
}
