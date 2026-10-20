package dev.diorama.gameshim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonInfo;
//?}
import net.minecraft.network.chat.Component;

/** The shim's {@link ClientGameTestContext}, after Fabric API's ClientGameTestContextImpl. */
final class ShimContext implements ClientGameTestContext {
	/** Fabric numbers screenshots across the whole run: 0000_first.png, 0001_second.png... */
	private static final AtomicInteger SCREENSHOT_COUNTER = new AtomicInteger();

	private final ShimInput input = new ShimInput(this);

	/** Before each test: the same test-friendly options a fresh game starts with. */
	void prepare() {
		runOnClient(minecraft -> ShimOptions.apply(minecraft.options));
	}

	@Override
	public void waitTick() {
		ShimThreading.checkOnTestThread("waitTick");
		ShimThreading.waitTicks(1);
	}

	@Override
	public void waitTicks(int ticks) {
		ShimThreading.checkOnTestThread("waitTicks");
		if (ticks < 0) {
			throw new IllegalArgumentException("ticks cannot be negative");
		}
		ShimThreading.waitTicks(ticks);
	}

	@Override
	public int waitFor(Predicate<Minecraft> predicate, int timeout) {
		ShimThreading.checkOnTestThread("waitFor");
		Objects.requireNonNull(predicate, "predicate");
		if (timeout == NO_TIMEOUT) {
			int waited = 0;
			while (!computeOnClient(predicate::test)) {
				waited++;
				waitTick();
			}
			return waited;
		}
		if (timeout <= 0) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		for (int i = 0; i < timeout; i++) {
			if (computeOnClient(predicate::test)) {
				return i;
			}
			waitTick();
		}
		if (!computeOnClient(predicate::test)) {
			throw new AssertionError("Timed out waiting for predicate");
		}
		return timeout;
	}

	@Override
	public void setScreen(Supplier<Screen> screen) {
		ShimThreading.checkOnTestThread("setScreen");
		runOnClient(minecraft -> minecraft.gui.setScreen(screen.get()));
	}

	@Override
	public void clickScreenButton(String translationKey) {
		ShimThreading.checkOnTestThread("clickScreenButton");
		Objects.requireNonNull(translationKey, "translationKey");
		runOnClient(minecraft -> {
			Screen current = minecraft.gui.screen();
			if (!clickButton(current, translationKey)) {
				throw new AssertionError("Could not find button '%s' in screen '%s'".formatted(
					translationKey, current == null ? null : current.getClass().getName()));
			}
		});
	}

	private static boolean clickButton(Screen screen, String translationKey) {
		if (screen == null) {
			return false;
		}
		String text = Component.translatable(translationKey).getString();
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractWidget widget && pressIfMatches(widget, text)) {
				return true;
			}
			if (child instanceof LayoutElement layout) {
				boolean[] found = {false};
				layout.visitWidgets(widget -> {
					if (!found[0]) {
						found[0] = pressIfMatches(widget, text);
					}
				});
				if (found[0]) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean pressIfMatches(AbstractWidget widget, String text) {
		if (widget instanceof Button button && text.equals(button.getMessage().getString())) {
			//? if >=1.21.9 {
			button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
			//?} else {
			/*button.onPress();
			*///?}
			return true;
		}
		return false;
	}

	@Override
	public Path takeScreenshot(String name) {
		ShimThreading.checkOnTestThread("takeScreenshot");
		Objects.requireNonNull(name, "name");
		// A frame drawn entirely after this call, so it shows everything the test did before it.
		// The main render target still holds it when the next frame's tasks run.
		ShimThreading.waitFrames(1);
		CompletableFuture<Path> written = new CompletableFuture<>();
		runOnClient(minecraft -> {
			Path folder = minecraft.gameDirectory.toPath().resolve("screenshots");
			Path file = folder.resolve("%04d_%s.png".formatted(SCREENSHOT_COUNTER.getAndIncrement(), name));
			//? if >=26.2 {
			RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
			//?} else {
			/*RenderTarget target = minecraft.getMainRenderTarget();
			*///?}
			//? if >=1.21.5 {
			// The GPU copies the frame out, and the game hands the image over a frame or two later.
			Screenshot.takeScreenshot(target, image -> write(image, file, written));
			//?} else {
			/*write(Screenshot.takeScreenshot(target), file, written);
			*///?}
		});
		// A few frames normally; a lost callback fails the test instead of hanging it.
		for (int frames = 0; !written.isDone(); frames++) {
			if (frames == 600) {
				throw new AssertionError("Timed out waiting for screenshot " + name);
			}
			ShimThreading.waitFrames(1);
		}
		try {
			return written.get();
		} catch (ExecutionException e) {
			throw ShimThreading.sneakyThrow(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while writing screenshot " + name, e);
		}
	}

	private static void write(NativeImage image, Path file, CompletableFuture<Path> written) {
		try (image) {
			Files.createDirectories(file.getParent());
			image.writeToFile(file);
			written.complete(file);
		} catch (IOException | RuntimeException e) {
			written.completeExceptionally(new AssertionError("Failed to write screenshot " + file, e));
		}
	}

	@Override
	public ShimInput getInput() {
		return input;
	}

	@Override
	public TestWorldBuilder worldBuilder() {
		return new ShimWorldBuilder(this);
	}

	@Override
	public <E extends Throwable> void runOnClient(FailableConsumer<Minecraft, E> action) throws E {
		ShimThreading.checkOnTestThread("runOnClient");
		Objects.requireNonNull(action, "action");
		ShimThreading.onClient(() -> {
			action.accept(Minecraft.getInstance());
			return null;
		});
	}

	@Override
	public <T, E extends Throwable> T computeOnClient(FailableFunction<Minecraft, T, E> function) throws E {
		ShimThreading.checkOnTestThread("computeOnClient");
		Objects.requireNonNull(function, "function");
		return ShimThreading.onClient(() -> function.apply(Minecraft.getInstance()));
	}
}
