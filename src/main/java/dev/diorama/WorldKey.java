package dev.diorama;

import dev.diorama.mixin.BiomeManagerAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Names the world the client is in, so per-world data (waypoints, the saved map) lands in its
 * own folder: the save folder for singleplayer; for multiplayer, the server address and the
 * server's name for the world, since one address can lead to several worlds (a network's lobby
 * and survival servers, or a map reset).
 *
 * <p>Servers built around Xaero's mods name their worlds with a level ID (see
 * {@link XaeroChannels}). Other servers name nothing, and the world is the hashed seed the
 * server sends with every level instead. The seed is the same in each dimension and stays put
 * when the spawn moves, but worlds that share one share a folder.
 *
 * <p>A level ID can come a moment after the level: Xaero's mods send it with the level,
 * MapModCompanion a second later. So on a server that declared one of the channels that come
 * with IDs, or already sent one, the map and waypoints wait for it a moment rather than open the
 * seed's folder, which may hold another world. An ID that comes later still moves the world to
 * its own folder, dropping what the map took from the loaded chunks so far: the new map takes it
 * again. Maps saved before Diorama read level IDs are under the seed: the first world named with
 * that seed takes the folder over.
 */
public final class WorldKey {
	/** How long to wait for a level ID after joining a server that may send one. */
	private static final long FIRST_WAIT_MS = 3000;
	/**
	 * How long to wait after a dimension change. A server that named the last level names the
	 * next as it arrives, or not at all: some only send an ID when the player changes worlds.
	 */
	private static final long LATER_WAIT_MS = 1000;

	/** What's known about a server's level. Kept per level object, which a respawn keeps. */
	private static final class Naming {
		/** The ID the server sent for this level. */
		@Nullable Integer id;
		final long since = System.currentTimeMillis();
		/** A folder was handed out for this level, so it doesn't start waiting later. */
		boolean decided;
	}

	private static final Map<ClientLevel, Naming> LEVELS = new WeakHashMap<>();
	/** The last ID this server sent since the player joined it, for levels it doesn't name. */
	private static @Nullable Integer lastId;
	/** This server let a wait run out without naming its world, so it isn't waited on again. */
	private static boolean silent;

	private WorldKey() {
	}

	/** The world the client is in, or null outside one. */
	public static @Nullable String current() {
		return current(Minecraft.getInstance().level);
	}

	/** The world a level belongs to, or null outside one or while waiting for its name. */
	public static @Nullable String current(@Nullable ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return key("singleplayer", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		}
		ServerData data = mc.getCurrentServer();
		if (data == null || level == null) {
			return null;
		}
		Naming naming = LEVELS.computeIfAbsent(level, l -> new Naming());
		if (naming.id == null && !naming.decided && expectingId(naming)) {
			return null;
		}
		naming.decided = true;
		Integer id = naming.id != null ? naming.id : lastId;
		return id != null ? idKey(data, id) : seedKey(data, level);
	}

	/** Whether {@link #current} last said to wait for this level's ID, without asking again. */
	public static boolean waiting(@Nullable ClientLevel level) {
		Naming naming = level == null ? null : LEVELS.get(level);
		return naming != null && !naming.decided;
	}

	private static boolean expectingId(Naming naming) {
		if (silent || !XaeroChannels.listening() || lastId == null && !XaeroChannels.serverMayNameWorlds()) {
			return false;
		}
		if (System.currentTimeMillis() - naming.since < (lastId == null ? FIRST_WAIT_MS : LATER_WAIT_MS)) {
			return true;
		}
		if (lastId == null) {
			silent = true;
			Diorama.LOGGER.info("The server sent no level ID, so its world is named by its seed");
		}
		return false;
	}

	private static String address(ServerData data) {
		return key(data.isRealm() ? "realms" : "server", data.ip);
	}

	private static String idKey(ServerData data, int id) {
		return address(data) + "-id-" + HexFormat.of().toHexDigits(id);
	}

	/** The folder for a server's world before Diorama read level IDs. */
	private static String seedKey(ServerData data, ClientLevel level) {
		return address(data) + "-world-" + HexFormat.of().toHexDigits(((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed());
	}

	/**
	 * The server named the level the client is in. A map already open for it, under the seed or
	 * the last ID, is dropped without saving: it only holds the loaded chunks, which the next map
	 * takes again. A folder saved under the seed, with nothing saved under the ID yet, is moved
	 * over: it's this world's map from before Diorama read IDs.
	 */
	public static void serverLevelId(int id) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		ServerData data = mc.getCurrentServer();
		if (level == null || data == null || mc.getSingleplayerServer() != null) {
			return;
		}
		Naming naming = LEVELS.computeIfAbsent(level, l -> new Naming());
		lastId = id;
		silent = false;
		if (Integer.valueOf(id).equals(naming.id)) {
			return;
		}
		naming.id = id;
		String key = idKey(data, id);
		Diorama.LOGGER.info("The server names this world {}", HexFormat.of().toHexDigits(id));
		MapSession.discardUnless(key);
		Path seedFolder = folder(seedKey(data, level));
		Path idFolder = folder(key);
		if (Files.isDirectory(seedFolder) && !Files.exists(idFolder)) {
			move(seedFolder, idFolder);
		}
	}

	/** Moves a world's folder, retrying for a moment in case a background thread still has a file in it open. */
	private static void move(Path from, Path to) {
		for (int attempt = 1; ; attempt++) {
			try {
				Files.move(from, to);
				Diorama.LOGGER.info("Moved this world's map from {} to {}", from.getFileName(), to.getFileName());
				return;
			} catch (IOException e) {
				if (attempt == 10) {
					Diorama.LOGGER.warn("Couldn't move {} to {}", from, to, e);
					return;
				}
				try {
					Thread.sleep(100);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		LEVELS.clear();
		lastId = null;
		silent = false;
	}

	/** {@code .minecraft/diorama/<world>}. */
	public static Path folder(String key) {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve(key);
	}

	/**
	 * A readable folder name plus a short hash of the exact identity, so worlds whose names clean
	 * up the same ("My World", "My_World") still get separate folders.
	 */
	public static String key(String kind, String identity) {
		return kind + "-" + sanitize(identity) + "-" + hash(kind + ":" + identity);
	}

	private static String hash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sanitize(String name) {
		String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		return cleaned.isEmpty() ? "unnamed" : cleaned;
	}
}
