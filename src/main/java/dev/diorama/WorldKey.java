package dev.diorama;

import dev.diorama.mixin.BiomeManagerAccessor;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
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
	private static @Nullable Last last;

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
			// An integrated server runs one save for its whole life.
			Last seen = last;
			if (seen != null && seen.matches(server, null, null, false)) {
				return seen.key;
			}
			String key = key("singleplayer", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
			last = new Last(new WeakReference<>(server), null, null, false, key);
			return key;
		}
		ServerData data = mc.getCurrentServer();
		if (data == null || level == null) {
			return null;
		}
		// A server list entry can be edited in place, so its address and kind are checked too. A
		// level's seed never changes.
		boolean realm = data.isRealm();
		Last seen = last;
		if (seen != null && seen.matches(data, level, data.ip, realm)) {
			return seen.key;
		}
		String address = key(realm ? "realms" : "server", data.ip);
		String key = address + "-world-" + HexFormat.of().toHexDigits(((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed());
		last = new Last(new WeakReference<>(data), new WeakReference<>(level), data.ip, realm, key);
		return key;
	}

	/**
	 * The last key handed out and what it was worked out from. The HUD and the map ask every
	 * frame, and working it out means a hash and a regex each time. The objects are held weakly
	 * so a world that was left can still be freed; one that was freed can't match a live one.
	 */
	private record Last(WeakReference<Object> source, @Nullable WeakReference<ClientLevel> level, @Nullable String ip, boolean realm, String key) {
		boolean matches(Object source, @Nullable ClientLevel level, @Nullable String ip, boolean realm) {
			return this.source.get() == source
				&& (this.level == null ? level == null : this.level.get() == level)
				&& Objects.equals(this.ip, ip)
				&& this.realm == realm;
		}
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
