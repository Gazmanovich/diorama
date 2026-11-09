package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.share.ShareClient;
import dev.diorama.share.ShareSettings;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
//?}

/**
 * Diorama's server half (#289): a server with Diorama tells players with Diorama what it shares of
 * its map, and players and servers without Diorama are left alone.
 */
final class ShareTest {
	/** What a server's console says when the server half went wrong. */
	private static final List<String> PROBLEMS = List.of("noclassdeffound", "classnotfound", "for invalid dist", "mixin apply failed", "mixinapplyerror",
		"exception in server tick", "encountered an unexpected exception", "failed to load mod");

	private ShareTest() {
	}

	/** In singleplayer the client reads the save itself: the integrated server says nothing. */
	static void singleplayer(ClientGameTestContext context) {
		if (context.computeOnClient(mc -> ShareClient.settings()) != null) {
			throw new AssertionError("Singleplayer's server told the client it shares its map");
		}
	}

	/**
	 * A real dedicated server, the loader's own install: with Diorama it starts cleanly and tells
	 * the player its settings, and lets a player without Diorama in; without Diorama the player
	 * gets in and hears nothing.
	 */
	static void realServer(ClientGameTestContext context) {
		String console;
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			context.waitFor(mc -> ShareClient.settings() != null, 1200);
			ShareSettings settings = context.computeOnClient(mc -> ShareClient.settings());
			Diorama.LOGGER.info("The real server's settings: {}", settings);
			if (!settings.share() || settings.land() != ShareSettings.Land.VISITED || settings.kilobytesPerSecond() != ShareSettings.DEFAULTS.kilobytesPerSecond()) {
				throw new AssertionError("The real server sent other settings than its defaults: " + settings);
			}
			context.takeScreenshot("real-server-with-diorama");
			String vanilla = server.vanillaPlayer("Vanilla", 15);
			Diorama.LOGGER.info("A player without Diorama on the server with it: {}", vanilla);
			RealServer.leave(context);
			if (context.computeOnClient(mc -> ShareClient.settings()) != null) {
				throw new AssertionError("The server's settings outlived leaving it");
			}
			console = server.console();
		}
		checkConsole(console, true);

		try (RealServer server = RealServer.start(false)) {
			server.join(context);
			context.waitTicks(200);
			if (context.computeOnClient(mc -> ShareClient.settings()) != null) {
				throw new AssertionError("A server without Diorama told the client it shares its map");
			}
			context.takeScreenshot("real-server-without-diorama");
			RealServer.leave(context);
			console = server.console();
		}
		checkConsole(console, false);
	}

	private static void checkConsole(String console, boolean withDiorama) {
		// A failure counts if Diorama or a client class is in it: a server's console has others of
		// its own, such as NeoForge 26.x looking for macOS's network transport on Linux.
		String lower = console.toLowerCase(Locale.ROOT);
		for (String problem : PROBLEMS) {
			for (int at = lower.indexOf(problem); at >= 0; at = lower.indexOf(problem, at + 1)) {
				int start = Math.max(0, console.lastIndexOf('\n', at) + 1);
				// The line and its stack trace: up to the next line of the log.
				int next = console.indexOf("\n[", at);
				int end = Math.min(console.length(), Math.min(next < 0 ? console.length() : next, at + 3000));
				String around = lower.substring(start, end);
				if (around.contains("diorama") || around.contains("net/minecraft/client") || around.contains("net.minecraft.client")) {
					throw new AssertionError("The real server's console has a problem: " + console.substring(start, end));
				}
			}
		}
		boolean loaded = console.contains("Diorama on the server: sharing the map");
		if (loaded != withDiorama) {
			throw new AssertionError(withDiorama ? "The server half didn't start on the real server" : "Diorama started on a server without it");
		}
		console.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains("diorama")).forEach(line -> Diorama.LOGGER.info("Real server: {}", line));
	}

	//? if >=1.21.4 && fabric {
	/**
	 * The test API's dedicated server: the settings file is written with the defaults the first
	 * time, the client hears them as it joins, an owner's change to the file is what the next start
	 * sends, and leaving forgets them.
	 */
	static void settings(ClientGameTestContext context) {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(ShareSettings.FILE);
		try {
			Files.deleteIfExists(file);
			ShareSettings first = join(context);
			if (!first.equals(ShareSettings.DEFAULTS)) {
				throw new AssertionError("A server with no settings file sent " + first + ", not the defaults");
			}
			if (!Files.readString(file).contains("share-land=visited")) {
				throw new AssertionError("The server didn't write its settings file with the defaults: " + Files.readString(file));
			}
			Files.writeString(file, "share-map=false\nshare-land=generated\nshare-kilobytes-per-second=64\n");
			ShareSettings second = join(context);
			if (!second.equals(new ShareSettings(false, ShareSettings.Land.GENERATED, 64))) {
				throw new AssertionError("The server sent " + second + " for an owner's settings of no sharing, generated land, 64 KB/s");
			}
			Files.delete(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static ShareSettings join(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.createServer(DioramaClientTest.serverProperties())) {
			ShareSettings settings;
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> ShareClient.settings() != null, 1200);
				settings = context.computeOnClient(mc -> ShareClient.settings());
			}
			if (context.computeOnClient(mc -> ShareClient.settings()) != null) {
				throw new AssertionError("The server's settings outlived leaving it");
			}
			return settings;
		}
	}
	//?}
}
