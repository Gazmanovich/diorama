package dev.diorama.waypoint;

import dev.diorama.Projection;
import dev.diorama.render.Pins;
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
 * Waypoints on the minimap and fullscreen map: a pin in the waypoint's colour standing with its
 * point on the spot. Waypoints outside the view sit on its edge as a badge with a caret pointing
 * toward them, so the minimap always shows which way home is.
 */
public final class WaypointOverlay {
	/**
	 * A waypoint placed on screen: the spot it marks, or for {@code clamped} ones pulled in to the
	 * view's edge, the badge's centre. {@code size} is the head's diameter.
	 */
	public record Pin(Waypoint waypoint, float x, float y, float size, boolean clamped) {
		/** Where the head is centred: above the spot for a standing pin. */
		public float headY() {
			return this.clamped ? this.y : this.y - Pins.headAbove((int) this.size);
		}
	}

	private WaypointOverlay() {
	}

	public static List<Pin> layout(MapViewport.View view, List<Waypoint> waypoints, boolean fullscreen) {
		List<Pin> pins = new ArrayList<>();
		int size = fullscreen ? Pins.LARGE : Pins.SMALL;
		float inset = Pins.edgeReach(size);
		float cx = view.x() + view.width() / 2.0F;
		float cy = view.y() + view.height() / 2.0F;
		// At least a pixel, so a view smaller than a badge still gets finite positions.
		float halfW = Math.max(1, view.width() / 2.0F - inset);
		float halfH = Math.max(1, view.height() / 2.0F - inset);
		for (Waypoint waypoint : waypoints) {
			if (!WaypointStore.isShown(waypoint)) {
				continue;
			}
			float x = view.screenX(waypoint.x + 0.5);
			float y = view.screenY(Projection.v(waypoint.y, waypoint.z + 0.5));
			float dx = x - cx;
			float dy = y - cy;
			if (Pins.fitsStanding(size, x, y, view.x(), view.y(), view.x() + view.width(), view.y() + view.height())) {
				pins.add(new Pin(waypoint, x, y, size, false));
			} else {
				// Too near the edge for the whole pin to show: a badge pointing the way, pulled in to
				// the view's edge when the spot is outside it.
				float reach = Math.max(1, Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH));
				pins.add(new Pin(waypoint, cx + dx / reach, cy + dy / reach, size, true));
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
			int s = (int) pin.size();
			if (pin.clamped()) {
				Pins.edge(graphics, waypoint, pin.x(), pin.y(), s, Math.atan2(pin.y() - cy, pin.x() - cx));
			} else {
				Pins.standing(graphics, waypoint, pin.x(), pin.y(), s);
			}
			if (fullscreen && !pin.clamped() && (pin == hovered || view.zoom() >= 2)) {
				int w = font.width(waypoint.name);
				int tx = Math.round(pin.x() - w / 2.0F);
				int ty = Math.round(pin.y()) + 3;
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
			// A standing pin answers at its head and at the spot its point touches.
			double d = Math.hypot(pin.x() - mouseX, pin.headY() - mouseY);
			if (!pin.clamped()) {
				d = Math.min(d, Math.hypot(pin.x() - mouseX, pin.y() - mouseY));
			}
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
}
