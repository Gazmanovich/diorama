package dev.diorama.render;

import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import dev.diorama.waypoint.Waypoint;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws waypoint pins from {@link PinArt}: the pin's body is a small texture,
 * its initials are the game's own text drawn on top. Bodies are made once per shape and colour.
 */
public final class Pins {
	/** Head sizes: the map and the world use the large pin, the minimap the small one. */
	public static final int LARGE = 15;
	public static final int SMALL = 13;
	/** Directions a caret at the edge of a view can point, around the full turn. */
	private static final int CARET_STEPS = 32;
	private static final Map<String, Sprite> SPRITES = new HashMap<>();

	private record Sprite(MapTexture texture, int edge, PinArt.Art art) {
	}

	private Pins() {
	}

	/** A pin standing with its point on (x, y), the spot it marks. */
	public static void standing(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int size) {
		int head = PinArt.standingHead(size);
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		Sprite sprite = sprite("pin " + head + " " + rgb(waypoint) + " " + death, () -> PinArt.standing(rgb(waypoint), death, head));
		draw(graphics, sprite, waypoint, x, y, head);
	}

	/** A round badge centred on (x, y): the waypoint list's rows. */
	public static void badge(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int head) {
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		Sprite sprite = sprite("badge " + head + " " + rgb(waypoint) + " " + death, () -> PinArt.badge(rgb(waypoint), death, head, Double.NaN, 0));
		draw(graphics, sprite, waypoint, x, y, head);
	}

	/**
	 * A badge centred on (x, y) with a caret beside it pointing along {@code angle} (radians, screen
	 * space, 0 is right): a waypoint pulled in to the edge of a view, showing which way it lies.
	 */
	public static void edge(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int head, double angle) {
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		int step = Math.floorMod(Math.round((float) (angle / (Math.PI * 2) * CARET_STEPS)), CARET_STEPS);
		double snapped = step * Math.PI * 2 / CARET_STEPS;
		int caret = head >= LARGE ? 6 : 5;
		Sprite sprite = sprite("edge " + head + " " + rgb(waypoint) + " " + death + " " + step,
			() -> PinArt.badge(rgb(waypoint), death, head, snapped, caret));
		draw(graphics, sprite, waypoint, x, y, head);
	}

	/** How far above the marked spot a standing pin's head is centred, in GUI pixels. */
	public static float headAbove(int size) {
		int head = PinArt.standingHead(size);
		return (head == SMALL ? 5 : 6) + head / 2.0F;
	}

	/** Whether a standing pin marking (x, y) shows whole inside the box x0..x1, y0..y1: its head above, its shadow below. */
	public static boolean fitsStanding(int size, float x, float y, float x0, float y0, float x1, float y1) {
		float[] box = standingBox(size, x, y);
		return box[0] >= x0 && box[2] <= x1 && box[1] >= y0 && box[3] <= y1;
	}

	/** Whether a standing pin with its point at (x, y) is wholly inside a circle. */
	public static boolean fitsStanding(int size, float x, float y, Circle circle) {
		float[] box = standingBox(size, x, y);
		return circle.contains(box[0], box[1]) && circle.contains(box[2], box[1]) && circle.contains(box[0], box[3]) && circle.contains(box[2], box[3]);
	}

	/** What a standing pin with its point at (x, y) covers: x0, y0, x1, y1. */
	private static float[] standingBox(int size, float x, float y) {
		int head = PinArt.standingHead(size);
		float above = head + (head == SMALL ? 5 : 6);
		float side = (head + 4) / 2.0F;
		return new float[] {x - side, y - above, x + side, y + 3};
	}

	/** How far a badge with a caret reaches from its centre, so a view can keep it inside. */
	public static float edgeReach(int head) {
		return head / 2.0F + 6;
	}

	private static int rgb(Waypoint waypoint) {
		return waypoint.color & 0xFFFFFF;
	}

	private static Sprite sprite(String key, java.util.function.Supplier<PinArt.Art> art) {
		Sprite sprite = SPRITES.get(key);
		if (sprite == null) {
			PinArt.Art made = art.get();
			int edge = Math.max(made.image().width, made.image().height);
			MapTexture texture = MapTexture.create("diorama " + key, edge, 1);
			texture.write(0, 0, 0, edge, made.image().abgr(edge));
			sprite = new Sprite(texture, edge, made);
			SPRITES.put(key, sprite);
		}
		return sprite;
	}

	private static void draw(GuiGraphicsExtractor graphics, Sprite sprite, Waypoint waypoint, float x, float y, int head) {
		PinArt.Art art = sprite.art();
		// Keep the body and initials on one local pixel grid, moving continuously with the map.
		graphics.pose().pushMatrix();
		graphics.pose().translate(x - art.anchorX(), y - art.anchorY());
		int w = art.image().width;
		int h = art.image().height;
		float edge = sprite.edge();
		// A colour saved with no alpha at all is meant opaque; otherwise the pin keeps the colour's alpha.
		int alpha = waypoint.color >>> 24 == 0 ? 0xFF : waypoint.color >>> 24;
		MapCanvas.of(graphics).rect(sprite.texture(), Sampling.CLAMP_CRISP, 0, 0, w, h, 0, 0, w / edge, h / edge, alpha << 24 | 0xFFFFFF);
		if (waypoint.kind != Waypoint.Kind.DEATH) {
			label(graphics, waypoint, art.labelX0(), art.labelY0(), art.labelX1(), art.labelY1());
		}
		graphics.pose().popMatrix();
	}

	/** The initials at full size, centred on their ink in the head; just the first letter when two don't fit. */
	private static void label(GuiGraphicsExtractor graphics, Waypoint waypoint, int x0, int y0, int x1, int y1) {
		Font font = Minecraft.getInstance().font;
		String text = waypoint.label();
		if (text.isEmpty()) {
			return;
		}
		if (ink(font, text) > x1 - x0 - 2) {
			text = text.substring(0, text.offsetByCodePoints(0, 1));
		}
		int rgb = rgb(waypoint);
		boolean light = PinArt.light(rgb);
		int x = PinArt.centred(x0, x1, ink(font, text));
		// Capitals and digits are 7 pixels tall from the top of the line.
		int y = PinArt.centred(y0, y1, 7);
		graphics.text(font, text, x, y, light ? 0xFF000000 | PinArt.INK : 0xFFFFFFFF, !light);
	}

	/** Width of a string's ink: its advance less the one-pixel gap after the last glyph. */
	private static int ink(Font font, String text) {
		return font.width(text) - 1;
	}
}
