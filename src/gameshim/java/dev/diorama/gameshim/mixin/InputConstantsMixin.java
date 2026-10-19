package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Cuts the game off from the real keyboard and mouse, as Fabric API's client game tests do, so a
 * person or another program using the desktop can't disturb a test: no input callbacks, no cursor
 * capture, and no key reads as held.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void diorama$noKeyHeld(long window, int key, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}

	@Inject(method = {"setupKeyboardCallbacks", "setupMouseCallbacks"}, at = @At("HEAD"), cancellable = true)
	private static void diorama$noInputCallbacks(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
	private static void diorama$noCursorCapture(CallbackInfo ci) {
		ci.cancel();
	}
}
