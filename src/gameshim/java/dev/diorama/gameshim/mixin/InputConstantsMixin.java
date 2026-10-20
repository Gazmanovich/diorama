package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=1.21.9 && <26.3 {
/*import com.mojang.blaze3d.platform.Window;
*///?}

/**
 * Cuts the game off from the real keyboard and mouse, as Fabric API's client game tests do, so a
 * person or another program using the desktop can't disturb a test: no input callbacks, no cursor
 * capture, and no key reads as held. From 26.3, where input comes as SDL events, the events are
 * dropped in {@code SDLEventHandlerMixin} instead of the callbacks.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	//? if >=26.3 {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void diorama$noKeyHeld(int key, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}

	@Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"), cancellable = true)
	private static void diorama$noCursorCapture(CallbackInfo ci) {
		ci.cancel();
	}
	//?} else if >=1.21.9 {
	/*@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void diorama$noKeyHeld(Window window, int key, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}
	*///?} else {
	/*@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void diorama$noKeyHeld(long window, int key, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}
	*///?}

	//? if <26.3 {
	/*@Inject(method = {"setupKeyboardCallbacks", "setupMouseCallbacks"}, at = @At("HEAD"), cancellable = true)
	private static void diorama$noInputCallbacks(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
	private static void diorama$noCursorCapture(CallbackInfo ci) {
		ci.cancel();
	}
	*///?}
}
