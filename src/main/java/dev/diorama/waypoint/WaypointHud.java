package dev.diorama.waypoint;

import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.render.MapIcons;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Waypoints in the world: each one is projected through the game camera and drawn on the HUD as
 * its disc with the distance, so it shows through terrain at any range. The name appears when it
 * is near the crosshair or close by, which keeps a screen full of waypoints readable.
 */
public final class WaypointHud implements HudElement {
	/** Within this many GUI pixels of the crosshair a waypoint shows its name. */
	private static final float NAME_RADIUS = 40;
	/** Within this many blocks a waypoint always shows its name. */
	private static final double NAME_DISTANCE = 48;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		WaypointStore store = WaypointStore.get();
		if (player == null || mc.level == null || store == null || !Settings.get().waypointsInWorld || mc.gui.hud.isHidden() || !ServerRules.minimapAllowed()) {
			return;
		}
		Vec3 eye = WorldProjection.camera();
		if (eye == null) {
			return;
		}
		float width = graphics.guiWidth();
		float height = graphics.guiHeight();
		Font font = mc.font;
		List<Waypoint> waypoints = store.in(mc.level.dimension().identifier().toString());
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.visible) {
				continue;
			}
			double dx = waypoint.x + 0.5 - eye.x;
			double dy = waypoint.y + 1.0 - eye.y;
			double dz = waypoint.z + 0.5 - eye.z;
			float[] screen = WorldProjection.project(waypoint.x + 0.5, waypoint.y + 1.0, waypoint.z + 0.5, width, height);
			if (screen == null) {
				continue;
			}
			float sx = screen[0];
			float sy = screen[1];
			if (sx < -20 || sy < -20 || sx > width + 20 || sy > height + 20) {
				continue;
			}
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			float size = 11;
			MapIcons.dot(graphics, sx, sy, size + 2.5F, 0xD0101418);
			MapIcons.dot(graphics, sx, sy, size, waypoint.color);
			String label = waypoint.kind == Waypoint.Kind.DEATH ? "✝" : waypoint.label();
			graphics.pose().pushMatrix();
			graphics.pose().translate(sx, sy - 3);
			graphics.pose().scale(0.75F);
			graphics.centeredText(font, label, 0, 0, WaypointOverlay.textColour(waypoint.color));
			graphics.pose().popMatrix();
			String text = Math.round(distance) + " m";
			boolean nearCrosshair = Math.hypot(sx - width / 2, sy - height / 2) < NAME_RADIUS;
			if (nearCrosshair || distance < NAME_DISTANCE) {
				text = waypoint.name + " · " + text;
			}
			int w = font.width(text);
			int tx = Math.round(sx - w / 2.0F);
			int ty = Math.round(sy + size / 2 + 3);
			graphics.fill(tx - 2, ty - 1, tx + w + 2, ty + 9, 0x80000000);
			graphics.text(font, text, tx, ty, 0xFFFFFFFF, false);
		}
	}
}
