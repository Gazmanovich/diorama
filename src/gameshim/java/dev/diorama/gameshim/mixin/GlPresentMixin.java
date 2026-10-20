//? if >=1.21.5 {
package dev.diorama.gameshim.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
//? if >=26.3 {
import com.mojang.renderpearl.backend.opengl.DirectStateAccess;
//?} else {
/*import com.mojang.blaze3d.opengl.DirectStateAccess;
*///?}
//? if >=1.21.6 {
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else {
/*import com.mojang.renderpearl.api.textures.GpuTexture;
*///?}

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import dev.diorama.gameshim.WindowHooks;
import net.minecraft.client.Minecraft;

// From 1.21.5 the frame reaches the window through the OpenGL backend's presentTexture: like
// Fabric API's client game tests (GlCommandEncoderMixin), the frame drawn at the test's size is
// stretched over the real window. Only applied from 1.21.5 (see ShimMixinPlugin); the whole file
// is a Stonecutter block, so no block comments in it.
//? if >=26.3 {
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
//?} else {
/*@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
*///?}
public abstract class GlPresentMixin {
	//? if >=26.3 {
	@WrapOperation(method = "presentTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
	//?} else {
	/*@WrapOperation(method = "presentTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V"))
	*///?}
	private void diorama$showAtRealSize(DirectStateAccess access, int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1,
		int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter, Operation<Void> original,
		//? if >=1.21.6 {
		@Local(argsOnly = true) GpuTextureView view) {
		Object mainTexture = mainColorTexture();
		boolean main = mainTexture != null && view.texture() == mainTexture;
		//?} else {
		/*@Local(argsOnly = true) GpuTexture texture) {
		Object mainTexture = mainColorTexture();
		boolean main = mainTexture != null && texture == mainTexture;
		*///?}
		if (main) {
			WindowHooks window = (WindowHooks) (Object) Minecraft.getInstance().getWindow();
			// From 26.2 the game copies only what fits, at the window's top.
			dstY0 = 0;
			dstX1 = window.diorama$realFramebufferWidth();
			dstY1 = window.diorama$realFramebufferHeight();
		}
		original.call(access, readFramebuffer, drawFramebuffer, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
	}

	// Null while NeoForge's loading screen still shows frames, before the game has a render target.
	private static Object mainColorTexture() {
		Minecraft minecraft = Minecraft.getInstance();
		//? if >=26.2 {
		if (minecraft == null || minecraft.gameRenderer == null) {
			return null;
		}
		return minecraft.gameRenderer.mainRenderTarget().getColorTexture();
		//?} else {
		/*if (minecraft == null || minecraft.getMainRenderTarget() == null) {
			return null;
		}
		return minecraft.getMainRenderTarget().getColorTexture();
		*///?}
	}
}
//?}
