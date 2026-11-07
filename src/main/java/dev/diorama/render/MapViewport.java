package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.FogLayer;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	/**
	 * What fog.fsh would otherwise work out from the mask for every pixel, worked out once per mask
	 * texel instead: red is the known share blurred as the fog's soft edge reads it.
	 */
	private @Nullable MapTexture detailTexture;
	/** Texels of the detail texture's edge: room for a view's cells at their most. */
	private static final int DETAIL = 1024;
	private final int[] detailPixels = new int[DETAIL * DETAIL];
	private final byte[] softBytes = new byte[MASK * MASK];
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, long memoryVersion, int layer, int lod, int memoryLod, double originU, double originV, double texel, boolean rounded, int scaleSteps, int fraction, boolean fast, int nearU, int nearV, long clouds) {
		/**
		 * Whether a mask built from these lies over the screen as one built from {@code other} does:
		 * same place, scale, cells and fog. Only what the map knows differs, which may show a frame late.
		 */
		boolean sameGeometry(MaskInputs other) {
			return this.layer == other.layer && this.originU == other.originU && this.originV == other.originV && this.texel == other.texel
				&& this.rounded == other.rounded && this.scaleSteps == other.scaleSteps && this.fraction == other.fraction && this.fast == other.fast;
		}
	}

	/**
	 * Finishes fog masks off the render thread. After the tiles are read into the mask, the rest of
	 * a build (rounding off, the soft edge, the clouds' heights, the reach marks, the cells) took 3 to
	 * 5 milliseconds of the frame on a fast CPU, several times a second while flying over new land.
	 * When only what the map knows changed, the view is where it was and a mask a frame late shows
	 * the same: so this thread does that part, and the render thread uploads it once it's done.
	 */
	private static final ExecutorService MASK_WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-fog-mask");
		thread.setDaemon(true);
		// Under the game's threads, over the bakers': the fog waits on it.
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	/**
	 * The mask being finished on {@link #MASK_WORKER}, or null. Until it's done, everything a build
	 * works in (the mask's pixels, the heights, the cells and what they're made with) is the
	 * worker's, and the render thread leaves it alone.
	 */
	private @Nullable CompletableFuture<Void> finishing;
	/** The worker's build threw: built again in full on the render thread. */
	private volatile boolean finishFailed;
	private static boolean finishFailureLogged;
	/** The detail texture's part to upload with the mask: {x, y, edge}, pixels in {@link #detailUpload}. */
	private final int[] detailArea = new int[3];
	private int @Nullable [] detailUpload;

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
	/** How far into the night to draw the sky view whatever the time, or -1 to follow {@link NightLook}. */
	private float night = -1;

	/**
	 * Sets how opaque this map is drawn: the tiles and the fog, and not the backdrop behind them, so
	 * the game shows through. The fullscreen map leaves it at 1; the minimap takes it from the settings.
	 */
	/** Draws the sky view this far into the night (0..1) whatever the time, or as {@link NightLook} has it for -1. */
	public void night(float night) {
		this.night = night;
	}

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
		float night = this.night < 0 || cave || level.roofless() ? NightLook.forView(level, view.layer()) : this.night;
		if (night > 0) {
			background = NightLook.darken(background, night);
		}
		// A see-through map has no backdrop: drawn under tiles that are themselves see-through, it
		// would show through them and tint the game behind.
		if (this.opacity < 1) {
			// Nothing to draw.
		} else if (view.round()) {
			MapCanvas.of(graphics).solid(Circle.in(view).disc(), background);
		} else {
			graphics.fill(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), background);
		}

		int alpha = Math.round(this.opacity * 255);
		MapMemory memory = cave ? null : level.memory();
		if (memory != null) {
			memory.draw(MapCanvas.of(graphics), view, NightLook.dim(night, alpha), lodFor(level, view), tileSampling(sampling(view)));
		}
		this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0, claims, lit, night);
		this.drawFog(graphics, level, view, night);
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

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit, float night) {
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
					drawStandIn(level, key, canvas, tiles, circle, sx, sy, size, tint, night);
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
						drawTile(canvas, tile, tiles, inside.corners(), inside.uvs(), tint, night);
						if (claimed != null) {
							above.add(() -> canvas.quad(claimed, sampling, inside.corners(), inside.uvs(), tint));
						}
						if (overlay != null) {
							above.add(() -> canvas.quad(overlay, sampling, inside.corners(), inside.uvs(), tint));
						}
					}
					continue;
				}
				drawTile(canvas, tile, tiles, sx, sy, size, 0, 0, 1, 1, tint, night);
				if (claimed != null) {
					above.add(() -> canvas.rect(claimed, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
				if (overlay != null) {
					above.add(() -> canvas.rect(overlay, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
			}
		}
		if (this.drawsNear(view, lod)) {
			drawNear(level, view, canvas, tiles, circle, tint, night);
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
	private static void drawStandIn(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint, float night) {
		TileKey above = key;
		for (int up = 1; key.lod() + up <= TileKey.MAX_LOD; up++) {
			above = above.parent();
			MapTile tile = level.standIn(above);
			if (tile != null && tile.texture() != null) {
				int span = 1 << up;
				float u0 = Math.floorMod(key.tileU(), span) / (float) span;
				float v0 = Math.floorMod(key.tileV(), span) / (float) span;
				drawPart(canvas, tile, sampling, circle, sx, sy, size, u0, v0, u0 + 1F / span, v0 + 1F / span, tint, night);
				return;
			}
		}
		drawFiner(level, key, canvas, sampling, circle, sx, sy, size, tint, night, FINER_STAND_INS);
	}

	/**
	 * How many levels finer a stand-in can come from. One notch of the wheel zooms 1.25 times and a
	 * level is twice the zoom, so a quick scroll crosses two levels before the one between has loaded,
	 * and the tiles last drawn are then two levels finer.
	 */
	private static final int FINER_STAND_INS = 2;

	/** Draws the four tiles one level finer than {@code key}, and in place of any not in memory, theirs. */
	private static void drawFiner(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint, float night, int levels) {
		if (key.lod() == 0 || levels == 0) {
			return;
		}
		float half = size / 2;
		for (int dv = 0; dv < 2; dv++) {
			for (int du = 0; du < 2; du++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + du, key.tileV() * 2 + dv, key.lod() - 1);
				MapTile tile = level.standIn(child);
				if (tile != null && tile.texture() != null) {
					drawPart(canvas, tile, sampling, circle, sx + du * half, sy + dv * half, half, 0, 0, 1, 1, tint, night);
				} else if (tile == null) {
					drawFiner(level, child, canvas, sampling, circle, sx + du * half, sy + dv * half, half, tint, night, levels - 1);
				}
			}
		}
	}

	/** Draws the part of a tile's texture from (u0, v0) to (u1, v1) in a square, inside the circle if the view is round. */
	private static void drawPart(MapCanvas canvas, MapTile tile, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, float u0, float v0, float u1, float v1, int tint, float night) {
		if (circle != null) {
			Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, u0, v0, u1, v1);
			if (inside != null) {
				drawTile(canvas, tile, sampling, inside.corners(), inside.uvs(), tint, night);
			}
			return;
		}
		drawTile(canvas, tile, sampling, sx, sy, size, u0, v0, u1, v1, tint, night);
	}

	/** A square of a tile's texture from (u0, v0) to (u1, v1); see the other {@link #drawTile}. */
	private static void drawTile(MapCanvas canvas, MapTile tile, Sampling sampling, float sx, float sy, float size, float u0, float v0, float u1, float v1, int tint, float night) {
		drawTile(
			canvas, tile, sampling,
			new float[] {sx, sy, sx, sy + size, sx + size, sy + size, sx + size, sy},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0},
			tint, night
		);
	}

	/**
	 * A tile's texture over these quads, as {@link MapCanvas#quad} draws it, {@code night} into the
	 * night ({@link NightLook}): by day as it is, at night dimmed, with its block light if it has any.
	 */
	private static void drawTile(MapCanvas canvas, MapTile tile, Sampling sampling, float[] corners, float[] uvs, int tint, float night) {
		if (night <= 0) {
			canvas.quad(tile.texture(), sampling, corners, uvs, tint);
			return;
		}
		int alpha = tint >>> 24;
		MapTexture light = tile.light();
		if (light == null) {
			canvas.quad(tile.texture(), sampling, corners, uvs, NightLook.dim(night, alpha));
			return;
		}
		canvas.nightTile(tile.texture(), sampling, light, corners, uvs, NightLook.params(night, sampling == Sampling.TILE_CRISP, alpha));
	}

	/**
	 * Whether detailed tiles go over this view's overviews. Not on a see-through map: the ground
	 * would show through twice, as a brighter square, so it waits for the overviews instead.
	 */
	private boolean drawsNear(View view, int lod) {
		return lod > 0 && view.follows() && this.opacity >= 1;
	}

	private static void drawNear(MapLevel level, View view, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, int tint, float night) {
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
				drawPart(canvas, tile, sampling, circle, sx, sy, size, 0, 0, 1, 1, tint, night);
			}
		}
	}

	private static volatile boolean fogHiddenForTests;

	/** For tests that check the tiles under the fog (TileFilterTest): the map drawn without it. */
	public static void hideFogForTests(boolean hide) {
		fogHiddenForTests = hide;
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view, float night) {
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
		this.buildMask(level, view, originU, originV, texel, steps, fraction, fast);
		if (fogHiddenForTests) {
			return;
		}

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
		// The green byte's low five bits: how far into the night, in 31sts, which dims the fog too.
		int dark = Math.round(night * 31);
		int params = (scaleCode * 16 | quality) << 24 | (noiseU * 32 | veil) << 16 | (noiseV * 32 | dark) << 8 | fogCode;

		MapCanvas canvas = MapCanvas.of(graphics);
		if (this.drawFogLayer(canvas, view, originU, originV, span, params)) {
			return;
		}
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), mu0, mv0, mu1, mv1);
			if (disc != null) {
				canvas.fog(this.maskTexture, FogTextures.noise(), this.detailTexture, disc.corners(), disc.uvs(), view.x(), view.y(), view.width(), view.height(), params);
			}
			return;
		}
		canvas.fog(
			this.maskTexture, FogTextures.noise(), this.detailTexture,
			view.x(), view.y(), view.width(), view.height(),
			mu0, mv0, mu1, mv1,
			params
		);
	}

	/**
	 * The fog at half the screen's resolution, scaled up: drawn into {@link FogLayer} when anything it
	 * shows changes (the mask, the view, the parameters) or a game tick goes by, which moves the
	 * clouds' slow drift on, and otherwise shown again as it is. False if the layer couldn't be drawn.
	 *
	 * <p>The layer's texels sit on a grid fixed to the map, whole texels from map 0, and the layer is
	 * shown shifted by where the view starts within a texel. So a pan draws the same fog at the same
	 * points of the map, and what's on screen moves with the map rather than being worked out afresh
	 * at new points, which made the clouds' thin wisps change shape as the map moved.
	 */
	private boolean drawFogLayer(MapCanvas canvas, View view, double originU, double originV, double span, int params) {
		double scale = Minecraft.getInstance().getWindow().getGuiScale() / 2.0;
		// The view in layer texels, and a texel in map units.
		double texelsW = view.width() * scale;
		double texelsH = view.height() * scale;
		double texel = 1 / (view.zoom() * scale);
		// From the grid line a texel before the view's start, so what's shown never blends with a
		// texel the layer didn't draw; a texel more after its end for the same reason.
		double startU = (Math.floor(view.minU() / texel) - 1) * texel;
		double startV = (Math.floor(view.minV() / texel) - 1) * texel;
		double shiftU = (view.minU() - startU) / texel;
		double shiftV = (view.minV() - startV) / texel;
		int width = (int) Math.ceil(shiftU + texelsW) + 1;
		int height = (int) Math.ceil(shiftV + texelsH) + 1;
		if (this.fogLayer == null) {
			this.fogLayer = new FogLayer();
		}
		float u0 = (float) ((startU - originU) / span);
		float v0 = (float) ((startV - originV) / span);
		float u1 = (float) ((startU + width * texel - originU) / span);
		float v1 = (float) ((startV + height * texel - originV) / span);
		var world = Minecraft.getInstance().level;
		LayerKey key = new LayerKey(this.fogSerial, params, width, height, u0, v0, u1, v1, world == null ? 0 : world.getGameTime());
		if (!key.equals(this.layerKey) || this.fogLayer.texture() == null) {
			if (!this.fogLayer.render(canvas, this.maskTexture, FogTextures.noise(), this.detailTexture, width, height, u0, v0, u1, v1, params | LAYER_CODE << 24)) {
				return false;
			}
			this.layerKey = key;
		}
		MapTexture layer = this.fogLayer.texture();
		float size = this.fogLayer.size();
		float su0 = (float) (shiftU / size);
		float sv0 = (float) (shiftV / size);
		float su1 = (float) ((shiftU + texelsW) / size);
		float sv1 = (float) ((shiftV + texelsH) / size);
		float x1 = view.x() + view.width();
		float y1 = view.y() + view.height();
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), x1, y1, su0, sv0, su1, sv1);
			if (disc != null) {
				canvas.quad(layer, Sampling.CLAMP_LINEAR, disc.corners(), disc.uvs(), 0xFFFFFFFF);
			}
		} else {
			canvas.rect(layer, Sampling.CLAMP_LINEAR, view.x(), view.y(), x1, y1, su0, sv0, su1, sv1, 0xFFFFFFFF);
		}
		return true;
	}

	/** In the fog parameters' alpha byte: draw into the layer (fog.fsh writes, rather than discards, where there is none). */
	private static final int LAYER_CODE = 2;

	/** Everything the layer shows: when it differs from what was drawn, the layer is drawn again. */
	private record LayerKey(long serial, int params, int width, int height, float u0, float v0, float u1, float v1, long tick) {
	}

	private @Nullable FogLayer fogLayer;
	private @Nullable LayerKey layerKey;
	/** Goes up whenever the mask or detail textures change. */
	private long fogSerial;

	private void buildMask(MapLevel level, View view, double originU, double originV, double texel, int scaleSteps, int fraction, boolean fast) {
		// Cloud cells double halfway to the next step; while they hand over, both sizes are drawn.
		int cellScale = fraction >= 7 ? 2 : 1;
		if (this.maskTexture == null) {
			this.maskTexture = MapTexture.create("diorama fog mask", MASK, 1);
			this.detailTexture = MapTexture.create("diorama fog details", DETAIL, 1);
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
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), memory == null ? 0 : memory.version(), view.layer(), lod, memoryLod, originU, originV, texel, rounded, scaleSteps, fraction, fast, nearU, nearV, VanillaClouds.version());
		boolean sameLevel = this.maskLevel.get() == level;
		this.takeFinished();
		if (sameLevel && inputs.equals(this.maskInputs)) {
			// A few times a second: a cell's level, all the heights show as, changes far less often.
			if (this.finishing == null && this.lifting && System.currentTimeMillis() - this.liftMs >= EASE_MS) {
				this.finish(this::easeLift, true);
			}
			return;
		}
		boolean later = sameLevel && this.maskInputs != null && inputs.sameGeometry(this.maskInputs);
		if (this.finishing != null) {
			if (later) {
				// The next starts once this one is in.
				return;
			}
			// The view moved: what's being finished wouldn't line up. It's what the heights ease from, though.
			this.finishing.join();
			this.finishing = null;
			if (this.finishFailed) {
				// Half made: nothing to ease from.
				this.maskInputs = null;
			}
		}
		MaskInputs before = this.maskInputs;
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
		boolean surface = view.layer() == TileKey.SURFACE;
		boolean clouds = surface && !level.roofless();
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
		int centerI = (int) Math.floor((view.centerU() - originU) / texel);
		int centerJ = (int) Math.floor((view.centerV() - originV) / texel);
		CellGrid grid = clouds ? CellGrid.of(view, originU, originV, texel, scaleSteps, fraction) : null;
		// Read here, so the worker finds them made: the clouds' texture is read from the resource packs, on this thread.
		FogTextures.noiseBytes();
		if (clouds) {
			VanillaClouds.prepare();
		}
		this.finish(() -> this.finishBuild(before, sameLevel, inputs, rounded, surface, clouds, cellScale, centerI, centerJ, grid), later);
	}

	/**
	 * The rest of a mask's build, from the known share read into the mask's pixels (and the
	 * remembered map into {@link #remembered}) to everything the textures take. Doesn't touch the
	 * map or the textures, so it can run on {@link #MASK_WORKER}.
	 */
	private void finishBuild(@Nullable MaskInputs before, boolean sameLevel, MaskInputs inputs, boolean rounded, boolean surface, boolean clouds,
		int cellScale, int centerI, int centerJ, @Nullable CellGrid grid) {
		double texel = inputs.texel();
		int scaleSteps = inputs.scaleSteps();
		int lod = inputs.lod();
		boolean fast = inputs.fast();
		double originU = inputs.originU();
		double originV = inputs.originV();
		if (rounded) {
			this.roundOff(Math.max(1, (int) Math.round(10 / texel)));
		}
		this.writeSoft(texel, !surface, fast);
		this.lifting = false;
		this.reach = fogReach(texel, !surface, fast, scaleSteps, clouds ? cellScale : 0);
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
			this.liftTargets(centerI, centerJ, texel, scaleSteps);
			this.cloudDistances(texel);
			// The heights ease to new ones, as tiles land (as often as every frame in a redraw) or the
			// view pans; a pan moves them with the ground. The mask can't see explored land past its
			// edge, so heights near the edge are only a guess, corrected as a pan brings that land in:
			// eased, that reads as the clouds settling, not jumping. A new mask scale or another map
			// has nothing to ease from.
			boolean fresh = !sameLevel || before == null || before.texel() != texel || before.lod() != lod || before.layer() != inputs.layer()
				|| before.scaleSteps() != scaleSteps;
			int di = fresh ? 0 : (int) Math.round((originU - before.originU()) / texel);
			int dj = fresh ? 0 : (int) Math.round((originV - before.originV()) / texel);
			if (fresh || Math.abs(di) >= MASK || Math.abs(dj) >= MASK) {
				System.arraycopy(this.liftTarget, 0, this.liftShown, 0, this.liftShown.length);
				System.arraycopy(this.remembered, 0, this.rememberedShown, 0, this.rememberedShown.length);
				this.liftMs = System.currentTimeMillis();
			} else {
				if (di != 0 || dj != 0) {
					for (int j = 0; j < MASK; j++) {
						for (int i = 0; i < MASK; i++) {
							int fromI = i + di;
							int fromJ = j + dj;
							boolean inside = fromI >= 0 && fromI < MASK && fromJ >= 0 && fromJ < MASK;
							this.liftMoved[j * MASK + i] = inside ? this.liftShown[fromJ * MASK + fromI] : this.liftTarget[j * MASK + i];
							this.rememberedMoved[j * MASK + i] = inside ? this.rememberedShown[fromJ * MASK + fromI] : this.remembered[j * MASK + i];
						}
					}
					System.arraycopy(this.liftMoved, 0, this.liftShown, 0, this.liftShown.length);
					System.arraycopy(this.rememberedMoved, 0, this.rememberedShown, 0, this.rememberedShown.length);
				}
				this.lifting = !this.stepLift();
			}
			this.markReach(this.reach, true);
			this.writeRemembered();
			this.writeLift();
		}
		this.cellGrid = grid;
		this.writeCells();
	}

	/**
	 * Runs {@code work}, a build's or an ease's part that doesn't touch the textures, then uploads
	 * what it made: on the worker if {@code later}, the upload then on a later frame (see
	 * {@link #takeFinished}), else here and now.
	 */
	private void finish(Runnable work, boolean later) {
		if (!later) {
			work.run();
			this.uploadMask();
			return;
		}
		this.finishFailed = false;
		this.finishing = CompletableFuture.runAsync(() -> {
			try {
				work.run();
			} catch (Throwable t) {
				this.finishFailed = true;
				if (!finishFailureLogged) {
					finishFailureLogged = true;
					Diorama.LOGGER.error("Building the fog mask failed", t);
				}
			}
		}, MASK_WORKER);
	}

	/** Uploads the mask the worker finished, if it has. Render thread. */
	private void takeFinished() {
		if (this.finishing == null || !this.finishing.isDone()) {
			return;
		}
		this.finishing = null;
		if (this.finishFailed) {
			// Half made: the next frame builds it all again, here.
			this.maskInputs = null;
			return;
		}
		this.uploadMask();
	}

	/** The mask's pixels and the detail texture's changed part to the graphics card. Render thread. */
	private void uploadMask() {
		if (this.maskTexture != null) {
			this.maskTexture.write(0, 0, 0, MASK, this.maskPixels);
		}
		if (this.detailTexture != null && this.detailUpload != null) {
			this.detailTexture.write(0, this.detailArea[0], this.detailArea[1], this.detailArea[2], this.detailUpload);
		}
		this.detailUpload = null;
		this.fogSerial++;
	}

	/**
	 * The remembered share drawn, eased like the heights, into green, at 0.4 for all of it, where
	 * green isn't the clear flag: the shader covers the old map softly by it, so a remembered tile
	 * read a moment late fades in or out instead of switching.
	 */
	private void writeRemembered() {
		for (int k = 0; k < MASK * MASK; k++) {
			int pixel = this.maskPixels[k];
			if ((pixel & 0xFF00) != 0xFF00) {
				this.maskPixels[k] = pixel & ~0xFF00 | Math.round(this.rememberedShown[k] * 0.4F * 255) << 8;
			}
		}
	}

	/** Cloud levels, and how far from explored land the clouds over never-explored land reach full height, per level in cells. Match fog.fsh. */
	private static final double CLOUD_LEVELS = 5;
	private static final double CLOUD_CELL = 12.8;
	private static final double CELLS_PER_LEVEL = 5;

	/** How much of a remembered map is under each mask texel, 0 to 1, and that drawn, easing toward it. */
	private final float[] remembered = new float[MASK * MASK];
	private final float[] rememberedShown = new float[MASK * MASK];
	private final float[] rememberedMoved = new float[MASK * MASK];
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
			float rememberedDiff = this.remembered[k] - this.rememberedShown[k];
			if (Math.abs(rememberedDiff) <= step * 2) {
				this.rememberedShown[k] = this.remembered[k];
			} else {
				this.rememberedShown[k] += Math.signum(rememberedDiff) * step * 2;
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
		}
		this.writeRemembered();
		this.writeLift();
	}

	/** The drawn heights into the mask's alpha. */
	private void writeLift() {
		for (int k = 0; k < MASK * MASK; k++) {
			this.maskPixels[k] = this.maskPixels[k] & 0xFFFFFF | Math.round(this.liftShown[k] * 255) << 24;
		}
	}

	/** A cloud level's thickness in cells; match fog.fsh. */
	private static final double LEVEL_THICK = 0.9;
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
				boolean plain = !surface || this.liftTarget[k] >= 1 && this.liftShown[k] >= 1 && this.remembered[k] <= 0 && this.rememberedShown[k] <= 0;
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

	/**
	 * The cloud cells the shader can read for a mask: those of the size drawn and, while the zoom
	 * hands over, of twice that, each from {@link #CELL_MARGIN} cells before the mask's first to as
	 * many after its last. The mask's origin is always a whole number of cells from the noise's: ten
	 * of the base size to each eighth of the coarser noise period the origin snaps to.
	 */
	private record CellGrid(double originU, double originV, double texel, int noiseU, int noiseV, double snap, double base, int mult,
		double viewMinU, double viewMinV, double viewMaxU, double viewMaxV) {
		static CellGrid of(View view, double originU, double originV, double texel, int scaleSteps, int fraction) {
			double snap = 64.0 * (1 << (scaleSteps + 1));
			return new CellGrid(originU, originV, texel,
				Math.floorMod((long) (originU / snap), 8), Math.floorMod((long) (originV / snap), 8), snap,
				CLOUD_CELL * (1 << scaleSteps), fraction >= 10 ? 2 : 1,
				view.minU(), view.minV(), view.minU() + view.width() / view.zoom(), view.minV() + view.height() / view.zoom());
		}

		/** Cells of {@code mult} times the base size from the first the shader can read to the mask's far edge and the margin. */
		int count(int mult) {
			return (int) Math.ceil(MASK * this.texel / (this.base * mult)) + 2 * CELL_MARGIN + 1;
		}
	}

	/** Cells read around the mask's edge: the clouds' reach, 2.45 cells and the lean, and one to spare. */
	private static final int CELL_MARGIN = 7;
	/** How often the heights step while they ease. */
	private static final long EASE_MS = 50;
	private @Nullable CellGrid cellGrid;

	/**
	 * Into the detail texture: blue holds the soft known share (writeSoft) for the mask's texels
	 * and, beside it, the signed distance from explored land ({@link #cloudDistances}); alpha, a
	 * texel a cell of the grid drawn, what clouds are within reach ({@link #cloudNear}); and further
	 * down, red, green and blue a texel a cloud cell of each size, its layers ({@link #cloudCells}).
	 * Alpha at (0, 0) says the cells are there: 255, or 170 with the bigger layout. Written and
	 * uploaded whole after each rebuild: the cells don't change while the heights ease.
	 */
	private void writeCells() {
		CellGrid grid = this.cellGrid;
		int span = grid == null ? 0 : grid.count(grid.mult()) * grid.mult();
		boolean cells = grid != null && span <= 2 * CLOUD_SLOT;
		boolean large = cells && span > CLOUD_SLOT;
		int edge = large ? 4 * MASK : 2 * MASK;
		int[] pixels = this.detailPixels;
		for (int j = 0; j < edge; j++) {
			for (int i = 0; i < edge; i++) {
				int blue = 0;
				if (j < MASK && i < MASK) {
					blue = this.softBytes[j * MASK + i] & 0xFF;
				} else if (j < MASK && i < 2 * MASK) {
					// Two map units a step, 128 at explored land's edge.
					blue = Math.max(0, Math.min(255, Math.round(this.signed[j * MASK + i - MASK] / 2) + 128));
				}
				pixels[j * edge + i] = blue << 16;
			}
		}
		if (cells) {
			this.cloudCells(grid, pixels, edge, large);
			pixels[0] = pixels[0] & 0xFFFFFF | (large ? 170 : 255) << 24;
		}
		this.detailArea[0] = 0;
		this.detailArea[1] = 0;
		this.detailArea[2] = edge;
		this.detailUpload = pixels;
	}

	/**
	 * Each size's cells have a block of the detail texture, from row {@link #CLOUD_SLOT} (twice that
	 * with the bigger layout), the smallest size first, each half as wide as the one before: a cell a
	 * texel, the deck, middle and top layers in red, green and blue, 0 or 255. Read filtered, the
	 * shader's four cells round a point blend in one read. Match fog.fsh's cloudRaw.
	 */
	private static final int CLOUD_SLOT = 256;

	private static int slotX(int level, boolean large) {
		int width = large ? 2 * CLOUD_SLOT : CLOUD_SLOT;
		return level == 0 ? 0 : width * 2 - (width >> (level - 1));
	}

	private static int slotWidth(int level, boolean large) {
		return (large ? 2 * CLOUD_SLOT : CLOUD_SLOT) >> level;
	}

	/** Map units from land explored or remembered, positive over land never explored: {@link #cloudDistances}. */
	private final float[] signed = new float[MASK * MASK];
	private final int[] toKnown = new int[MASK * MASK];
	private final float[] signedBlur = new float[MASK * MASK];

	/**
	 * The signed distance from explored land the clouds are placed by. Land remembered from before a
	 * turn counts as explored, so turning the map doesn't move the clouds; the soft cover over the
	 * remembered map while it's redrawn is fog.fsh's, from the eased heights. Blurred a little, so
	 * its contours don't step with the mask's texels. Needs {@link #liftTargets}'s distances.
	 */
	private void cloudDistances(double texel) {
		for (int k = 0; k < MASK * MASK; k++) {
			boolean known = (this.maskPixels[k] & 0xFF) >= 128 || this.remembered[k] >= 0.5F;
			this.toKnown[k] = known ? 0 : Integer.MAX_VALUE / 2;
		}
		chamfer(this.toKnown);
		int far = MASK * 3;
		for (int k = 0; k < MASK * MASK; k++) {
			this.signed[k] = (float) ((Math.min(this.toKnown[k], far) - Math.min(this.toUnexplored[k], far)) / 3.0 * texel);
		}
		for (int pass = 0; pass < 3; pass++) {
			for (int j = 0; j < MASK; j++) {
				for (int i = 0; i < MASK; i++) {
					float sum = 0;
					for (int d = -2; d <= 2; d++) {
						sum += this.signed[j * MASK + Math.max(0, Math.min(MASK - 1, i + d))];
					}
					this.signedBlur[j * MASK + i] = sum / 5;
				}
			}
			for (int j = 0; j < MASK; j++) {
				for (int i = 0; i < MASK; i++) {
					float sum = 0;
					for (int d = -2; d <= 2; d++) {
						sum += this.signedBlur[Math.max(0, Math.min(MASK - 1, j + d)) * MASK + i];
					}
					this.signed[j * MASK + i] = sum / 5;
				}
			}
		}
	}

	/** The signed distance at a point in mask texels, read between texels as the shader reads it. */
	private double signedAt(double x, double y) {
		double fx = x - 0.5;
		double fy = y - 0.5;
		int i0 = (int) Math.floor(fx);
		int j0 = (int) Math.floor(fy);
		double tx = Math.max(0, Math.min(1, fx - i0));
		double ty = Math.max(0, Math.min(1, fy - j0));
		int i1 = Math.min(MASK - 1, Math.max(0, i0 + 1));
		int j1 = Math.min(MASK - 1, Math.max(0, j0 + 1));
		i0 = Math.min(MASK - 1, Math.max(0, i0));
		j0 = Math.min(MASK - 1, Math.max(0, j0));
		return lerp2(this.signed[j0 * MASK + i0], this.signed[j0 * MASK + i1], this.signed[j1 * MASK + i0], this.signed[j1 * MASK + i1], tx, ty);
	}

	/**
	 * The cloud cells around the view, for the grid drawn: each cell's {@link VanillaClouds} code at
	 * four sizes, 1, 2, 4 and 8 times the base cell, into each size's block. A size smaller than the
	 * grid's cell is left out. Then {@link #cloudNear}.
	 */
	private void cloudCells(CellGrid grid, int[] pixels, int edge, boolean large) {
		int mult = grid.mult();
		double size = grid.base() * mult;
		int count = grid.count(mult);
		int steps = (int) Math.round(Math.log(grid.base() / VanillaClouds.CELL) / Math.log(2));
		// The shader's map units start at the noise origin, a whole number of cells from map 0:
		// ten of the base size to each step of it.
		double mapU = grid.noiseU() * grid.snap();
		double mapV = grid.noiseV() * grid.snap();
		int firstU = grid.noiseU() * 10 / mult - CELL_MARGIN;
		int firstV = grid.noiseV() * 10 / mult - CELL_MARGIN;
		// The cells around the view, in the shader's map units, and the reach of the biggest cells'
		// walls and shadows past it.
		int margin = CELL_MARGIN + 24;
		int fromU = Math.max(0, (int) Math.floor((mapU + grid.viewMinU() - grid.originU()) / size) - margin - firstU);
		int toU = Math.min(count - 1, (int) Math.floor((mapU + grid.viewMaxU() - grid.originU()) / size) + margin - firstU);
		int fromV = Math.max(0, (int) Math.floor((mapV + grid.viewMinV() - grid.originV()) / size) - margin - firstV);
		int toV = Math.min(count - 1, (int) Math.floor((mapV + grid.viewMaxV() - grid.originV()) / size) + margin - firstV);
		int slotY = large ? 2 * CLOUD_SLOT : CLOUD_SLOT;
		for (int level = 0; level < 4; level++) {
			int times = 1 << level;
			if (times < mult) {
				continue;
			}
			// Cells of this size, numbered in the shader's map units, from the one holding the grid's first.
			long first0U = Math.floorDiv((long) firstU * mult, times);
			long first0V = Math.floorDiv((long) firstV * mult, times);
			int width = slotWidth(level, large);
			int x0 = slotX(level, large);
			int from0U = (int) (Math.floorDiv((long) (firstU + fromU) * mult, times) - first0U);
			int to0U = Math.min(width - 1, (int) (Math.floorDiv((long) (firstU + toU) * mult + mult - 1, times) - first0U) + 1);
			int from0V = (int) (Math.floorDiv((long) (firstV + fromV) * mult, times) - first0V);
			int to0V = Math.min(width - 1, (int) (Math.floorDiv((long) (firstV + toV) * mult + mult - 1, times) - first0V) + 1);
			double cell = grid.base() * times;
			for (int j = from0V; j <= to0V; j++) {
				long v = first0V + j;
				double y = ((v + 0.5) * cell - mapV) / grid.texel();
				long worldV = v + Math.round((grid.originV() - mapV) / cell);
				for (int i = from0U; i <= to0U; i++) {
					long u = first0U + i;
					double x = ((u + 0.5) * cell - mapU) / grid.texel();
					// The shader's map units wrap with the noise origin; the cell counted from map 0 is
					// a whole number of cells on (the origins differ by whole wraps, 80 base cells each).
					long worldU = u + Math.round((grid.originU() - mapU) / cell);
					int code = VanillaClouds.code(this.signedAt(x, y), worldU, worldV, steps + level);
					int at = (slotY + j) * edge + x0 + i;
					pixels[at] = ((code & VanillaClouds.DECK) != 0 ? 0xFF : 0) | ((code & VanillaClouds.MIDDLE) != 0 ? 0xFF00 : 0)
						| ((code & VanillaClouds.TOP) != 0 ? 0xFF0000 : 0);
				}
			}
		}
		this.cloudNear(pixels, edge, large, fromU, toU, fromV, toV, firstU, firstV, mult);
	}

	/** The code of the cell of size {@code level} holding the grid cell {@code (gu, gv)}, from its block. */
	private static int cellCode(int[] pixels, int edge, boolean large, int level, int gu, int gv, int firstU, int firstV, int mult) {
		int times = 1 << level;
		int i = (int) (Math.floorDiv((long) gu * mult, times) - Math.floorDiv((long) firstU * mult, times));
		int j = (int) (Math.floorDiv((long) gv * mult, times) - Math.floorDiv((long) firstV * mult, times));
		int width = slotWidth(level, large);
		if (i < 0 || j < 0 || i >= width || j >= width) {
			return 0;
		}
		int pixel = pixels[((large ? 2 * CLOUD_SLOT : CLOUD_SLOT) + j) * edge + slotX(level, large) + i];
		return ((pixel & 0xFF) != 0 ? VanillaClouds.DECK : 0) | ((pixel & 0xFF00) != 0 ? VanillaClouds.MIDDLE : 0) | ((pixel & 0xFF0000) != 0 ? VanillaClouds.TOP : 0);
	}

	private int[] nearSmall = new int[0];
	private int[] nearBig = new int[0];
	private int[] nearLine = new int[0];

	/**
	 * Into alpha, for each cell: which layers any cell within reach has, so the shader skips the
	 * rest, and whether every cell within reach is the deck alone, so it draws the deck's plain top
	 * with no reads. The low nibble for the two smaller sizes, the high one for the two bigger: the
	 * three layer bits, then the deck-alone bit. Within reach: a wall, shadow or ground-shadow read
	 * lands under 3.5 cells of the bigger size away.
	 */
	private void cloudNear(int[] pixels, int edge, boolean large, int fromU, int toU, int fromV, int toV, int firstU, int firstV, int mult) {
		int w = toU - fromU + 1;
		int h = toV - fromV + 1;
		if (w <= 0 || h <= 0) {
			return;
		}
		int n = w * h;
		if (this.nearSmall.length < n) {
			this.nearSmall = new int[n];
			this.nearBig = new int[n];
			this.nearLine = new int[n];
		}
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int gu = firstU + fromU + i;
				int gv = firstV + fromV + j;
				int c1 = cellCode(pixels, edge, large, 1, gu, gv, firstU, firstV, mult);
				// With the grid's cells twice the base, the base size isn't drawn.
				int c0 = mult <= 1 ? cellCode(pixels, edge, large, 0, gu, gv, firstU, firstV, mult) : c1;
				int c2 = cellCode(pixels, edge, large, 2, gu, gv, firstU, firstV, mult);
				int c3 = cellCode(pixels, edge, large, 3, gu, gv, firstU, firstV, mult);
				// Bit 3 set where a cell is NOT the deck alone, so ORing finds any such cell within reach.
				this.nearSmall[j * w + i] = c0 | c1 | (c0 == VanillaClouds.DECK && c1 == VanillaClouds.DECK ? 0 : 8);
				this.nearBig[j * w + i] = c2 | c3 | (c2 == VanillaClouds.DECK && c3 == VanillaClouds.DECK ? 0 : 8);
			}
		}
		this.orAround(this.nearSmall, w, h, (int) Math.ceil(3.5 * 2 / mult) + 1);
		this.orAround(this.nearBig, w, h, (int) Math.ceil(3.5 * 8 / mult) + 1);
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int small = this.nearSmall[j * w + i] ^ 8;
				int big = this.nearBig[j * w + i] ^ 8;
				int at = (fromV + j) * edge + fromU + i;
				pixels[at] = pixels[at] & 0x00FFFFFF | (small | big << 4) << 24;
			}
		}
	}

	/** Each value ORed with every value up to {@code r} cells away each way (a square), a row then a column at a time. */
	private void orAround(int[] values, int w, int h, int r) {
		int[] line = this.nearLine;
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int v = 0;
				for (int d = Math.max(0, i - r); d <= Math.min(w - 1, i + r); d++) {
					v |= values[j * w + d];
				}
				line[j * w + i] = v;
			}
		}
		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				int v = 0;
				for (int d = Math.max(0, j - r); d <= Math.min(h - 1, j + r); d++) {
					v |= line[d * w + i];
				}
				values[j * w + i] = v;
			}
		}
	}

	private static double lerp2(double a, double b, double c, double d, double tx, double ty) {
		double top = a + (b - a) * tx;
		return top + (c + (d - c) * tx - top) * ty;
	}


	private final float[] knownShare = new float[MASK * MASK];
	private final int[] notFullCounts = new int[(MASK + 1) * (MASK + 1)];
	private final int[] notEmptyCounts = new int[(MASK + 1) * (MASK + 1)];

	/**
	 * The known share as fog.fsh's knownSoft reads it, at each texel's middle, into the detail
	 * texture's red: one blur of nine taps (the middle, four at the radius, four on the diagonals at
	 * 1.9 times it, each read filtered between texels and clamped at the mask's edge) or, for the
	 * wide surface fog, half of that and half of the same at 2.4 times the radius. Filtered between
	 * texels the shader then reads the same as it would have worked out, to well under a step of the
	 * mask's bytes. Texels whose whole blur reach is all known or all unknown are just that.
	 */
	private void writeSoft(double texel, boolean cave, boolean fast) {
		double units = cave ? 2.5 : 7;
		boolean wide = units >= 5 && !fast;
		double near = Math.max(units / texel, 1);
		double far = Math.max(units * 2.4 / texel, 1);
		int reach = (int) Math.ceil(1.9 * (wide ? far : near)) + 2;
		int row = MASK + 1;
		for (int j = 0; j < MASK; j++) {
			int lineNotFull = 0;
			int lineNotEmpty = 0;
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int known = this.maskPixels[k] & 0xFF;
				this.knownShare[k] = known / 255.0F;
				lineNotFull += known == 0xFF ? 0 : 1;
				lineNotEmpty += known == 0 ? 0 : 1;
				this.notFullCounts[(j + 1) * row + i + 1] = this.notFullCounts[j * row + i + 1] + lineNotFull;
				this.notEmptyCounts[(j + 1) * row + i + 1] = this.notEmptyCounts[j * row + i + 1] + lineNotEmpty;
			}
		}
		for (int j = 0; j < MASK; j++) {
			int t = Math.max(0, j - reach);
			int b = Math.min(MASK - 1, j + reach) + 1;
			for (int i = 0; i < MASK; i++) {
				int l = Math.max(0, i - reach);
				int r = Math.min(MASK - 1, i + reach) + 1;
				float soft;
				if (this.notFullCounts[b * row + r] - this.notFullCounts[t * row + r] - this.notFullCounts[b * row + l] + this.notFullCounts[t * row + l] == 0) {
					soft = 1;
				} else if (this.notEmptyCounts[b * row + r] - this.notEmptyCounts[t * row + r] - this.notEmptyCounts[b * row + l] + this.notEmptyCounts[t * row + l] == 0) {
					soft = 0;
				} else {
					soft = this.blurAt(i + 0.5, j + 0.5, near);
					if (wide) {
						soft = 0.5F * soft + 0.5F * this.blurAt(i + 0.5, j + 0.5, far);
					}
				}
				this.softBytes[j * MASK + i] = (byte) Math.round(soft * 255);
			}
		}
	}

	/** The known share blurred over {@code r} texels around (x, y), in texels, so chunk-shaped edges melt away. */
	private float blurAt(double x, double y, double r) {
		double d = r * 1.9;
		return this.knownAt(x, y) * 0.2F
			+ (this.knownAt(x + r, y) + this.knownAt(x - r, y) + this.knownAt(x, y + r) + this.knownAt(x, y - r)) * 0.1F
			+ (this.knownAt(x + d, y + d) + this.knownAt(x - d, y + d) + this.knownAt(x + d, y - d) + this.knownAt(x - d, y - d)) * 0.1F;
	}

	/** The known share at (x, y) in texels, filtered between texel middles and clamped at the edge, as the graphics card reads it. */
	private float knownAt(double x, double y) {
		double fx = x - 0.5;
		double fy = y - 0.5;
		int i0 = (int) Math.floor(fx);
		int j0 = (int) Math.floor(fy);
		float tx = (float) (fx - i0);
		float ty = (float) (fy - j0);
		int i1 = Math.min(MASK - 1, Math.max(0, i0 + 1));
		int j1 = Math.min(MASK - 1, Math.max(0, j0 + 1));
		i0 = Math.min(MASK - 1, Math.max(0, i0));
		j0 = Math.min(MASK - 1, Math.max(0, j0));
		float top = this.knownShare[j0 * MASK + i0] + (this.knownShare[j0 * MASK + i1] - this.knownShare[j0 * MASK + i0]) * tx;
		float bottom = this.knownShare[j1 * MASK + i0] + (this.knownShare[j1 * MASK + i1] - this.knownShare[j1 * MASK + i0]) * tx;
		return top + (bottom - top) * ty;
	}

	private static int index(int line, int k, boolean horizontal) {
		return horizontal ? line * MASK + k : k * MASK + line;
	}

	public void close() {
		if (this.finishing != null) {
			// Its arrays are the next build's.
			this.finishing.join();
			this.finishing = null;
		}
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		if (this.detailTexture != null) {
			this.detailTexture.close();
			this.detailTexture = null;
		}
		if (this.fogLayer != null) {
			this.fogLayer.close();
			this.fogLayer = null;
		}
		this.layerKey = null;
		this.maskInputs = null;
	}
}
