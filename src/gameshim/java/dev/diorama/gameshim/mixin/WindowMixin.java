package dev.diorama.gameshim.mixin;

import java.util.Optional;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
//? if >=26.2 {
import com.mojang.blaze3d.platform.MonitorManager;
//?} else {
/*import com.mojang.blaze3d.platform.ScreenManager;
*///?}
//? if >=26.3 {
import com.mojang.renderpearl.api.device.GpuBackend;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
//?} else if >=26.1 {
/*import com.mojang.blaze3d.systems.GpuBackend;
*///?}

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
 * WindowMixin (Apache-2.0). The game sees the size the test set; the window system's resize, focus
 * and minimize events are ignored, so the user moving, resizing or leaving the window changes
 * nothing.
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
	//? if >=26.2 {
	@Shadow
	@Final
	private MonitorManager monitorManager;
	//?} else {
	/*@Shadow
	@Final
	private ScreenManager screenManager;
	*///?}
	@Shadow
	private Optional<VideoMode> preferredFullscreenVideoMode;
	//? if >=26.1 {
	@Shadow
	private boolean focused;
	//?}

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
	@Unique
	private int keptWidth;
	@Unique
	private int keptHeight;
	@Unique
	private int keptWindowedWidth;
	@Unique
	private int keptWindowedHeight;
	@Unique
	private int keptFramebufferWidth;
	@Unique
	private int keptFramebufferHeight;

	//? if >=26.3 {
	// The constructor that does the work; the other one calls it.
	@Inject(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V", at = @At("RETURN"))
	private void diorama$init(WindowEventHandler eventHandler, DisplayData displayData, String videoMode, boolean exclusiveFullscreen, String title, MonitorManager monitorManager, GpuBackend backend, int maximumSize, CallbackInfo ci) {
	//?} else if >=26.2 {
	/*@Inject(method = "<init>", at = @At("RETURN"))
	private void diorama$init(WindowEventHandler eventHandler, DisplayData displayData, String videoMode, boolean exclusiveFullscreen, String title, MonitorManager monitorManager, GpuBackend backend, CallbackInfo ci) {
	*///?} else if >=26.1 {
	/*@Inject(method = "<init>", at = @At("RETURN"))
	private void diorama$init(WindowEventHandler eventHandler, DisplayData displayData, String videoMode, String title, GpuBackend backend, CallbackInfo ci) {
	*///?} else {
	/*@Inject(method = "<init>", at = @At("RETURN"))
	private void diorama$init(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String videoMode, String title, CallbackInfo ci) {
	*///?}
		//? if >=1.21.5 {
		defaultWidth = displayData.width();
		defaultHeight = displayData.height();
		//?} else {
		/*defaultWidth = displayData.width;
		defaultHeight = displayData.height;
		*///?}
		realFramebufferWidth = framebufferWidth;
		realFramebufferHeight = framebufferHeight;
		width = windowedWidth = framebufferWidth = defaultWidth;
		height = windowedHeight = framebufferHeight = defaultHeight;
	}

	//? if >=26.3 {
	@Inject(method = {"onFocus", "onIconified"}, at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreFocus(CallbackInfo ci) {
		ci.cancel();
	}

	// The cursor entering or leaving the window comes as an event the window handles itself.
	@Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreEnter(SDL_Event event, CallbackInfo ci) {
		if (event.type() == SDLEvents.SDL_EVENT_WINDOW_MOUSE_ENTER || event.type() == SDLEvents.SDL_EVENT_WINDOW_MOUSE_LEAVE) {
			ci.cancel();
		}
	}

	@Inject(method = "onResize", at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreResize(int width, int height, CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
	private void diorama$ignoreFramebufferResize(int width, int height, CallbackInfo ci) {
	//?} else {
	/*@Inject(method = {"onFocus", "onEnter"}, at = @At("HEAD"), cancellable = true)
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
	*///?}
		if (width != 0 && height != 0) {
			realFramebufferWidth = width;
			realFramebufferHeight = height;
		}
		ci.cancel();
	}

	/**
	 * Moves and sizes the real window, but keeps the size the game sees. Fabric wraps the method;
	 * the shim keeps the size at its start and puts it back at its end, because MixinExtras isn't
	 * there on every NeoForge version.
	 */
	@Inject(method = "setMode", at = @At("HEAD"))
	private void diorama$keepSize(CallbackInfo ci) {
		keptWidth = width;
		keptHeight = height;
		keptWindowedWidth = windowedWidth;
		keptWindowedHeight = windowedHeight;
		keptFramebufferWidth = framebufferWidth;
		keptFramebufferHeight = framebufferHeight;
	}

	@Inject(method = "setMode", at = @At("RETURN"))
	private void diorama$restoreSize(CallbackInfo ci) {
		width = keptWidth;
		height = keptHeight;
		windowedWidth = keptWindowedWidth;
		windowedHeight = keptWindowedHeight;
		//? if >=26.3 {
		// From 26.3 the mode change also reads the real framebuffer size back.
		realFramebufferWidth = framebufferWidth;
		realFramebufferHeight = framebufferHeight;
		framebufferWidth = keptFramebufferWidth;
		framebufferHeight = keptFramebufferHeight;
		//?}
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
	public void diorama$focus() {
		//? if >=26.1 {
		focused = true;
		//?}
	}

	@Override
	public void diorama$resize(int width, int height) {
		if (width == this.width && width == windowedWidth && width == framebufferWidth
			&& height == this.height && height == windowedHeight && height == framebufferHeight) {
			return;
		}
		// Grow or shrink around the window's centre, keeping it on its monitor where possible.
		//? if >=26.2 {
		Monitor monitor = monitorManager.findBestMonitor((Window) (Object) this);
		//?} else {
		/*Monitor monitor = screenManager.findBestMonitor((Window) (Object) this);
		*///?}
		if (monitor != null) {
			//? if >=26.3 {
			VideoMode videoMode = monitor.getPreferredVideoMode(preferredFullscreenVideoMode);
			//?} else {
			/*VideoMode videoMode = monitor.getPreferredVidMode(preferredFullscreenVideoMode);
			*///?}
			//? if >=26.2 {
			int monitorX = monitor.x();
			int monitorY = monitor.y();
			//?} else {
			/*int monitorX = monitor.getX();
			int monitorY = monitor.getY();
			*///?}
			x += (windowedWidth - width) / 2;
			y += (windowedHeight - height) / 2;
			x = Math.max(monitorX, Math.min(x, monitorX + videoMode.getWidth() - width));
			y = Math.max(monitorY, Math.min(y, monitorY + videoMode.getHeight() - height));
			windowedX = x;
			windowedY = y;
		}
		this.width = windowedWidth = framebufferWidth = width;
		this.height = windowedHeight = framebufferHeight = height;
		setMode();
		//? if >=26.1 {
		eventHandler.resizeGui();
		//?} else {
		/*eventHandler.resizeDisplay();
		*///?}
	}
}
