package dev.diorama.gameshim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

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
		runOnClient(minecraft -> minecraft.setScreen(screen.get()));
	}

	@Override
	public void clickScreenButton(String translationKey) {
		ShimThreading.checkOnTestThread("clickScreenButton");
		Objects.requireNonNull(translationKey, "translationKey");
		runOnClient(minecraft -> {
			if (!clickButton(minecraft.screen, translationKey)) {
				throw new AssertionError("Could not find button '%s' in screen '%s'".formatted(
					translationKey, minecraft.screen == null ? null : minecraft.screen.getClass().getName()));
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
			button.onPress();
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
		return computeOnClient(minecraft -> {
			Path folder = minecraft.gameDirectory.toPath().resolve("screenshots");
			Path file = folder.resolve("%04d_%s.png".formatted(SCREENSHOT_COUNTER.getAndIncrement(), name));
			try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
				Files.createDirectories(folder);
				image.writeToFile(file);
			} catch (IOException e) {
				throw new AssertionError("Failed to write screenshot " + file, e);
			}
			return file;
		});
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
