package dev.diorama.share;

import dev.diorama.Diorama;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Diorama's half on a dedicated server: it tells each player with Diorama what the server shares
 * of its map. It never draws anything and stores nothing; the world save is the data. Loads on a
 * dedicated server, so nothing here may name a client class. The loader's common entrypoint calls
 * in from the server's events.
 *
 * <p>Only dedicated servers share. In singleplayer the client reads the save itself, and a LAN
 * world's host has no settings file for it.
 */
public final class ShareServer {
	private static volatile @Nullable ShareServer running;

	private final MinecraftServer server;
	private final ShareSettings settings;

	private ShareServer(MinecraftServer server, ShareSettings settings) {
		this.server = server;
		this.settings = settings;
	}

	/** The server started; {@code configFolder} is the loader's config folder. */
	public static void started(MinecraftServer server, Path configFolder) {
		if (!server.isDedicatedServer()) {
			return;
		}
		ShareSettings settings = ShareSettings.load(configFolder);
		running = new ShareServer(server, settings);
		Diorama.LOGGER.info("Diorama on the server: {} ({} land, up to {} KB/s a player)",
			settings.share() ? "sharing the map with players who have Diorama" : "not sharing the map",
			settings.land().key(), settings.kilobytesPerSecond());
	}

	public static void stopped(MinecraftServer server) {
		ShareServer share = running;
		if (share != null && share.server == server) {
			running = null;
		}
	}

	/**
	 * A player whose client has Diorama's channel joined, or said it has the channel. Server thread.
	 * Tells them the settings, which also tells their Diorama this server has Diorama.
	 */
	public static void listening(ServerPlayer player) {
		ShareServer share = running;
		if (share == null || player.level().getServer() != share.server) {
			return;
		}
		ShareProtocol.send(player, share.settings.encode());
	}

	/** A message from a player's Diorama. Server thread. Clients don't ask for anything yet. */
	public static void receive(ServerPlayer player, byte[] data) {
	}
}
