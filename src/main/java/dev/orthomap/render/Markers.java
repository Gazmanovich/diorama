package dev.orthomap.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;

/** Shapes drawn on top of the map. */
public final class Markers {
	private Markers() {
	}

	/**
	 * The player arrow at a screen point. {@code yawDegrees} is Minecraft's yaw: 0 faces south,
	 * 90 faces west.
	 */
	public static void player(GuiGraphicsExtractor graphics, float x, float y, float yawDegrees, float size) {
		double yaw = Math.toRadians(yawDegrees);
		// Screen direction of travel. North-south distances look shorter under the tilted camera.
		double dx = -Math.sin(yaw);
		double dy = Math.cos(yaw) * dev.orthomap.Projection.sin();
		double len = Math.sqrt(dx * dx + dy * dy);
		dx /= len;
		dy /= len;
		arrow(graphics, x, y, dx, dy, size + 1.6F, 0xE0101418);
		arrow(graphics, x, y, dx, dy, size, 0xFFFFFFFF);
		arrow(graphics, x, y, dx, dy, size * 0.55F, 0xFF3FA9F5);
	}

	private static void arrow(GuiGraphicsExtractor graphics, float x, float y, double dx, double dy, float size, int color) {
		// Perpendicular.
		double px = -dy;
		double py = dx;
		float tipX = (float) (x + dx * size);
		float tipY = (float) (y + dy * size);
		float leftX = (float) (x - dx * size * 0.75 + px * size * 0.7);
		float leftY = (float) (y - dy * size * 0.75 + py * size * 0.7);
		float rightX = (float) (x - dx * size * 0.75 - px * size * 0.7);
		float rightY = (float) (y - dy * size * 0.75 - py * size * 0.7);
		float notchX = (float) (x - dx * size * 0.35);
		float notchY = (float) (y - dy * size * 0.35);
		// One quad: tip, right wing, notch, left wing (the GUI culls the other winding).
		graphics.guiRenderState.addGuiElement(QuadElement.of(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			new Matrix3x2f(graphics.pose()),
			new float[] {tipX, tipY, rightX, rightY, notchX, notchY, leftX, leftY},
			null,
			color,
			graphics.scissorStack.peek()
		));
	}
}
