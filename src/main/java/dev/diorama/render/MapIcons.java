package dev.diorama.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//? if <1.21.11 {
/*import com.mojang.blaze3d.textures.AddressMode;
*///?}
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/** Small white shapes drawn tinted on the map: dots for mobs, carets for "above" and "below". */
public final class MapIcons {
	private static final int SIZE = 32;
	private static @Nullable GpuTextureView dot;
	private static @Nullable GpuTextureView caret;

	private MapIcons() {
	}

	/** A round dot of diameter {@code size} centred on the point. */
	public static void dot(GuiGraphicsExtractor graphics, float x, float y, float size, int argb) {
		if (dot == null) {
			dot = create("diorama dot", MapIcons::circle);
		}
		draw(graphics, dot, x - size / 2, y - size / 2, size, argb, false);
	}

	/** A small caret centred on the point, pointing up, or down when {@code down}. */
	public static void caret(GuiGraphicsExtractor graphics, float x, float y, float size, int argb, boolean down) {
		if (caret == null) {
			caret = create("diorama caret", MapIcons::triangle);
		}
		draw(graphics, caret, x - size / 2, y - size / 2, size, argb, down);
	}

	/**
	 * A caret centred on the point, pointing along {@code angle} (radians, screen space, 0 is
	 * right). {@code grow} enlarges it, for drawing an outline underneath.
	 */
	public static void arrow(GuiGraphicsExtractor graphics, float x, float y, float size, double angle, int argb, float grow) {
		if (caret == null) {
			caret = create("diorama caret", MapIcons::triangle);
		}
		float half = (size + grow) / 2;
		// The caret texture points up; turn it to point along the angle.
		double turn = angle + Math.PI / 2;
		float cos = (float) Math.cos(turn);
		float sin = (float) Math.sin(turn);
		float[] corners = new float[8];
		float[][] local = {{-half, -half}, {-half, half}, {half, half}, {half, -half}};
		for (int i = 0; i < 4; i++) {
			corners[i * 2] = x + local[i][0] * cos - local[i][1] * sin;
			corners[i * 2 + 1] = y + local[i][0] * sin + local[i][1] * cos;
		}
		graphics.guiRenderState.addGuiElement(QuadElement.of(
			RenderPipelines.GUI_TEXTURED,
			//? if >=1.21.11 {
			TextureSetup.singleTexture(caret, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
			//?} else {
			/*TextureSetup.singleTexture(caret),
			*///?}
			new Matrix3x2f(graphics.pose()),
			corners,
			new float[] {0, 0, 0, 1, 1, 1, 1, 0},
			argb,
			graphics.scissorStack.peek()
		));
	}

	private static void draw(GuiGraphicsExtractor graphics, GpuTextureView texture, float x, float y, float size, int argb, boolean flip) {
		graphics.guiRenderState.addGuiElement(QuadElement.rect(
			RenderPipelines.GUI_TEXTURED,
			//? if >=1.21.11 {
			TextureSetup.singleTexture(texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
			//?} else {
			/*TextureSetup.singleTexture(texture),
			*///?}
			new Matrix3x2f(graphics.pose()),
			x, y, x + size, y + size,
			0, flip ? 1 : 0, 1, flip ? 0 : 1,
			argb,
			graphics.scissorStack.peek()
		));
	}

	private interface Shape {
		/** Coverage 0..1 of the pixel centred at (px, py), in -1..1 coordinates. */
		float coverage(float px, float py);
	}

	private static float circle(float px, float py) {
		float d = (float) Math.sqrt(px * px + py * py);
		// One pixel of soft edge.
		return Math.max(0, Math.min(1, (0.94F - d) * SIZE / 2));
	}

	private static float triangle(float px, float py) {
		// Tip at the top, base at the bottom.
		float halfWidth = (py + 0.8F) * 0.62F;
		if (py < -0.8F || py > 0.75F) {
			return 0;
		}
		return Math.max(0, Math.min(1, (halfWidth - Math.abs(px)) * SIZE / 2));
	}

	private static GpuTextureView create(String label, Shape shape) {
		int[] pixels = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float px = (x + 0.5F) / SIZE * 2 - 1;
				float py = (y + 0.5F) / SIZE * 2 - 1;
				int a = Math.round(shape.coverage(px, py) * 255);
				pixels[y * SIZE + x] = (a << 24) | 0xFFFFFF;
			}
		}
		GpuTexture texture = FogTextures.createTexture(label, SIZE);
		FogTextures.write(texture, pixels, SIZE);
		GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
		//? if <1.21.11 {
		/*FogTextures.sampleWith(view, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, false);
		*///?}
		return view;
	}
}
