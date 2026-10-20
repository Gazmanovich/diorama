//? if >=26.3 {
package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.SDLEventHandler;

// From 26.3 the real keyboard and mouse reach the game as SDL events; like Fabric API's client
// game tests, the shim drops them. Only applied from 26.3 (see ShimMixinPlugin); the whole file is
// a Stonecutter block, so no block comments in it.
@Mixin(SDLEventHandler.class)
public abstract class SDLEventHandlerMixin {
	@Inject(method = {
		"handleKeyEvent",
		"handleTextEditingEvent",
		"handleTextInputEvent",
		"handleMouseMotionEvent",
		"handleMouseButtonEvent",
		"handleMouseWheelEvent",
		"handleDropFileEvent",
		"handleDropBeginEvent",
		"handleDropCompleteEvent"
	}, at = @At("HEAD"), cancellable = true)
	private void diorama$noRealInput(CallbackInfo ci) {
		ci.cancel();
	}
}
//?}
