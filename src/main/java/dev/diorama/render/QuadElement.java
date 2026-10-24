//? if >=1.21.6 {
package dev.diorama.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

// A GUI quad with float corners, optional UVs and one colour, as GUI render state (1.21.6 on).
// Vanilla GUI draws take whole-pixel positions; the map pans smoothly, so it needs this.
// corners: x0 y0 x1 y1 x2 y2 x3 y3, counter-clockwise from the top left; more quads follow in
// the same element, which draws as one (the round minimap's clipped shapes).
// uvs: u0 v0 u1 v1 u2 v2 u3 v3, matching the corners, or null for untextured pipelines.
public record QuadElement(
	RenderPipeline pipeline,
	TextureSetup textureSetup,
	Matrix3x2f pose,
	float[] corners,
	float @Nullable [] uvs,
	int color,
	@Nullable ScreenRectangle scissorArea,
	@Nullable ScreenRectangle bounds
) implements GuiElementRenderState {
	public static QuadElement of(
		RenderPipeline pipeline, TextureSetup textures, Matrix3x2f pose, float[] corners, float @Nullable [] uvs, int color,
		@Nullable ScreenRectangle scissor
	) {
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int i = 0; i < corners.length; i += 2) {
			minX = Math.min(minX, corners[i]);
			maxX = Math.max(maxX, corners[i]);
			minY = Math.min(minY, corners[i + 1]);
			maxY = Math.max(maxY, corners[i + 1]);
		}
		int x0 = (int) Math.floor(minX);
		int y0 = (int) Math.floor(minY);
		ScreenRectangle bounds = new ScreenRectangle(x0, y0, (int) Math.ceil(maxX) - x0, (int) Math.ceil(maxY) - y0).transformMaxBounds(pose);
		if (scissor != null) {
			bounds = scissor.intersection(bounds);
		}
		return new QuadElement(pipeline, textures, pose, corners, uvs, color, scissor, bounds);
	}

	public static QuadElement rect(
		RenderPipeline pipeline, TextureSetup textures, Matrix3x2f pose, float x0, float y0, float x1, float y1,
		float u0, float v0, float u1, float v1, int color, @Nullable ScreenRectangle scissor
	) {
		return of(
			pipeline, textures, pose,
			new float[] {x0, y0, x0, y1, x1, y1, x1, y0},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0},
			color, scissor
		);
	}

	//? if >=1.21.9 {
	@Override
	public void buildVertices(VertexConsumer consumer) {
		for (int i = 0; i < this.corners.length / 2; i++) {
			this.vertex(consumer.addVertexWith2DPose(this.pose, this.corners[i * 2], this.corners[i * 2 + 1]), i);
		}
	}
	//?} else {
	/*// GUI elements were drawn at a depth the renderer handed in.
	@Override
	public void buildVertices(VertexConsumer consumer, float z) {
		for (int i = 0; i < this.corners.length / 2; i++) {
			this.vertex(consumer.addVertexWith2DPose(this.pose, this.corners[i * 2], this.corners[i * 2 + 1], z), i);
		}
	}
	*///?}

	private void vertex(VertexConsumer vertex, int i) {
		if (this.uvs != null) {
			vertex.setUv(this.uvs[i * 2], this.uvs[i * 2 + 1]);
		}
		vertex.setColor(this.color);
	}
}
//?}
