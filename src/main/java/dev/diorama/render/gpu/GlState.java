package dev.diorama.render.gpu;

//? if >=26.3 {
import com.mojang.renderpearl.backend.opengl.GlStateManager;
//?} else if >=1.21.5 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
*///?} else {
/*import com.mojang.blaze3d.platform.GlStateManager;
*///?}

/**
 * The OpenGL state Minecraft keeps a copy of, which the raw OpenGL bake changes only through
 * Minecraft's own calls so that copy stays true; vanilla skips calls its copy says are already
 * done. The few that differ between versions are here.
 */
final class GlState {
	private GlState() {
	}

	static void bindFramebuffer(int target, int framebuffer) {
		GlStateManager._glBindFramebuffer(target, framebuffer);
	}

	static void activeTexture(int unit) {
		GlStateManager._activeTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0 + unit);
	}

	static void bindTexture(int texture) {
		GlStateManager._bindTexture(texture);
	}

	static void blend(boolean on) {
		//? if >=26.2 {
		// Blending is kept per attachment; the bake draws to at most two.
		for (int i = 0; i < 2; i++) {
			if (on) {
				GlStateManager._enableBlend(i);
			} else {
				GlStateManager._disableBlend(i);
			}
		}
		//?} else {
		/*if (on) {
			GlStateManager._enableBlend();
		} else {
			GlStateManager._disableBlend();
		}
		*///?}
	}

	static void blendFuncSeparate(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
		GlStateManager._blendFuncSeparate(srcColor, dstColor, srcAlpha, dstAlpha);
	}

	static void depthTest(boolean on) {
		if (on) {
			GlStateManager._enableDepthTest();
		} else {
			GlStateManager._disableDepthTest();
		}
	}

	static void depthFunc(int func) {
		GlStateManager._depthFunc(func);
	}

	static void depthMask(boolean write) {
		GlStateManager._depthMask(write);
	}

	static void colorMaskAll() {
		//? if >=26.1 {
		GlStateManager._colorMask(15);
		//?} else {
		/*GlStateManager._colorMask(true, true, true, true);
		*///?}
	}

	static void noCullNoScissor() {
		GlStateManager._disableCull();
		GlStateManager._disableScissorTest();
	}

	static void viewport(int width, int height) {
		GlStateManager._viewport(0, 0, width, height);
	}

	static void viewportAt(int x, int y, int width, int height) {
		GlStateManager._viewport(x, y, width, height);
	}

	static void useProgram(int program) {
		GlStateManager._glUseProgram(program);
	}

	static void bindVertexArray(int vao) {
		GlStateManager._glBindVertexArray(vao);
	}

	/** Before 1.21.5 vanilla's immediate-mode uploader remembers the last buffer it bound. */
	static void forgetBuffers() {
		//? if <1.21.5 {
		/*com.mojang.blaze3d.vertex.BufferUploader.invalidate();
		*///?}
	}
}
