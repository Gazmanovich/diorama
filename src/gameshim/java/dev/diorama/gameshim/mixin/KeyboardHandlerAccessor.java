package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.KeyboardHandler;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//?}

/**
 * The keyboard handler's window system callbacks, for simulated typing. Before 1.21.9 key presses
 * are public.
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
	//? if >=1.21.9 {
	@Invoker("keyPress")
	void diorama$keyPress(long window, int action, KeyEvent event);

	@Invoker("charTyped")
	void diorama$charTyped(long window, CharacterEvent event);
	//?} else {
	/*@Invoker("charTyped")
	void diorama$charTyped(long window, int codePoint, int mods);
	*///?}
}
