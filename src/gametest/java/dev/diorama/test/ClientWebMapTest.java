package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.web.ClientWebMap;
import dev.diorama.web.WebMap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The player's own map in a browser, in a singleplayer world (opt-in: {@code web-map-client}).
 * Off, nothing listens. On, the game serves the page, the map file, the player's place and the
 * tiles over HTTP; the tile under the player is the player's own map of it, as near as a JPEG
 * gets; and the place it gives moves when the player does. Where the machine has Chromium, the
 * page itself is photographed, before and after the move, into {@code screenshots/web-map-client/}.
 */
final class ClientWebMapTest {
	private static final Pattern NUMBER = Pattern.compile("\"(x|y|z|u|v|heading)\":(-?[0-9.]+)");

	private ClientWebMapTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		Path out = Path.of("screenshots", "web-map-client");
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		HttpClient http = HttpClient.newHttpClient();
		// What a singleplayer world did before: the server's web map only starts on a dedicated server.
		if (WebMap.get() != null) {
			throw new AssertionError("The server's web map runs in singleplayer");
		}
		if (context.computeOnClient(mc -> Settings.get().webMap) != Settings.WebMap.OFF || ClientWebMap.servingPort() != 0) {
			throw new AssertionError("The web map is on before the player turned it on");
		}
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8));
		world.getServer().runCommand("tp @a 8.5 " + ground + " 8.5 -90 0");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == 8, 600);
		DioramaClientTest.settle(context, 40);

		// A taken port: the web map takes the next one.
		int port;
		try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			int first = taken.getLocalPort();
			context.runOnClient(mc -> {
				Settings.get().webMapPort = first;
				Settings.get().webMap = Settings.WebMap.THIS_COMPUTER;
			});
			context.waitTicks(3);
			port = ClientWebMap.servingPort();
			// The last few ports there are have none after them to take.
			if (first < 65500 && (port <= first || port >= first + 10)) {
				throw new AssertionError("With port " + first + " taken, the web map is on " + port);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (port == 0) {
			throw new AssertionError("The web map isn't serving after it was turned on");
		}
		Diorama.LOGGER.info("web-map-client: serving on {}", port);
		// The address is in chat, to click.
		context.waitTicks(5);
		context.takeScreenshot("web-map-client-chat");

		HttpResponse<String> page = get(http, port, "/");
		if (page.statusCode() != 200 || !page.body().contains("Diorama web map") || !page.body().contains("player.json")) {
			throw new AssertionError("The page: " + page.statusCode());
		}
		// The tiles reach the disk a few seconds after they're drawn, and the overviews after that.
		String map = "";
		for (int i = 0; i < 200; i++) {
			map = get(http, port, "/map.json").body();
			if (map.contains("\"zoomLevels\":6")) {
				break;
			}
			context.waitTicks(10);
		}
		if (!map.contains("\"live\":true") || !map.contains("\"id\":\"minecraft:overworld\"") || !map.contains("\"zoomLevels\":6")) {
			throw new AssertionError("map.json: " + map.substring(0, Math.min(400, map.length())));
		}
		double[] before = player(http, port);
		double[] at = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		if (Math.abs(before[0] - at[0]) > 0.01 || Math.abs(before[2] - at[2]) > 0.01 || Math.abs(before[3] - at[0]) > 0.01
			|| Math.abs(before[4] - Projection.v(at[1], at[2])) > 0.01) {
			throw new AssertionError("player.json says " + java.util.Arrays.toString(before) + " for a player at " + java.util.Arrays.toString(at));
		}
		// Looking east: along the picture's x.
		if (Math.abs(before[5]) > 0.05) {
			throw new AssertionError("A player looking east has heading " + before[5]);
		}

		// The tile the player stands in, against the player's own map of it.
		int tileU = Math.floorDiv((int) Math.floor(before[3]), Projection.TILE_UNITS);
		int tileV = Math.floorDiv((int) Math.floor(before[4]), Projection.TILE_UNITS);
		if (!map.contains("[" + tileU + "," + tileV + ",") && !map.contains("," + tileU + "," + tileV + ",")) {
			throw new AssertionError("map.json doesn't list the player's tile " + tileU + "," + tileV);
		}
		int[] own = context.computeOnClient(mc -> MapSession.get().map().savedMap()).read(new TileKey(TileKey.SURFACE, tileU, tileV));
		HttpResponse<byte[]> tile = getBytes(http, port, "/minecraft_overworld/0/" + tileU + "_" + tileV + ".jpg?v=1");
		if (own == null || tile.statusCode() != 200 || !tile.headers().firstValue("Content-Type").orElse("").equals("image/jpeg")) {
			throw new AssertionError("The player's tile: " + tile.statusCode() + (own == null ? ", and the map has none" : ""));
		}
		try {
			Files.write(out.resolve("tile-" + tileU + "_" + tileV + ".jpg"), tile.body());
			BufferedImage web = ImageIO.read(new ByteArrayInputStream(tile.body()));
			int size = Projection.TILE_PIXELS;
			if (web == null || web.getWidth() != size || web.getHeight() != size) {
				throw new AssertionError("The player's tile isn't a " + size + " pixel picture");
			}
			long sum = 0;
			int known = 0;
			for (int i = 0; i < own.length; i++) {
				int c = own[i];
				if (c >>> 24 != 255) {
					continue;
				}
				int w = web.getRGB(i % size, i / size);
				sum += Math.max(Math.abs((c & 0xFF) - (w >> 16 & 0xFF)), Math.max(Math.abs((c >> 8 & 0xFF) - (w >> 8 & 0xFF)), Math.abs((c >> 16 & 0xFF) - (w & 0xFF))));
				known++;
			}
			double mean = known == 0 ? 0 : (double) sum / known;
			Diorama.LOGGER.info("web-map-client: tile {},{}: {} known pixels, {} off the player's map on average, {} bytes", tileU, tileV, known, mean, tile.body().length);
			// A JPEG of ground this busy is about 5 off; another tile, or the wrong colours, several times that.
			if (known < size * size / 10 || mean > 12) {
				throw new AssertionError("The served tile is " + mean + " off the player's map over " + known + " known pixels");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// Zoomed out, and nothing outside the map.
		if (getBytes(http, port, "/minecraft_overworld/3/" + Math.floorDiv(tileU, 8) + "_" + Math.floorDiv(tileV, 8) + ".jpg").statusCode() != 200) {
			throw new AssertionError("No zoomed-out tile over the player");
		}
		for (String path : new String[] {"/minecraft_overworld/0/9999_9999.jpg", "/minecraft_the_nether/0/" + tileU + "_" + tileV + ".jpg", "/../config.json", "/minecraft_overworld/0/x.jpg", "/minecraft_overworld/9/0_0.jpg"}) {
			int code = getBytes(http, port, path).statusCode();
			if (code != 404) {
				throw new AssertionError(path + " answered " + code);
			}
		}
		browser(port, out.resolve("page-before.png"));

		// The player moves: so does the place the page is given.
		int there = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 72, -40));
		world.getServer().runCommand("tp @a 72.5 " + there + " -39.5 180 0");
		context.waitFor(mc -> mc.player.blockPosition().getX() == 72, 600);
		context.waitTicks(5);
		double[] after = player(http, port);
		if (Math.abs(after[0] - 72.5) > 0.01 || Math.abs(after[2] + 39.5) > 0.01 || Math.abs(after[3] - before[3] - 64) > 0.01) {
			throw new AssertionError("After moving to 72.5, -39.5, player.json says " + java.util.Arrays.toString(after));
		}
		// Looking north: up the picture.
		if (Math.abs(after[5] + Math.PI / 2) > 0.05) {
			throw new AssertionError("A player looking north has heading " + after[5]);
		}
		DioramaClientTest.settle(context, 40);
		browser(port, out.resolve("page-after.png"));

		// For every device on the network: the same port, on every address.
		int served = port;
		context.runOnClient(mc -> {
			Settings.get().webMapPort = served;
			Settings.get().webMap = Settings.WebMap.NETWORK;
		});
		context.waitTicks(3);
		if (ClientWebMap.servingPort() != served || get(http, served, "/map.json").statusCode() != 200) {
			throw new AssertionError("The web map isn't serving on " + served + " for the network");
		}
		Diorama.LOGGER.info("web-map-client: for the network at {}", ClientWebMap.address());
		// Off: nothing listens any more.
		context.runOnClient(mc -> Settings.get().webMap = Settings.WebMap.OFF);
		context.waitTicks(3);
		if (ClientWebMap.servingPort() != 0) {
			throw new AssertionError("The web map still serves after it was turned off");
		}
		try {
			get(http, served, "/");
			throw new AssertionError("Port " + served + " still answers after the web map was turned off");
		} catch (UncheckedIOException e) {
			// Refused.
		}
		context.runOnClient(mc -> Settings.get().webMapPort = Settings.DEFAULT_WEB_MAP_PORT);
	}

	/** x, y, z, u, v and heading from player.json. */
	private static double[] player(HttpClient http, int port) {
		String json = get(http, port, "/player.json").body();
		double[] values = new double[6];
		Matcher matcher = NUMBER.matcher(json);
		int found = 0;
		while (matcher.find()) {
			values[java.util.List.of("x", "y", "z", "u", "v", "heading").indexOf(matcher.group(1))] = Double.parseDouble(matcher.group(2));
			found++;
		}
		if (found != 6 || !json.contains("\"dimension\":\"minecraft:overworld\"")) {
			throw new AssertionError("player.json: " + json);
		}
		return values;
	}

	/** Photographs the page in Chromium, where the machine has one. */
	private static void browser(int port, Path file) {
		for (String name : new String[] {"chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "/snap/bin/chromium"}) {
			try {
				Process process = new ProcessBuilder(name, "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--window-size=1280,800",
					"--virtual-time-budget=20000", "--screenshot=" + file.toAbsolutePath(), "http://127.0.0.1:" + port + "/")
					.redirectErrorStream(true).redirectOutput(file.resolveSibling(file.getFileName() + ".log").toFile()).start();
				if (!process.waitFor(180, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					Diorama.LOGGER.warn("web-map-client: {} didn't finish photographing the page", name);
					return;
				}
				Diorama.LOGGER.info("web-map-client: {} photographed the page into {} (exit {}, {})", name, file, process.exitValue(), Files.exists(file) ? "written" : "no file");
				return;
			} catch (IOException e) {
				// Not this one.
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
		Diorama.LOGGER.warn("web-map-client: no Chromium on this machine, so no picture of the page");
	}

	private static HttpResponse<String> get(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static HttpResponse<byte[]> getBytes(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
}
