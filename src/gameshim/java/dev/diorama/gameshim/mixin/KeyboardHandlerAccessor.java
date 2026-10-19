package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.KeyboardHandler;

/** The keyboard handler's GLFW character callback, for simulated typing. Key presses are public. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
	@Invoker("charTyped")
	void diorama$charTyped(long window, int codePoint, int mods);
}
