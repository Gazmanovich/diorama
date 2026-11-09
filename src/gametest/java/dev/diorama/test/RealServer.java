package dev.diorama.test;

import dev.diorama.Diorama;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * A real dedicated server in its own process, the loader's own server install, with this node's
 * release jar or without it: what a player joins, unlike the test API's server, which runs in the
 * client's own process with the client's classes. Runs through the boat game test skill's
 * real_server.sh, which sandbox.sh passes when a run sets {@code DIORAMA_REAL_SERVER=1}.
 */
final class RealServer implements AutoCloseable {
	private static final long START_SECONDS = 900;

	private final String script;
	private final String node;
	final int port;
	final Path folder;

	private RealServer(String script, String node, int port) {
		this.script = script;
		this.node = node;
		this.port = port;
		this.folder = Path.of(System.getProperty("user.home"), "real-servers", node);
	}

	/** Starts the server, with Diorama in its mods folder or not, and waits until it's up. */
	static RealServer start(boolean withDiorama, String... properties) {
		String script = System.getenv("DIORAMA_REAL_SERVER");
		String node = System.getenv("DIORAMA_NODE");
		if (script == null || node == null) {
			throw new AssertionError("This test needs a real server: run it on boat with --env DIORAMA_REAL_SERVER=1");
		}
		List<String> command = new ArrayList<>(List.of("bash", script, "start", node, withDiorama ? "with" : "without"));
		String output = run(command, String.join("\n", properties), START_SECONDS);
		String[] lines = output.strip().split("\n");
		return new RealServer(script, node, Integer.parseInt(lines[lines.length - 1].strip()));
	}

	/** Runs a command in the server's console. */
	void command(String command) {
		run(List.of("bash", this.script, "cmd", this.node, command), "", 30);
	}

	/**
	 * Runs a console command and waits until the console says {@code done} once more than it had,
	 * for commands like {@code save-all flush} that hold the server for a while on a slow machine.
	 */
	void commandAndWait(String command, String done, int seconds) {
		long before = this.console().lines().filter(line -> line.contains(done)).count();
		this.command(command);
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (this.console().lines().filter(line -> line.contains(done)).count() <= before) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("The server never said \"" + done + "\" after " + command);
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	/**
	 * When the server last wrote a chunk of the overworld to its region file, in seconds, from the
	 * file's header, or 0 if it hasn't.
	 */
	int savedAt(int chunkX, int chunkZ) {
		String name = "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
		for (Path region : List.of(this.folder.resolve("world").resolve("region"), this.folder.resolve("world/dimensions/minecraft/overworld/region"))) {
			Path file = region.resolve(name);
			if (Files.isRegularFile(file)) {
				try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file.toFile(), "r")) {
					in.seek(4096 + 4L * (((chunkZ & 31) << 5) | (chunkX & 31)));
					return in.readInt();
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			}
		}
		return 0;
	}

	/** The server's console output so far. */
	String console() {
		try {
			return Files.readString(this.folder.resolve("console.log"), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * A player without Diorama, the vanilla client with no mods, joins as {@code name}, stays for
	 * {@code seconds} and leaves. Returns what it printed; throws if it couldn't join or was disconnected.
	 */
	String vanillaPlayer(String name, int seconds) {
		return run(List.of("bash", this.script, "vanilla", this.node, name, Integer.toString(seconds)), "", seconds + 900);
	}

	/** As {@link #vanillaPlayer}, but staying until {@link #vanillaPlayerLeaves}, for the console to move them about. */
	void vanillaPlayerJoins(String name) {
		run(List.of("bash", this.script, "vanilla-start", this.node, name), "", 960);
	}

	void vanillaPlayerLeaves(String name) {
		run(List.of("bash", this.script, "vanilla-stop", this.node, name), "", 120);
	}

	@Override
	public void close() {
		run(List.of("bash", this.script, "stop", this.node), "", 180);
	}

	private static String run(List<String> command, String properties, long seconds) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			builder.environment().put("REAL_SERVER_PROPERTIES", properties);
			Process process = builder.start();
			byte[] out = process.getInputStream().readAllBytes();
			if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new AssertionError(String.join(" ", command) + " took over " + seconds + " s");
			}
			String text = new String(out, StandardCharsets.UTF_8);
			Diorama.LOGGER.info("{}: exit {}\n{}", String.join(" ", command), process.exitValue(), text);
			if (process.exitValue() != 0) {
				throw new AssertionError(String.join(" ", command) + " failed (" + process.exitValue() + "): " + text);
			}
			return text;
		} catch (IOException e) {
			throw new AssertionError(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	/** Joins the server from the title screen, and waits until the player is in its world. */
	void join(ClientGameTestContext context) {
		ServerAddress address = new ServerAddress("127.0.0.1", this.port);
		context.runOnClient(mc -> {
			//? if >=1.21 {
			ServerData data = new ServerData("Real server", address.getHost() + ":" + address.getPort(), ServerData.Type.OTHER);
			ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false, null);
			//?} else {
			/*ServerData data = new ServerData("Real server", address.getHost() + ":" + address.getPort(), false);
			ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false);
			*///?}
		});
		context.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, 6000);
	}

	/** Leaves the server for the title screen. */
	static void leave(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			//? if >=1.21.6 {
			mc.level.disconnect(Component.literal("Leaving the real server"));
			mc.disconnect(new GenericMessageScreen(Component.literal("Leaving the real server")), false);
			//?} else {
			/*mc.level.disconnect();
			mc.disconnect(new GenericMessageScreen(Component.literal("Leaving the real server")));
			*///?}
		});
		context.waitFor(mc -> mc.level == null, 1200);
		context.setScreen(TitleScreen::new);
	}
}
