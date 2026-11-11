package dev.diorama.web;

import com.sun.net.httpserver.HttpExchange;
import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The player's own map in a browser, for a second screen: a small web server inside the game,
 * as JourneyMap's web map has, showing the map the player is on with their arrow moving over it.
 * Off unless the player turns it on ({@link Settings#webMap}), for this computer only or for the
 * devices on its network.
 *
 * <p>It serves the same page as a server's web map ({@link WebMap}), but keeps no pictures of its
 * own: each tile is made on request from the map's saved tile, so the page shows what the player
 * has explored, in their resource pack, a few seconds after the map itself does.
 */
public final class ClientWebMap {
	/** A taken port is passed over for the next, this many times, so the address changes as little as it can. */
	private static final int PORT_TRIES = 10;
	/** The tile list is listed from the map's folder at most this often, however many pages ask. */
	private static final long LIST_MS = 1000;

	private static @Nullable WebServer http;
	/** The settings the server was last started, or failed to start, under. */
	private static Settings.WebMap mode = Settings.WebMap.OFF;
	private static int port;
	/** Why it isn't serving though it's on; null when it is. */
	private static @Nullable Component failure;
	/** Whether this world's chat has been told the address. */
	private static boolean announced;
	private static volatile @Nullable Live live;
	/** Whether only this computer's browsers are served ({@link Settings.WebMap#THIS_COMPUTER}). */
	private static volatile boolean thisComputerOnly;
	private static byte @Nullable [] page;
	private static final Object LIST_LOCK = new Object();
	private static @Nullable TileStore listedStore;
	private static long listedAt;
	private static String listed = "";

	private ClientWebMap() {
	}

	/** The map being shown, as the client thread last saw it. */
	private record Live(TileStore store, String dimension, String folder, double sin, double cos, Facing facing, String player) {
	}

	/** The port the page is served on, or 0 when it isn't. */
	public static int servingPort() {
		WebServer server = http;
		return server == null ? 0 : server.port();
	}

	/** Follows the setting and the map the player is on. Call every client tick. */
	public static void tick(Minecraft mc) {
		Settings settings = Settings.get();
		if (settings.webMap != mode || settings.webMapPort != port) {
			stop();
			mode = settings.webMap;
			port = settings.webMapPort;
			announced = false;
			if (mode != Settings.WebMap.OFF) {
				start();
			}
		}
		live = http == null ? null : snapshot(mc);
		if (mc.level == null || mc.player == null) {
			announced = false;
		} else if (!announced && mode != Settings.WebMap.OFF) {
			announced = true;
			Component message = http != null ? Component.translatable("diorama.web_map.ready", link(address())) : failure;
			if (message != null) {
				//? if >=26.1 {
				mc.gui.hud.getChat().addClientSystemMessage(message);
				//?} else {
				/*mc.gui.getChat().addMessage(message);
				*///?}
			}
		}
	}

	/** Stops serving. For closing the game. */
	public static void stop() {
		if (http != null) {
			http.stop();
			http = null;
		}
		live = null;
		failure = null;
		mode = Settings.WebMap.OFF;
	}

	private static void start() {
		String bind = mode == Settings.WebMap.THIS_COMPUTER ? "127.0.0.1" : "";
		thisComputerOnly = mode == Settings.WebMap.THIS_COMPUTER;
		try {
			for (int i = 0; i < PORT_TRIES && http == null && port + i <= 65535; i++) {
				try {
					http = WebServer.start(bind, port + i, ClientWebMap::serve);
				} catch (BindException e) {
					// Taken, by another game or program: the next one.
				}
			}
			if (http == null) {
				Diorama.LOGGER.warn("Diorama's web map found no free port from {} to {}", port, port + PORT_TRIES - 1);
				failure = Component.translatable("diorama.web_map.no_port", port, port + PORT_TRIES - 1);
			} else {
				failure = null;
				Diorama.LOGGER.info("Diorama's web map is at {}", address());
			}
		} catch (IOException | RuntimeException | NoClassDefFoundError e) {
			// No port at all, or a Java without the built-in web server.
			Diorama.LOGGER.warn("Diorama's web map couldn't start", e);
			failure = Component.translatable("diorama.web_map.failed", String.valueOf(e.getMessage() == null ? e : e.getMessage()));
		}
	}

	/** Where a browser finds the page: this computer's name for itself, or its address on the network. */
	public static String address() {
		int serving = servingPort();
		String host = mode == Settings.WebMap.NETWORK ? networkAddress() : null;
		return "http://" + (host == null ? "localhost" : host) + ":" + serving + "/";
	}

	/**
	 * This computer's address on its network, as another device there would reach it; null if it
	 * has none. A UDP socket pointed at an outside address sends nothing, and says which of the
	 * computer's addresses would be used for it: the one on the network that leads out.
	 */
	private static @Nullable String networkAddress() {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(new InetSocketAddress("192.0.2.1", 9));
			InetAddress local = socket.getLocalAddress();
			if (local instanceof Inet4Address && !local.isAnyLocalAddress() && !local.isLoopbackAddress()) {
				return local.getHostAddress();
			}
		} catch (IOException | RuntimeException e) {
			// No route out: look at the interfaces.
		}
		try {
			for (NetworkInterface network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (InetAddress address : java.util.Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						return address.getHostAddress();
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// None to be found.
		}
		return null;
	}

	private static Component link(String address) {
		//? if >=1.21.5 {
		ClickEvent open = new ClickEvent.OpenUrl(java.net.URI.create(address));
		//?} else {
		/*ClickEvent open = new ClickEvent(ClickEvent.Action.OPEN_URL, address);
		*///?}
		return Component.literal(address).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(open)
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("diorama.web_map.open"))));
	}

	/** What the page shows now: the map the player is on, or null outside a world or where the server allows no map. */
	private static @Nullable Live snapshot(Minecraft mc) {
		MapSession session = MapSession.get();
		Entity viewer = MapSession.viewer();
		if (session == null || viewer == null || mc.level == null || !ServerRules.minimapAllowed()) {
			return null;
		}
		TileStore store = session.map().savedMap();
		if (store == null) {
			return null;
		}
		String dimension = mc.level.dimension().identifier().toString();
		Facing facing = Projection.facing();
		// Which way the viewer looks, across the picture: yaw 0 is south, turning clockwise from above.
		double yaw = Math.toRadians(viewer.getYRot());
		double lookX = -Math.sin(yaw);
		double lookZ = Math.cos(yaw);
		double heading = Math.atan2(facing.z(lookX, lookZ) * Projection.sin(), facing.x(lookX, lookZ));
		String player = String.format(Locale.ROOT, "{\"dimension\":\"%s\",\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"u\":%.3f,\"v\":%.3f,\"heading\":%.4f,\"color\":\"#%06x\"}",
			escape(dimension), escape(viewer.getName().getString()), viewer.getX(), viewer.getY(), viewer.getZ(),
			Projection.u(viewer.getX(), viewer.getZ()), Projection.v(viewer.getX(), viewer.getY(), viewer.getZ()), heading, Settings.get().arrowRgb());
		// The pictures' addresses name the map they're of, by the folder it's saved in: a browser keeps a
		// picture under its address, and the same tile of another world or another map of this
		// dimension must not be taken for it.
		String folder = dimension.replace(':', '_').replace('/', '_') + "-" + Integer.toHexString(store.folder().toAbsolutePath().normalize().toString().hashCode());
		return new Live(store, dimension, folder, Projection.sin(), Projection.cos(), facing, player);
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\\') {
				out.append('\\').append(c);
			} else if (c < 0x20 || c > 0x7E) {
				out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static void serve(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if (!method.equals("GET") && !method.equals("HEAD")) {
				WebServer.send(exchange, 405, "text/plain", null);
				return;
			}
			// For this computer only: a page from elsewhere can point a name of its own at 127.0.0.1 and
			// have the browser ask here under that name. Only what was asked for as this computer is answered.
			if (thisComputerOnly && !thisComputer(exchange.getRequestHeaders().getFirst("Host"))) {
				WebServer.send(exchange, 403, "text/plain", null);
				return;
			}
			boolean head = method.equals("HEAD");
			String path = exchange.getRequestURI().getPath();
			Live now = live;
			exchange.getResponseHeaders().set("Cache-Control", "no-cache");
			if (path.equals("/") || path.equals("/index.html")) {
				byte[] body = page();
				WebServer.send(exchange, body == null ? 404 : 200, "text/html; charset=utf-8", head ? null : body);
			} else if (path.equals("/map.json")) {
				WebServer.send(exchange, 200, "application/json", head ? null : mapJson(now).getBytes(StandardCharsets.UTF_8));
			} else if (path.equals("/player.json")) {
				WebServer.send(exchange, 200, "application/json", head ? null : (now == null ? "{}" : now.player()).getBytes(StandardCharsets.UTF_8));
			} else {
				byte[] tile = now == null ? null : tile(now, path);
				if (tile == null) {
					WebServer.send(exchange, 404, "text/plain", null);
					return;
				}
				// Asked for with its version (the page's way), a picture never changes under that address.
				String query = exchange.getRequestURI().getRawQuery();
				exchange.getResponseHeaders().set("Cache-Control", query != null && query.startsWith("v=") ? "private, max-age=31536000, immutable" : "no-cache");
				WebServer.send(exchange, 200, "image/jpeg", head ? null : tile);
			}
		} catch (IOException | RuntimeException e) {
			// The browser went away, or the map closed under the request.
		} finally {
			exchange.close();
		}
	}

	/** Whether a request's Host names this computer itself: localhost or a loopback address, with any port. */
	private static boolean thisComputer(@Nullable String host) {
		if (host == null) {
			return false;
		}
		String name = host.trim().toLowerCase(Locale.ROOT);
		if (name.startsWith("[")) {
			int end = name.indexOf(']');
			name = end < 0 ? name : name.substring(0, end + 1);
		} else if (name.lastIndexOf(':') >= 0) {
			name = name.substring(0, name.lastIndexOf(':'));
		}
		return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
	}

	private static byte @Nullable [] page() throws IOException {
		if (page == null) {
			try (InputStream in = ClientWebMap.class.getResourceAsStream("/diorama/web/index.html")) {
				page = in == null ? null : in.readAllBytes();
			}
		}
		return page;
	}

	/** A tile's picture from {@code /<dimension>/<zoom>/<u>_<v>.jpg}, or null if the map has none there. */
	private static byte @Nullable [] tile(Live now, String path) throws IOException {
		String[] parts = path.substring(1).split("/");
		if (parts.length != 3 || !parts[0].equals(now.folder()) || !parts[2].endsWith(".jpg")) {
			return null;
		}
		String name = parts[2].substring(0, parts[2].length() - 4);
		int split = name.indexOf('_');
		int[] pixels;
		try {
			int zoom = Integer.parseInt(parts[1]);
			if (split < 1 || zoom < 0 || zoom > TileKey.MAX_LOD) {
				return null;
			}
			pixels = now.store().read(new TileKey(TileKey.SURFACE, Integer.parseInt(name.substring(0, split)), Integer.parseInt(name.substring(split + 1)), zoom));
		} catch (NumberFormatException e) {
			return null;
		}
		return pixels == null ? null : WebTiles.jpeg(pixels);
	}

	/** What the page reads first, as {@link WebMap#writeMapJson} writes it: here one dimension, the one the player is in. */
	private static String mapJson(@Nullable Live now) {
		StringBuilder json = new StringBuilder();
		json.append("{\"tileUnits\":").append(Projection.TILE_UNITS)
			.append(",\"tilePixels\":").append(Projection.TILE_PIXELS)
			.append(",\"sin\":").append(String.format(Locale.ROOT, "%.6f", now == null ? Projection.sin() : now.sin()))
			.append(",\"cos\":").append(String.format(Locale.ROOT, "%.6f", now == null ? Projection.cos() : now.cos()))
			.append(",\"background\":\"#").append(String.format(Locale.ROOT, "%06x", WebTiles.BACKGROUND)).append('"')
			.append(",\"updated\":").append(System.currentTimeMillis())
			// The player's own map: the page follows the player, and asks again sooner.
			.append(",\"live\":true,\"facing\":").append(now == null ? 0 : now.facing().turns)
			.append(",\"dimensions\":[");
		if (now != null) {
			json.append("{\"id\":\"").append(escape(now.dimension())).append("\",\"folder\":\"").append(escape(now.folder())).append("\",").append(tiles(now.store()));
		}
		return json.append("]}").toString();
	}

	/** The page always gets this many zoom levels where the map has them: as many as maps had up to 0.9.0. */
	private static final int PAGE_LEVELS = 6;
	/** A level with this few tiles shows the whole map in a screen or so; see {@link #tiles}. */
	private static final int FEW_TILES = 4;

	/** The map's tiles at each zoom with when each was last written, as {@code "zoomLevels":n,"versions":true,"tiles":[...]}}. */
	private static String tiles(TileStore store) {
		synchronized (LIST_LOCK) {
			long time = System.currentTimeMillis();
			if (store == listedStore && time - listedAt < LIST_MS) {
				return listed;
			}
			StringBuilder[] levels = new StringBuilder[TileKey.MAX_LOD + 1];
			int zoomLevels = 1;
			// The page zooms out to a quarter of its coarsest level. The map's own levels go on far
			// past where all of it is a few tiles across, and each one after that would only show it
			// smaller: the page gets them up to there, as the shared map's makes them (WebMap), and
			// at least the six it got before the full map zoomed further out.
			int enough = TileKey.MAX_LOD;
			for (int lod = 0; lod <= TileKey.MAX_LOD; lod++) {
				StringBuilder level = levels[lod] = new StringBuilder();
				Map<TileKey, Long> saved = store.savedTimes(lod);
				if (lod >= PAGE_LEVELS - 1 && lod < enough && saved.size() <= FEW_TILES) {
					enough = lod;
				}
				for (Map.Entry<TileKey, Long> tile : saved.entrySet()) {
					if (level.length() > 0) {
						level.append(',');
					}
					level.append(tile.getKey().tileU()).append(',').append(tile.getKey().tileV()).append(',').append(tile.getValue());
				}
				if (level.length() > 0 && lod <= enough) {
					zoomLevels = lod + 1;
				}
			}
			StringBuilder json = new StringBuilder("\"zoomLevels\":").append(zoomLevels).append(",\"versions\":true,\"tiles\":[");
			for (int lod = 0; lod < zoomLevels; lod++) {
				json.append(lod > 0 ? "," : "").append('[').append(levels[lod]).append(']');
			}
			listed = json.append("]}").toString();
			listedStore = store;
			listedAt = time;
			return listed;
		}
	}
}
