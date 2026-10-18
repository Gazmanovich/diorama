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
 * <p>A level ID can come a moment after the level, so the folder isn't decided while one is
 * still expected: from a server that declared Xaero's channels, or one that sent an ID before.
 * The map and waypoints wait for it rather than open the seed's folder, which may hold another
 * world. Maps saved before Diorama read level IDs are under the seed: the first world named with
 * that seed takes the folder over.
 */
public final class WorldKey {
	/** How long to wait for a level ID after joining a server that may send one. */
	private static final long FIRST_WAIT_MS = 3000;
	/**
	 * How long to wait after a respawn or a dimension change. Servers that named the last level
	 * name the next one along with it, if at all: some only send an ID when the world changes.
	 */
	private static final long LATER_WAIT_MS = 1000;

	/** The level the server last named, and the name. */
	private static @Nullable ClientLevel namedLevel;
	private static int namedId;
	/** The last ID this server sent since the player joined it, for levels it doesn't name. */
	private static @Nullable Integer lastId;
	/** The level an ID is being waited for, and since when. */
	private static @Nullable ClientLevel waitingLevel;
	private static long waitingSince;

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
		if (data == null || level == null || waiting(level)) {
			return null;
		}
		Integer id = level == namedLevel ? Integer.valueOf(namedId) : lastId;
		return id != null ? address(data) + "-id-" + HexFormat.of().toHexDigits(id) : seedKey(data, level);
	}

	private static String address(ServerData data) {
		return key(data.isRealm() ? "realms" : "server", data.ip);
	}

	/** The folder for a server's world before Diorama read level IDs. */
	private static String seedKey(ServerData data, ClientLevel level) {
		return address(data) + "-world-" + HexFormat.of().toHexDigits(((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed());
	}

	/**
	 * Whether a level ID for this level may still come: the server may name its worlds and the
	 * level is new enough. Starts the wait the first time it's asked about a level.
	 */
	public static boolean waiting(@Nullable ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		if (level == null || level == namedLevel || mc.getSingleplayerServer() != null || lastId == null && !XaeroChannels.serverMayNameWorlds()) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (level != waitingLevel) {
			waitingLevel = level;
			waitingSince = now;
		}
		return now - waitingSince < (lastId == null ? FIRST_WAIT_MS : LATER_WAIT_MS);
	}

	/**
	 * The server named the level the client is in. If that moves the world to another folder, the
	 * map open on the old one closes and reopens on the new one next tick. A folder saved under
	 * the seed with nothing saved under the ID yet is moved over: it's this world's map from
	 * before Diorama read IDs, or this world's first moments here, if the ID came late.
	 */
	public static void serverLevelId(int id) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		ServerData data = mc.getCurrentServer();
		if (level == null || data == null || mc.getSingleplayerServer() != null) {
			return;
		}
		String before = current(level);
		namedLevel = level;
		namedId = id;
		lastId = id;
		String after = current(level);
		if (after == null || after.equals(before)) {
			return;
		}
		Diorama.LOGGER.info("The server names this world {}", id);
		if (before != null) {
			MapSession.close();
		}
		Path seedFolder = folder(seedKey(data, level));
		Path idFolder = folder(after);
		if (Files.isDirectory(seedFolder) && !Files.exists(idFolder)) {
			try {
				Files.move(seedFolder, idFolder);
				Diorama.LOGGER.info("Moved this world's map from {} to {}", seedFolder.getFileName(), idFolder.getFileName());
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't move {} to {}", seedFolder, idFolder, e);
			}
		}
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		namedLevel = null;
		lastId = null;
		waitingLevel = null;
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
