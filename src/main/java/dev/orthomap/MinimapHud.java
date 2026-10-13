package dev.orthomap;

import dev.orthomap.bake.TileKey;
import dev.orthomap.render.MapViewport;
import dev.orthomap.render.Markers;
import dev.orthomap.render.Radar;
import dev.orthomap.waypoint.WaypointOverlay;
import dev.orthomap.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

/**
 * The minimap, centred on the player, in the corner the settings pick. Compass letters sit on its
 * edges, and coordinates and the biome sit on the side away from the screen edge.
 */
public final class MinimapHud implements HudElement {
	private static final int MARGIN = 6;

	private final MapViewport viewport = new MapViewport();

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		LocalPlayer player = mc.player;
		Settings settings = Settings.get();
		if (session == null || player == null || mc.level == null || !settings.minimap || mc.gui.hud.isHidden()
			|| mc.gui.screen() instanceof MapScreen || !ServerRules.minimapAllowed()) {
			return;
		}
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		Vec3 pos = player.getPosition(partialTick);
		int size = settings.minimapSize.pixels;
		Font font = mc.font;
		List<String> info = this.infoLines(settings, player, mc);
		int infoHeight = info.size() * 10;
		boolean right = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.BOTTOM_RIGHT;
		boolean top = settings.minimapCorner == Settings.Corner.TOP_RIGHT || settings.minimapCorner == Settings.Corner.TOP_LEFT;
		int x = right ? graphics.guiWidth() - size - MARGIN : MARGIN;
		int y = top ? MARGIN + this.effectRows(player, right) : graphics.guiHeight() - size - MARGIN - infoHeight;
		int layer = settings.autoCaves ? session.autoLayer(player) : TileKey.SURFACE;

		graphics.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xC0101418);
		graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x60FFFFFF);
		MapViewport.View view = new MapViewport.View(x, y, size, size, pos.x, Projection.v(pos.y, pos.z), settings.zoom(), layer);
		this.viewport.draw(graphics, session.map(), view);
		if (ServerRules.radarAllowed()) {
			Radar.draw(graphics, view, Radar.collect(view, partialTick, false), false);
		}
		WaypointStore store = WaypointStore.get();
		if (store != null && settings.waypointsOnMap) {
			WaypointOverlay.draw(graphics, view, WaypointOverlay.layout(view, store.in(mc.level.dimension().identifier().toString()), false), false, null);
		}
		Markers.player(graphics, x + size / 2.0F, y + size / 2.0F, player.getViewYRot(partialTick), 5);
		if (settings.compass) {
			this.compass(graphics, font, x, y, size);
		}
		int textY = y + size + 4;
		for (String line : info) {
			int w = font.width(line);
			int tx = right ? x + size - w : x;
			graphics.text(font, line, tx, textY, 0xFFFFFFFF, true);
			textY += 10;
		}
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
