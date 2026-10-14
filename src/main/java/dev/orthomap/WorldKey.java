package dev.orthomap;

import dev.orthomap.mixin.BiomeManagerAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Names the world the client is in, so per-world data (waypoints, the saved map) lands in its
 * own folder: the save folder for singleplayer; for multiplayer, the server address and the
 * hashed world seed the server sends with every level, since one address can lead to several
 * worlds (a network's lobby and survival servers, or a map reset). The seed is the same in each
 * dimension and stays put when the spawn moves.
 */
public final class WorldKey {
	/** Per server address: whether its map from before worlds were told apart is still in use this run. */
	private static final Map<String, Boolean> UNSORTED = new HashMap<>();

	private WorldKey() {
	}

	/** The world the client is in, or null outside one. */
	public static @Nullable String current() {
		return current(Minecraft.getInstance().level);
	}

	/** The world a level belongs to, or null outside one. */
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
		String address = key(data.isRealm() ? "realms" : "server", data.ip);
		String world = address + "-world-" + HexFormat.of().toHexDigits(((BiomeManagerAccessor) level.getBiomeManager()).orthomap$biomeZoomSeed());
		return stillUnsorted(address, world) ? address : world;
	}

	/** {@code .minecraft/orthomap/<world>}. */
	public static Path folder(String key) {
		return root().resolve(key);
	}

	private static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Orthomap.MOD_ID);
	}

	/**
	 * A map saved before a server's worlds were told apart is in a folder named for the address
	 * alone. The first world seen there takes it over by renaming the folder, all at once or not
	 * at all. If that fails, this run keeps using the old folder, as before, and the next one
	 * tries again. Checked once per address per run.
	 */
	private static synchronized boolean stillUnsorted(String address, String world) {
		Boolean known = UNSORTED.get(address);
		if (known != null) {
			return known;
		}
		boolean unsorted = false;
		Path old = root().resolve(address);
		if (Files.isDirectory(old) && !hasWorlds(address)) {
			try {
				Files.move(old, root().resolve(world), StandardCopyOption.ATOMIC_MOVE);
				Orthomap.LOGGER.info("Moved the map saved for {} to {}", address, world);
			} catch (IOException e) {
				Orthomap.LOGGER.error("Couldn't move the map saved for {} to {}; using it as it is for now", address, world, e);
				unsorted = true;
			}
		}
		UNSORTED.put(address, unsorted);
		return unsorted;
	}

	private static boolean hasWorlds(String address) {
		try (Stream<Path> list = Files.list(root())) {
			return list.anyMatch(p -> p.getFileName().toString().startsWith(address + "-world-"));
		} catch (IOException e) {
			return false;
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
