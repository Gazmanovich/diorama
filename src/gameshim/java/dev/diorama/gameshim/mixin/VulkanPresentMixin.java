//? if >=26.2 {
package dev.diorama.gameshim.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkOffset3D;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import dev.diorama.gameshim.WindowHooks;
import net.minecraft.client.Minecraft;

// From 26.2 the game can draw through Vulkan, whose window surface copies the frame into the
// swapchain: like Fabric API's client game tests (VulkanGpuSurfaceMixin), the frame drawn at the
// test's size is stretched over the real window. Only applied from 26.2 (see ShimMixinPlugin);
// the whole file is a Stonecutter block, so no block comments in it.
//? if >=26.3 {
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface")
//?} else {
/*@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanGpuSurface")
*///?}
public abstract class VulkanPresentMixin {
	@WrapOperation(method = "blitFromTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageBlit$Buffer;dstOffsets(Lorg/lwjgl/vulkan/VkOffset3D$Buffer;)Lorg/lwjgl/vulkan/VkImageBlit$Buffer;"))
	private VkImageBlit.Buffer diorama$showAtRealSize(VkImageBlit.Buffer region, VkOffset3D.Buffer dstOffsets, Operation<VkImageBlit.Buffer> original,
		@Local(argsOnly = true) GpuTextureView view) {
		// The game has no render target yet while NeoForge's loading screen still shows frames.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gameRenderer != null && view.texture() == minecraft.gameRenderer.mainRenderTarget().getColorTexture()) {
			WindowHooks window = (WindowHooks) (Object) Minecraft.getInstance().getWindow();
			dstOffsets.position(0);
			dstOffsets.x(0).y(window.diorama$realFramebufferHeight()).z(0);
			dstOffsets.position(1);
			dstOffsets.x(window.diorama$realFramebufferWidth()).y(0).z(1);
			dstOffsets.position(0);
		}
		return original.call(region, dstOffsets);
	}
}
//?}
