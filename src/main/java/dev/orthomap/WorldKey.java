package dev.orthomap;

import dev.orthomap.mixin.BiomeManagerAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Names the world the client is in, so per-world data (waypoints, the saved map) lands in its
 * own folder: the save folder for singleplayer; for multiplayer, the server address and then the
 * world's seed, since one address can lead to several worlds (a network's lobby and survival
 * servers, or a map reset).
 */
public final class WorldKey {
	/** Server folders already checked for a map saved before worlds on one address were told apart. */
	private static final Set<String> CHECKED = new HashSet<>();

	private WorldKey() {
	}

	public static @Nullable String current() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return key("singleplayer", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		}
		ServerData data = mc.getCurrentServer();
		ClientLevel level = mc.level;
		if (data == null || level == null) {
			return null;
		}
		String address = key(data.isRealm() ? "realms" : "server", data.ip);
		return address + "/" + world(((BiomeManagerAccessor) level.getBiomeManager()).orthomap$biomeZoomSeed());
	}

	/**
	 * One world on a server, named by the hashed seed the server sends with every level. It's
	 * the same in each dimension, stays put when the spawn moves, and differs between worlds
	 * unless they were made from the same seed.
	 */
	static String world(long hashedSeed) {
		return "world-" + hash(Long.toHexString(hashedSeed));
	}

	/** {@code .minecraft/orthomap/<world>}. */
	public static Path folder(String key) {
		Path root = Minecraft.getInstance().gameDirectory.toPath().resolve(Orthomap.MOD_ID);
		int slash = key.indexOf('/');
		if (slash >= 0) {
			adoptUnsorted(root.resolve(key.substring(0, slash)), key.substring(slash + 1));
		}
		return root.resolve(key);
	}

	/**
	 * A server's map and waypoints saved before its worlds were told apart sit right in the server
	 * folder. The first world seen there takes them, rather than leaving them where nothing reads
	 * them.
	 */
	private static synchronized void adoptUnsorted(Path server, String world) {
		if (!CHECKED.add(server.toString()) || !Files.isDirectory(server)) {
			return;
		}
		try {
			List<Path> entries;
			try (Stream<Path> list = Files.list(server)) {
				entries = list.toList();
			}
			if (entries.isEmpty() || entries.stream().anyMatch(p -> p.getFileName().toString().startsWith("world-"))) {
				return;
			}
			Path target = Files.createDirectories(server.resolve(world));
			for (Path entry : entries) {
				Files.move(entry, target.resolve(entry.getFileName()));
			}
			Orthomap.LOGGER.info("Moved the map saved for {} into {}", server.getFileName(), world);
		} catch (IOException e) {
			Orthomap.LOGGER.error("Couldn't move the map saved for {} into a world folder", server.getFileName(), e);
		}
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
