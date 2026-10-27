package dev.diorama;

import dev.diorama.bake.TileKey;
import dev.diorama.render.Circle;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Markers;
import dev.diorama.render.Radar;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
//? if >=1.21 {
import net.minecraft.client.DeltaTracker;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

/**
 * The minimap, centred on the player, in the corner the settings pick, square or round. Compass
 * letters sit on its edges, and coordinates and the biome sit on the side away from the screen edge.
 */
public final class MinimapHud implements HudElement {
	private static final int MARGIN = 6;
	/** Half the hotbar's width; hearts and hunger sit in the same columns just above it. */
	private static final int HOTBAR_HALF_WIDTH = 91;
	private static final int SMALLEST = 64;
	/**
	 * Longest the minimap keeps showing the old slice while the new one bakes. A tile takes about
	 * half a second to bake and a new slice can need four at once, so this leaves room for that.
	 */
	private static final long LAYER_HOLD_MS = 3000;

	private final MapViewport viewport = new MapViewport();
	/** The slice on screen. Moving into another slice keeps this one until the next has baked. */
	private int shownLayer;
	private boolean showing;
	/** When the minimap started holding the old slice, or -1 when it isn't. */
	private long holdStart = -1;

	@Override
	//? if >=1.21 {
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
	//?} else {
	/*public void extractRenderState(GuiGraphicsExtractor graphics, float partialTick) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		Settings settings = Settings.get();
		//? if >=26.2 {
		if (!settings.minimap || mc.gui.hud.isHidden()
		//?} else {
		/*if (!settings.minimap || mc.options.hideGui
		*///?}
			|| mc.gui.screen() instanceof MapScreen || mc.gui.screen() instanceof AbstractContainerScreen<?>
			|| mc.gui.screen() instanceof SettingsScreen || debugScreenOpen(mc) || !this.canDraw(mc)) {
			// Not drawn. Inventories and chests count, because the item lists other mods (JEI, REI,
			// EMI) put beside them run into the minimap's corner. The settings screen draws its own
			// copy, sharp over the blur, as a preview. The F3 screen fills both top corners with text
			// people open it to read.
			// A hold picks up afresh when it's drawn again, rather than having run out meanwhile.
			this.holdStart = -1;
			return;
		}
		Settings.Corner corner = settings.minimapCorner;
		boolean right = corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.BOTTOM_RIGHT;
		boolean top = corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.TOP_LEFT;
		this.draw(graphics, partialTick, right, top, top ? this.effectRows(mc.player, right) : 0);
	}

	/**
	 * The settings screen's live preview: the minimap as the settings make it, on the right of the
	 * screen where the settings leave room. A left corner shows on the right at the same height.
	 * Switched off, it still draws, dimmed, so the other minimap settings can be seen.
	 */
	public void preview(GuiGraphicsExtractor graphics, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (!this.canDraw(mc)) {
			return;
		}
		Settings settings = Settings.get();
		boolean top = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.TOP_LEFT;
		int[] bounds = this.draw(graphics, partialTick, true, top, 0);
		if (!settings.minimap) {
			//? if <1.21.6 {
			/*// Text sits a little above 0 and the GUI depth-tests, so lift the dimming over it.
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 1);
			*///?}
			if (settings.minimapShape == Settings.Shape.ROUND) {
				// The disc with its frame, then the lines under it, so the corners beside it stay undimmed.
				MapCanvas.of(graphics).solid(new Circle(bounds[4] + bounds[6] / 2.0F, bounds[5] + bounds[6] / 2.0F, bounds[6] / 2.0F + 2).disc(), 0xB0101418);
				if (bounds[3] > bounds[5] + bounds[6] + 2) {
					graphics.fill(bounds[0], bounds[5] + bounds[6] + 2, bounds[2], bounds[3], 0xB0101418);
				}
			} else {
				graphics.fill(bounds[0], bounds[1], bounds[2], bounds[3], 0xB0101418);
			}
			//? if <1.21.6 {
			/*pose.popPose();
			*///?}
		}
	}

	/** Frees the GPU texture the minimap's fog uses. For a copy that's done drawing, like the settings preview. */
	public void close() {
		this.viewport.close();
	}

	/**
	 * Whether F3 is showing the debug screen. From 1.21.9 single lines can be set to show without
	 * it, like the FPS; those alone don't hide the minimap.
	 */
	private static boolean debugScreenOpen(Minecraft mc) {
		//? if >=1.21.9 {
		return mc.debugEntries.isOverlayVisible();
		//?} else if >=1.21 {
		/*return mc.getDebugOverlay().showDebugScreen();
		*///?} else {
		/*return mc.options.renderDebug;
		*///?}
	}

	private boolean canDraw(Minecraft mc) {
		return MapSession.get() != null && mc.player != null && mc.level != null && ServerRules.minimapAllowed();
	}

	/**
	 * Draws the minimap in a corner, {@code effects} pixels lower for status effect icons. Returns
	 * what it covers, x0, y0, x1, y1, then the map's own x, y and size.
	 */
	private int[] draw(GuiGraphicsExtractor graphics, float partialTick, boolean right, boolean top, int effects) {
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		LocalPlayer player = mc.player;
		Settings settings = Settings.get();
		Vec3 pos = player.getPosition(partialTick);
		Font font = mc.font;
		List<String> info = this.infoLines(settings, player, mc);
		int infoHeight = info.size() * 10;
		int size = this.fit(settings.minimapSize, graphics.guiWidth(), graphics.guiHeight(), top, effects, infoHeight);
		int x = right ? graphics.guiWidth() - size - MARGIN : MARGIN;
		int y = top ? MARGIN + effects : graphics.guiHeight() - size - MARGIN - infoHeight;
		int wantedLayer = settings.autoCaves ? session.autoLayer(player) : TileKey.SURFACE;
		int layer = this.layerToShow(session.map(), new MapViewport.View(x, y, size, size, pos.x, Projection.v(pos.y, pos.z), settings.zoom(), wantedLayer));
		boolean round = settings.minimapShape == Settings.Shape.ROUND;

		if (round) {
			this.roundFrame(graphics, settings.minimapFrame, x, y, size);
		} else {
			this.frame(graphics, settings.minimapFrame, x, y, size);
		}
		MapViewport.View view = new MapViewport.View(x, y, size, size, pos.x, Projection.v(pos.y, pos.z), settings.zoom(), layer, round);
		this.viewport.draw(graphics, session.map(), view, 0, Settings.get().claims == Settings.ClaimsShown.ALWAYS);
		List<Radar.Blip> blips = ServerRules.radarAllowed() && settings.radarOnMinimap ? Radar.collect(view, partialTick, false) : List.of();
		Radar.draw(graphics, view, blips, false, false);
		WaypointStore store = WaypointStore.get();
		if (store != null && settings.waypointsOnMap) {
			WaypointOverlay.draw(graphics, view, WaypointOverlay.layout(view, store.in(mc.level.dimension().identifier().toString()), false), false, null);
		}
		Radar.draw(graphics, view, blips, false, true);
		Markers.player(graphics, x + size / 2.0F, y + size / 2.0F, player.getViewYRot(partialTick), 5);
		if (settings.compass) {
			this.compass(graphics, font, x, y, size);
		}
		int textY = y + size + 4;
		int left = x - 2;
		int textRight = x + size + 2;
		for (String line : info) {
			int w = font.width(line);
			int tx = right ? x + size - w : x;
			graphics.text(font, line, tx, textY, 0xFFFFFFFF, true);
			left = Math.min(left, tx - 1);
			textRight = Math.max(textRight, tx + w + 1);
			textY += 10;
		}
		return new int[] {left, y - 2, textRight, info.isEmpty() ? y + size + 2 : textY, x, y, size};
	}

	/**
	 * The slice to draw for a view of the slice the player is in: that one once its tiles have
	 * baked, the one already on screen until then, so climbing past a slice edge doesn't flash
	 * the minimap blank.
	 */
	private int layerToShow(MapLevel level, MapViewport.View wanted) {
		long now = System.currentTimeMillis();
		if (!this.showing || wanted.layer() == this.shownLayer) {
			this.showing = true;
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
			return this.shownLayer;
		}
		if (this.holdStart < 0) {
			this.holdStart = now;
		}
		MapViewport.View shown = new MapViewport.View(wanted.x(), wanted.y(), wanted.width(), wanted.height(),
			wanted.centerU(), wanted.centerV(), wanted.zoom(), this.shownLayer);
		// The old slice only helps if it has something to show: after a teleport or a new dimension it doesn't.
		if (MapViewport.ready(level, wanted) || now - this.holdStart > LAYER_HOLD_MS || !MapViewport.showsAnything(level, shown)) {
			this.shownLayer = wanted.layer();
			this.holdStart = -1;
		}
		return this.shownLayer;
	}

	/**
	 * Shrinks the minimap so it and its info lines stay on screen and, in a bottom corner, beside
	 * the hotbar instead of over the hearts or hunger bar.
	 */
	private int fit(int size, int width, int height, boolean top, int effects, int infoHeight) {
		int fitted = size;
		if (top) {
			fitted = Math.min(fitted, height - MARGIN - effects - infoHeight - MARGIN - 4);
		} else {
			fitted = Math.min(fitted, width / 2 - HOTBAR_HALF_WIDTH - 2 * MARGIN - 4);
			fitted = Math.min(fitted, height - infoHeight - 2 * MARGIN);
		}
		return Math.max(SMALLEST, Math.min(size, fitted));
	}

	/**
	 * The border around the map: the classic dark frame with a faint light line inside it, or the
	 * Minecraft edge, a black line with its corner pixels cut and a light grey line inside it.
	 */
	private void frame(GuiGraphicsExtractor graphics, Settings.Frame frame, int x, int y, int size) {
		int x1 = x + size;
		int y1 = y + size;
		switch (frame) {
			case CLASSIC -> {
				graphics.fill(x - 2, y - 2, x1 + 2, y1 + 2, 0xC0101418);
				graphics.fill(x - 1, y - 1, x1 + 1, y1 + 1, 0x60FFFFFF);
			}
			case MINECRAFT -> {
				graphics.fill(x - 2, y - 1, x1 + 2, y1 + 1, 0xFF000000);
				graphics.fill(x - 1, y - 2, x1 + 1, y1 + 2, 0xFF000000);
				graphics.fill(x - 1, y - 1, x1 + 1, y1 + 1, 0xFFA0A0A0);
			}
			case NONE -> {
			}
		}
	}

	/**
	 * The same borders around a round minimap: discs the map then covers all but the rim of. The
	 * Minecraft edge's cut corners have nothing to cut on a circle, so it's a black ring around a
	 * light grey one.
	 */
	private void roundFrame(GuiGraphicsExtractor graphics, Settings.Frame frame, int x, int y, int size) {
		if (frame == Settings.Frame.NONE) {
			return;
		}
		MapCanvas canvas = MapCanvas.of(graphics);
		float cx = x + size / 2.0F;
		float cy = y + size / 2.0F;
		boolean classic = frame == Settings.Frame.CLASSIC;
		canvas.solid(new Circle(cx, cy, size / 2.0F + 2).disc(), classic ? 0xC0101418 : 0xFF000000);
		canvas.solid(new Circle(cx, cy, size / 2.0F + 1).disc(), classic ? 0x60FFFFFF : 0xFFA0A0A0);
	}

	/** Letters on the frame. The map always faces north, so they never move. */
	private void compass(GuiGraphicsExtractor graphics, Font font, int x, int y, int size) {
		this.letter(graphics, font, "N", x + size / 2, y + 1, 0xFFFF6B6B);
		this.letter(graphics, font, "S", x + size / 2, y + size - 9, 0xFFFFFFFF);
		this.letter(graphics, font, "W", x + 5, y + size / 2 - 4, 0xFFFFFFFF);
		this.letter(graphics, font, "E", x + size - 5, y + size / 2 - 4, 0xFFFFFFFF);
	}

	private void letter(GuiGraphicsExtractor graphics, Font font, String letter, int cx, int y, int color) {
		int w = font.width(letter);
		graphics.fill(cx - w / 2 - 2, y - 1, cx - w / 2 + w + 1, y + 8, 0xA0101418);
		graphics.text(font, letter, cx - w / 2, y, color, false);
	}

	private List<String> infoLines(Settings settings, LocalPlayer player, Minecraft mc) {
		List<String> lines = new ArrayList<>();
		BlockPos pos = player.blockPosition();
		if (settings.coordinates) {
			lines.add(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		}
		if (settings.biome) {
			mc.level.getBiome(pos).unwrapKey().ifPresent(key ->
				lines.add(Component.translatable(key.identifier().toLanguageKey("biome")).getString()));
		}
		return lines;
	}

	/** Vanilla draws status effect icons in the top-right corner; move the minimap below them. */
	private int effectRows(LocalPlayer player, boolean right) {
		if (!right) {
			return 0;
		}
		boolean beneficial = false;
		boolean harmful = false;
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				beneficial = true;
			} else {
				harmful = true;
			}
		}
		return harmful ? 52 : beneficial ? 26 : 0;
	}
}
