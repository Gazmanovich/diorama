package dev.diorama.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.MouseHandler;

/** The GLFW callbacks the mouse handler registers, for simulated input. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Invoker("onPress")
	void diorama$onPress(long window, int button, int action, int mods);

	@Invoker("onMove")
	void diorama$onMove(long window, double x, double y);
}
