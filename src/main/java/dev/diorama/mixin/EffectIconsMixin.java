package dev.diorama.mixin;

import dev.diorama.MinimapHud;
//? if >=26.2 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the minimap the game's drawing of its status effect icons in the top-right corner ran to
 * the end, so it moves below them only then. With no effects, or under the inventory, that ends
 * without drawing any, but the minimap then has no rows to make room for or isn't drawn. Mods that put the icons somewhere else, like Inventory HUD+, cancel this
 * method, and on NeoForge and Forge a mod can turn its HUD layer off; either way the end of it is
 * never reached. Xaero's Minimap moves out of the way on the same signal.
 */
//? if >=26.2 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public abstract class EffectIconsMixin {
	//? if >=26.1 {
	@Inject(method = "extractEffects", at = @At("RETURN"))
	//?} else {
	/*@Inject(method = "renderEffects", at = @At("RETURN"))
	*///?}
	private void diorama$effectIconsDrawn(CallbackInfo ci) {
		MinimapHud.effectIconsDrawn();
	}
}
