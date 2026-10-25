//? if <1.21.6 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
//? if >=1.21.5 {
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
//?}

// The MapCanvas before 1.21.6, whose GUI draws straight away (or in batches it can flush) rather
// than through render state: each shape is a small mesh drawn at once through LegacyShaders, with
// its textures bound by hand. The GUI's scissor is already applied to such draws.
final class ImmediateCanvas implements MapCanvas {
	private final GuiGraphics graphics;

	ImmediateCanvas(GuiGraphics graphics) {
		this.graphics = graphics;
	}

	@Override
	public void quad(MapTexture texture, Sampling sampling, float[] corners, float[] uvs, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(argb);
			//?} else {
			// Before 1.21 the vertex setters had other names, and each vertex was ended by hand.
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).uv(uvs[i * 2], uvs[i * 2 + 1]).color(argb).endVertex();
			//?}
		}
		((LegacyTexture) texture).bind(0, sampling);
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	@Override
	public void solid(float[] corners, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setColor(argb);
			//?} else {
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).color(argb).endVertex();
			//?}
		}
		LegacyShaders.draw(LegacyShaders.SOLID, mesh.buildOrThrow());
	}

	@Override
	public void blit(ResourceLocation texture, int x, int y, int u, int v, int w, int h, int texW, int texH, int argb) {
		this.gameRect(texture, x, y, x + w, y + h, (float) u / texW, (float) v / texH, (float) (u + w) / texW, (float) (v + h) / texH, argb);
	}

	@Override
	public void solids(float[] xyz, int[] colors, int quads) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i < quads * 4; i++) {
			//? if >=1.21 {
			mesh.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setColor(colors[i / 4]);
			//?} else {
			mesh.vertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).color(colors[i / 4]).endVertex();
			//?}
		}
		LegacyShaders.draw(LegacyShaders.SOLID, mesh.buildOrThrow());
	}

	@Override
	public void blits(ResourceLocation texture, float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.mesh(xyz, uvs, colors, quads);
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(0, Minecraft.getInstance().getTextureManager().getTexture(texture).getTexture());
		//?} else {
		RenderSystem.setShaderTexture(0, texture);
		//?}
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	@Override
	public void rects(MapTexture texture, Sampling sampling, float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.mesh(xyz, uvs, colors, quads);
		((LegacyTexture) texture).bind(0, sampling);
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	// Textured quads whose corners are already in GUI coordinates.
	private BufferBuilder mesh(float[] xyz, float[] uvs, int[] colors, int quads) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		for (int i = 0; i < quads * 4; i++) {
			//? if >=1.21 {
			mesh.addVertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(colors[i / 4]);
			//?} else {
			mesh.vertex(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]).uv(uvs[i * 2], uvs[i * 2 + 1]).color(colors[i / 4]).endVertex();
			//?}
		}
		return mesh;
	}

	@Override
	public void playerFace(AbstractClientPlayer player, int x, int y, int size, int argb) {
		// The face and the hat layer over it, as vanilla's PlayerFaceRenderer cuts them from a skin.
		ResourceLocation skin = player.getSkin().texture();
		this.gameRect(skin, x, y, x + size, y + size, 8 / 64.0F, 8 / 64.0F, 16 / 64.0F, 16 / 64.0F, argb);
		this.gameRect(skin, x, y, x + size, y + size, 40 / 64.0F, 8 / 64.0F, 48 / 64.0F, 16 / 64.0F, argb);
	}

	@Override
	public void fog(MapTexture mask, MapTexture noise, float[] corners, float[] uvs, int x, int y, int width, int height, int params) {
		if (!LegacyShaders.fogReady()) {
			return;
		}
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		Matrix4f pose = this.graphics.pose().last().pose();
		for (int i = 0; i < corners.length / 2; i++) {
			//? if >=1.21 {
			mesh.addVertex(pose, corners[i * 2], corners[i * 2 + 1], 0).setUv(uvs[i * 2], uvs[i * 2 + 1]).setColor(params);
			//?} else {
			mesh.vertex(pose, corners[i * 2], corners[i * 2 + 1], 0).uv(uvs[i * 2], uvs[i * 2 + 1]).color(params).endVertex();
			//?}
		}
		((LegacyTexture) mask).bind(0, Sampling.CLAMP_LINEAR);
		((LegacyTexture) noise).bind(1, Sampling.REPEAT_LINEAR);
		LegacyShaders.drawFog(mesh.buildOrThrow());
		unbind(0);
		unbind(1);
	}

	// A rectangle of a game texture (a skin), sampled the way the game set that texture up.
	private void gameRect(ResourceLocation texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		BufferBuilder mesh = this.begin(DefaultVertexFormat.POSITION_TEX_COLOR);
		this.rect(mesh, x0, y0, x1, y1, u0, v0, u1, v1, argb);
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(0, Minecraft.getInstance().getTextureManager().getTexture(texture).getTexture());
		//?} else {
		RenderSystem.setShaderTexture(0, texture);
		//?}
		LegacyShaders.draw(LegacyShaders.TEXTURED, mesh.buildOrThrow());
		unbind(0);
	}

	private void rect(BufferBuilder mesh, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		Matrix4f pose = this.graphics.pose().last().pose();
		//? if >=1.21 {
		mesh.addVertex(pose, x0, y0, 0).setUv(u0, v0).setColor(argb);
		mesh.addVertex(pose, x0, y1, 0).setUv(u0, v1).setColor(argb);
		mesh.addVertex(pose, x1, y1, 0).setUv(u1, v1).setColor(argb);
		mesh.addVertex(pose, x1, y0, 0).setUv(u1, v0).setColor(argb);
		//?} else {
		mesh.vertex(pose, x0, y0, 0).uv(u0, v0).color(argb).endVertex();
		mesh.vertex(pose, x0, y1, 0).uv(u0, v1).color(argb).endVertex();
		mesh.vertex(pose, x1, y1, 0).uv(u1, v1).color(argb).endVertex();
		mesh.vertex(pose, x1, y0, 0).uv(u1, v0).color(argb).endVertex();
		//?}
	}

	private BufferBuilder begin(VertexFormat format) {
		// Draws the fills and text the GUI has batched so far first, so they stay underneath.
		this.graphics.flush();
		//? if >=1.21 {
		return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, format);
		//?} else {
		// Before 1.21 the tesselator had one builder, begun again for each mesh.
		BufferBuilder mesh = Tesselator.getInstance().getBuilder();
		mesh.begin(VertexFormat.Mode.QUADS, format);
		return mesh;
		//?}
	}

	// Clears a texture slot after a draw, so a later vanilla draw doesn't pick up our texture.
	private static void unbind(int slot) {
		//? if >=1.21.5 {
		RenderSystem.setShaderTexture(slot, (GpuTexture) null);
		//?} else {
		RenderSystem.setShaderTexture(slot, 0);
		//?}
	}
}
*///?}
