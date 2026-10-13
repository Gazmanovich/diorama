package dev.orthomap;

import java.nio.file.Path;
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
			return "singleplayer-" + sanitize(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
		}
		ServerData data = mc.getCurrentServer();
		if (data != null) {
			return (data.isRealm() ? "realms-" : "server-") + sanitize(data.ip);
		}
		return null;
	}

	/** {@code .minecraft/orthomap/<world>}. */
	public static Path folder(String key) {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Orthomap.MOD_ID).resolve(key);
	}

	private static String sanitize(String name) {
		String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		return cleaned.isEmpty() ? "unnamed" : cleaned;
	}
}
