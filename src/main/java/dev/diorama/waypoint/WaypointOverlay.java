package dev.diorama.waypoint;

import dev.diorama.Projection;
import dev.diorama.render.MapIcons;
import dev.diorama.render.MapViewport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints on the minimap and fullscreen map: a coloured disc with the waypoint's initials.
 * Waypoints outside the view sit on its edge with an arrow pointing toward them, so the minimap
 * always shows which way home is.
 */
public final class WaypointOverlay {
	/** A waypoint placed on screen; {@code clamped} when it was pulled in to the view's edge. */
	public record Pin(Waypoint waypoint, float x, float y, float size, boolean clamped) {
	}

	private WaypointOverlay() {
	}

	public static List<Pin> layout(MapViewport.View view, List<Waypoint> waypoints, boolean fullscreen) {
		List<Pin> pins = new ArrayList<>();
		float size = fullscreen ? 14 : 10;
		float inset = size / 2 + 3;
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		float halfW = view.width() / 2.0F - inset;
		float halfH = view.height() / 2.0F - inset;
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.visible) {
				continue;
			}
			float x = view.screenX(waypoint.x + 0.5);
			float y = view.screenY(Projection.v(waypoint.y, waypoint.z + 0.5));
			float dx = x - cx;
			float dy = y - cy;
			float reach = Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
			if (reach > 1) {
				pins.add(new Pin(waypoint, cx + dx / reach, cy + dy / reach, size * 0.85F, true));
			} else {
				pins.add(new Pin(waypoint, x, y, size, false));
			}
		}
		return pins;
	}

	public static void draw(GuiGraphicsExtractor graphics, MapViewport.View view, List<Pin> pins, boolean fullscreen, @Nullable Pin hovered) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		for (Pin pin : pins) {
			Waypoint waypoint = pin.waypoint();
			float s = pin.size();
			if (pin.clamped()) {
				double angle = Math.atan2(pin.y() - cy, pin.x() - cx);
				float ax = (float) (pin.x() + Math.cos(angle) * s * 0.62);
				float ay = (float) (pin.y() + Math.sin(angle) * s * 0.62);
				MapIcons.arrow(graphics, ax, ay, s * 0.55F, angle, 0xE0101418, 1.6F);
				MapIcons.arrow(graphics, ax, ay, s * 0.55F, angle, waypoint.color, 0);
			}
			MapIcons.dot(graphics, pin.x(), pin.y(), s + 2.5F, 0xF0101418);
			MapIcons.dot(graphics, pin.x(), pin.y(), s, waypoint.color);
			String label = waypoint.kind == Waypoint.Kind.DEATH ? "✝" : waypoint.label();
			float textScale = Math.min(1, (s - 3) / Math.max(5, font.width(label)));
			graphics.pose().pushMatrix();
			graphics.pose().translate(pin.x(), pin.y() - 3.5F * textScale);
			graphics.pose().scale(textScale);
			graphics.centeredText(font, label, 0, 0, textColour(waypoint.color));
			graphics.pose().popMatrix();
			if (fullscreen && !pin.clamped() && (pin == hovered || view.zoom() >= 2)) {
				int w = font.width(waypoint.name);
				int tx = Math.round(pin.x() - w / 2.0F);
				int ty = Math.round(pin.y() + s / 2 + 3);
				graphics.fill(tx - 2, ty - 1, tx + w + 2, ty + 9, 0x90000000);
				graphics.text(font, waypoint.name, tx, ty, 0xFFFFFFFF, false);
			}
		}
		graphics.disableScissor();
	}

	public static @Nullable Pin under(List<Pin> pins, double mouseX, double mouseY) {
		Pin best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Pin pin : pins) {
			double d = Math.hypot(pin.x() - mouseX, pin.y() - mouseY);
			if (d <= pin.size() / 2 + 2 && d < bestDistance) {
				best = pin;
				bestDistance = d;
			}
		}
		return best;
	}

	/** "Home · 120 m · 4 below" */
	public static Component describe(Waypoint waypoint, Player viewer) {
		int distance = (int) Math.round(waypoint.distanceTo(viewer.getX(), viewer.getY(), viewer.getZ()));
		int dy = (int) Math.round(waypoint.y - viewer.getY());
		String height = dy > 2 ? " · " + dy + " above" : dy < -2 ? " · " + (-dy) + " below" : "";
		return Component.literal(waypoint.name + " · " + distance + " m" + height);
	}

	/** Dark letters on light colours, white on dark ones. */
	static int textColour(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		return 0.299 * r + 0.587 * g + 0.114 * b > 160 ? 0xFF101418 : 0xFFFFFFFF;
	}
}
