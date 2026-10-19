package dev.diorama.gameshim.mixin;

import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.gameshim.WindowHooks;

/**
 * A window whose size is under the test's control, ported from Fabric API's client game test
 * WindowMixin (Apache-2.0). The game sees the size the test set; GLFW's resize, focus and minimize
 * events are ignored, so the user moving, resizing or leaving the window changes nothing.
 */
@Mixin(Window.class)
public abstract class WindowMixin implements WindowHooks {
	@Shadow
	private int x;
	@Shadow
	private int y;
	@Shadow
	private int windowedX;
	@Shadow
	private int windowedY;
	@Shadow
	private int width;
	@Shadow
	private int height;
	@Shadow
	private int windowedWidth;
	@Shadow
	private int windowedHeight;
	@Shadow
	private int framebufferWidth;
	@Shadow
	private int framebufferHeight;
	@Shadow
	private boolean fullscreen;
	@Shadow
	@Final
	private WindowEventHandler eventHandler;
	@Shadow
	@Final
	private ScreenManager screenManager;
	@Shadow
	private Optional<VideoMode> preferredFullscreenVideoMode;

	@Shadow
	protected abstract void setMode();

	@Unique
	private int defaultWidth;
	@Unique
	private int defaultHeight;
	@Unique
	private int realFramebufferWidth;
	@Unique
	private int realFramebufferHeight;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void diorama$init(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String videoMode, String title, CallbackInfo ci) {
		defaultWidth = displayData.width;
		defaultHeight = displayData.height;
		realFramebufferWidth = framebufferWidth;
		realFramebufferHeight = framebufferHeight;
		width = windowedWidth = framebufferWidth = defaultWidth;
		height = windowedHeight = framebufferHeight = defaultHeight;
	}

	@Inject(method = {"onFocus", "onEnter"}, at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreFocus(CallbackInfo ci) {
		ci.cancel();
	}

	// From 1.21.2: the game drops to 10 fps while minimized.
	@Inject(method = "onIconify", at = @At("HEAD"), cancellable = true, require = 0)
	private void diorama$ignoreMinimize(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "onResize", at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreResize(long window, int width, int height, CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreFramebufferResize(long window, int width, int height, CallbackInfo ci) {
		if (width != 0 && height != 0) {
			realFramebufferWidth = width;
			realFramebufferHeight = height;
		}
		ci.cancel();
	}

	/** Moves and sizes the real window, but keeps the size the game sees. */
	@WrapMethod(method = "setMode")
	private void diorama$keepSize(Operation<Void> original) {
		int keptWidth = width;
		int keptHeight = height;
		int keptWindowedWidth = windowedWidth;
		int keptWindowedHeight = windowedHeight;
		original.call();
		width = keptWidth;
		height = keptHeight;
		windowedWidth = keptWindowedWidth;
		windowedHeight = keptWindowedHeight;
	}

	@Inject(method = "setWindowed", at = @At("HEAD"), cancellable = true)
	private void diorama$setWindowed(int width, int height, CallbackInfo ci) {
		fullscreen = false;
		diorama$resize(width, height);
		ci.cancel();
	}

	@Override
	public int diorama$realFramebufferWidth() {
		return realFramebufferWidth;
	}

	@Override
	public int diorama$realFramebufferHeight() {
		return realFramebufferHeight;
	}

	@Override
	public void diorama$resetSize() {
		diorama$resize(defaultWidth, defaultHeight);
	}

	@Override
	public void diorama$resize(int width, int height) {
		if (width == this.width && width == windowedWidth && width == framebufferWidth
			&& height == this.height && height == windowedHeight && height == framebufferHeight) {
			return;
		}
		// Grow or shrink around the window's centre, keeping it on its monitor where possible.
		Monitor monitor = screenManager.findBestMonitor((Window) (Object) this);
		if (monitor != null) {
			VideoMode videoMode = monitor.getPreferredVidMode(preferredFullscreenVideoMode);
			x += (windowedWidth - width) / 2;
			y += (windowedHeight - height) / 2;
			x = Math.max(monitor.getX(), Math.min(x, monitor.getX() + videoMode.getWidth() - width));
			y = Math.max(monitor.getY(), Math.min(y, monitor.getY() + videoMode.getHeight() - height));
			windowedX = x;
			windowedY = y;
		}
		this.width = windowedWidth = framebufferWidth = width;
		this.height = windowedHeight = framebufferHeight = height;
		setMode();
		eventHandler.resizeDisplay();
	}
}
