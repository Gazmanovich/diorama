package dev.diorama.gameshim;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Runs the {@code fabric-client-gametest} entrypoints on Minecraft versions whose Fabric API has no
 * client game test module. Only active with {@code -Ddiorama.gameshim=true}: once the game has
 * loaded it runs every test on a thread of its own, writes {@code PASS} or {@code FAIL} and the
 * stack trace to {@code gameshim-result.txt} in the game directory, and closes the game with exit
 * code 0 if every test passed, 1 if one failed, and 2 if the game closed before they finished.
 */
public final class GameShim implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("diorama-gameshim");
	private static final String ENTRYPOINT = "fabric-client-gametest";

	private static volatile boolean finished;
	private static volatile boolean failed;
	private static boolean started;

	public static boolean enabled() {
		return Boolean.getBoolean("diorama.gameshim");
	}

	@Override
	public void onInitializeClient() {
		if (!enabled()) {
			return;
		}
		LOGGER.info("Diorama game shim: running the {} entrypoints once the game has loaded", ENTRYPOINT);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			ShimThreading.onTickEnd();
			if (!started && minecraft.getOverlay() == null) {
				started = true;
				start(minecraft);
			}
		});
	}

	/** Called as the game's options are created, after options.txt is read. */
	public static void applyTestOptions(Options options) {
		ShimOptions.apply(options);
	}

	/** The game's exit code, given the one it was about to exit with. */
	public static int exitCode(int code) {
		if (code != 0) {
			return code;
		}
		return !finished ? 2 : failed ? 1 : 0;
	}

	private static void start(Minecraft minecraft) {
		// The window may not have focus; the game should behave as if it had.
		minecraft.setWindowActive(true);
		List<EntrypointContainer<FabricClientGameTest>> tests = FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, FabricClientGameTest.class);
		Path result = FabricLoader.getInstance().getGameDir().resolve("gameshim-result.txt");
		Thread thread = new Thread(() -> run(tests, result), "Client game test (Diorama shim)");
		thread.setDaemon(true);
		ShimThreading.testThread = thread;
		thread.start();
	}

	private static void run(List<EntrypointContainer<FabricClientGameTest>> tests, Path result) {
		String outcome;
		try {
			Files.deleteIfExists(result);
			if (tests.isEmpty()) {
				throw new AssertionError("No " + ENTRYPOINT + " entrypoints to run");
			}
			for (EntrypointContainer<FabricClientGameTest> test : tests) {
				String name = test.getProvider().getMetadata().getId() + " " + test.getEntrypoint().getClass().getName();
				LOGGER.info("Running client game test {}", name);
				ShimContext context = new ShimContext();
				context.prepare();
				test.getEntrypoint().runTest(context);
				finalCheck(context, name);
				LOGGER.info("Client game test {} passed", name);
			}
			outcome = "PASS\n";
			LOGGER.info("GAMESHIM PASS: {} client game test(s) passed", tests.size());
		} catch (Throwable throwable) {
			failed = true;
			StringWriter trace = new StringWriter();
			throwable.printStackTrace(new PrintWriter(trace));
			outcome = "FAIL\n" + trace;
			LOGGER.error("GAMESHIM FAIL: a client game test failed", throwable);
		}
		try {
			Files.writeString(result, outcome, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Couldn't write {}", result, e);
		}
		finished = true;
		ShimThreading.onClientLater(() -> Minecraft.getInstance().stop());
	}

	/** Puts back what a test may have changed, and checks it ended where Fabric's runner wants it. */
	private static void finalCheck(ShimContext context, String name) {
		context.getInput().releaseAll();
		context.runOnClient(minecraft -> ((WindowHooks) (Object) minecraft.getWindow()).diorama$resetSize());
		context.runOnClient(minecraft -> {
			if (minecraft.getSingleplayerServer() != null) {
				throw new AssertionError("Client game test " + name + " finished while a server is still running");
			}
			if (minecraft.level != null) {
				throw new AssertionError("Client game test " + name + " finished while still in a world");
			}
			if (!(minecraft.screen instanceof TitleScreen)) {
				throw new AssertionError("Client game test " + name + " did not finish on the title screen");
			}
		});
	}
}
