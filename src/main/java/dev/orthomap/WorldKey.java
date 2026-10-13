package dev.orthomap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Names the world the client is in, so per-world data (waypoints, the saved map) lands in its
 * own folder: the save folder for singleplayer, the server address for multiplayer.
 */
public final class WorldKey {
	private WorldKey() {
	}

	public static @Nullable String current() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return key("singleplayer", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		}
		ServerData data = mc.getCurrentServer();
		if (data != null) {
			return key(data.isRealm() ? "realms" : "server", data.ip);
		}
		return null;
	}

	/** {@code .minecraft/orthomap/<world>}. */
	public static Path folder(String key) {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Orthomap.MOD_ID).resolve(key);
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
