package dev.diorama.gameshim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import dev.diorama.gameshim.mixin.KeyboardHandlerAccessor;
import dev.diorama.gameshim.mixin.MouseHandlerAccessor;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.minecraft.client.Minecraft;

/**
 * Simulated input, after Fabric API's TestInputImpl: events go straight into the game's mouse
 * handler, as if GLFW had sent them. The real mouse and keyboard are cut off (see the mixins).
 */
final class ShimInput implements TestInput {
	private final ShimContext context;
	private final Set<Integer> mouseDown = new LinkedHashSet<>();

	ShimInput(ShimContext context) {
		this.context = context;
	}

	@Override
	public void holdMouse(int button) {
		ShimThreading.checkOnTestThread("holdMouse");
		if (mouseDown.add(button)) {
			context.runOnClient(minecraft -> mouse(minecraft).diorama$onPress(window(minecraft), button, GLFW.GLFW_PRESS, 0));
		}
	}

	@Override
	public void releaseMouse(int button) {
		ShimThreading.checkOnTestThread("releaseMouse");
		if (mouseDown.remove(button)) {
			context.runOnClient(minecraft -> mouse(minecraft).diorama$onPress(window(minecraft), button, GLFW.GLFW_RELEASE, 0));
		}
	}

	@Override
	public void pressMouse(int button) {
		ShimThreading.checkOnTestThread("pressMouse");
		holdMouse(button);
		releaseMouse(button);
		context.waitTick();
	}

	@Override
	public void pressKey(KeyMapping keyMapping) {
		ShimThreading.checkOnTestThread("pressKey");
		// The key it is bound to, as the options file saves it.
		InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
		if (key.getType() == InputConstants.Type.MOUSE) {
			pressMouse(key.getValue());
			return;
		}
		context.runOnClient(minecraft -> {
			minecraft.keyboardHandler.keyPress(window(minecraft), key.getValue(), 0, GLFW.GLFW_PRESS, 0);
			minecraft.keyboardHandler.keyPress(window(minecraft), key.getValue(), 0, GLFW.GLFW_RELEASE, 0);
		});
		context.waitTick();
	}

	@Override
	public void typeChars(String chars) {
		ShimThreading.checkOnTestThread("typeChars");
		context.runOnClient(minecraft -> chars.codePoints().forEach(c ->
			((KeyboardHandlerAccessor) minecraft.keyboardHandler).diorama$charTyped(window(minecraft), c, 0)));
	}

	@Override
	public void setCursorPos(double x, double y) {
		ShimThreading.checkOnTestThread("setCursorPos");
		context.runOnClient(minecraft -> mouse(minecraft).diorama$onMove(window(minecraft), x, y));
	}

	@Override
	public void moveCursor(double deltaX, double deltaY) {
		ShimThreading.checkOnTestThread("moveCursor");
		context.runOnClient(minecraft -> mouse(minecraft).diorama$onMove(window(minecraft),
			minecraft.mouseHandler.xpos() + deltaX, minecraft.mouseHandler.ypos() + deltaY));
	}

	@Override
	public void resizeWindow(int width, int height) {
		ShimThreading.checkOnTestThread("resizeWindow");
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Window size must be positive: " + width + "x" + height);
		}
		context.runOnClient(minecraft -> ((WindowHooks) (Object) minecraft.getWindow()).diorama$resize(width, height));
	}

	/** After a test: releases held buttons and puts the cursor in the middle of the window. */
	void releaseAll() {
		for (int button : new ArrayList<>(mouseDown)) {
			releaseMouse(button);
		}
		context.runOnClient(minecraft -> mouse(minecraft).diorama$onMove(window(minecraft),
			minecraft.getWindow().getScreenWidth() * 0.5, minecraft.getWindow().getScreenHeight() * 0.5));
	}

	private static MouseHandlerAccessor mouse(Minecraft minecraft) {
		return (MouseHandlerAccessor) minecraft.mouseHandler;
	}

	private static long window(Minecraft minecraft) {
		return minecraft.getWindow().getWindow();
	}
}
