package dev.diorama.gameshim.mixin;

import com.mojang.blaze3d.platform.Window;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gameshim.GameShim;
import dev.diorama.gameshim.ShimThreading;
import dev.diorama.gameshim.WindowHooks;
import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	@Final
	private Window window;

	/** The test thread's work runs at the start of every frame, the game's blocking ones included. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void diorama$runTestTasks(boolean tick, CallbackInfo ci) {
		ShimThreading.runClientTasks();
	}

	@Inject(method = "runTick", at = @At("RETURN"))
	private void diorama$frameDrawn(boolean tick, CallbackInfo ci) {
		ShimThreading.onFrameEnd();
	}

	/** The frame is drawn at the size the test set, and shown at the real window's size. */
	@ModifyArg(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"), index = 0)
	private int diorama$showAtRealWidth(int width) {
		return ((WindowHooks) (Object) window).diorama$realFramebufferWidth();
	}

	@ModifyArg(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"), index = 1)
	private int diorama$showAtRealHeight(int height) {
		return ((WindowHooks) (Object) window).diorama$realFramebufferHeight();
	}

	/** Exit code 1 if a test failed, 2 if the game closed before the tests finished. */
	@ModifyArg(method = "destroy", at = @At(value = "INVOKE", target = "Ljava/lang/System;exit(I)V", remap = false))
	private int diorama$testExitCode(int code) {
		return GameShim.exitCode(code);
	}
}
