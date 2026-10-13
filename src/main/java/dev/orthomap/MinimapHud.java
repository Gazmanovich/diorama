package dev.orthomap;

import dev.orthomap.render.MapViewport;
import dev.orthomap.render.Markers;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** The minimap in the top-right corner, centred on the player. */
public final class MinimapHud implements HudElement {
	private static final int SIZE = 128;
	private static final int MARGIN = 6;
	/** GUI pixels per map unit. */
	private static final double ZOOM = 2.0;

	private final MapViewport viewport = new MapViewport();

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		MapSession session = MapSession.get();
		LocalPlayer player = mc.player;
		if (session == null || player == null || mc.gui.hud.isHidden() || mc.gui.screen() instanceof MapScreen) {
			return;
		}
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		Vec3 pos = player.getPosition(partialTick);
		double centerU = pos.x;
		double centerV = Projection.v(pos.y, pos.z);
		int x = graphics.guiWidth() - SIZE - MARGIN;
		int y = MARGIN;
		int layer = session.autoLayer(player);

		graphics.fill(x - 2, y - 2, x + SIZE + 2, y + SIZE + 2, 0xC0101418);
		graphics.fill(x - 1, y - 1, x + SIZE + 1, y + SIZE + 1, 0x60FFFFFF);
		this.viewport.draw(graphics, session.map(), new MapViewport.View(x, y, SIZE, SIZE, centerU, centerV, ZOOM, layer));
		Markers.player(graphics, x + SIZE / 2.0F, y + SIZE / 2.0F, player.getViewYRot(partialTick), 5);
	}
}
