//? if >=1.21.6 {
package dev.diorama.render.gpu;

import dev.diorama.render.DioramaPipelines;
import dev.diorama.render.QuadElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//?} else {
/*import net.minecraft.client.gui.components.PlayerFaceRenderer;
*///?}
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

// The MapCanvas from 1.21.6 on: every shape is a GUI element submitted to the frame's render state.
final class StateCanvas implements MapCanvas {
	private final GuiGraphicsExtractor graphics;

	StateCanvas(GuiGraphicsExtractor graphics) {
		this.graphics = graphics;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			RenderPipelines.GUI_TEXTURED,
			((StateTexture) texture).single(sampling),
			new Matrix3x2f(this.graphics.pose()),
			corners,
			uvs,
			argb,
			this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void solid(float[] corners, int argb) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.of(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			new Matrix3x2f(this.graphics.pose()),
			corners,
			null,
			argb,
			this.graphics.scissorStack.peek()
		));
	}

	@Override
	public void blit(Identifier texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, w, h, texW, texH, argb);
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		//? if >=26.1 {
		PlayerFaceExtractor.extractRenderState(this.graphics, player.getSkin(), x, y, size, argb);
		//?} else {
		/*PlayerFaceRenderer.draw(this.graphics, player.getSkin(), x, y, size, argb);
		*///?}
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, int x, int y, int width, int height, float u0, float v0, float u1, float v1, int params) {
		this.graphics.guiRenderState.addGuiElement(QuadElement.rect(
			DioramaPipelines.FOG,
			StateTexture.pair((StateTexture) mask, Sampling.CLAMP_LINEAR, (StateTexture) noise, Sampling.REPEAT_LINEAR),
			new Matrix3x2f(this.graphics.pose()),
			x, y, x + width, y + height,
			u0, v0, u1, v1,
			params,
			new ScreenRectangle(x, y, width, height)
		));
	}
}
//?}
