package dev.diorama.share;

import dev.diorama.Diorama;
import org.jspecify.annotations.Nullable;

/**
 * The client's side of {@link ShareProtocol}: what the server it's on shares, once that server's
 * Diorama has said. Null on servers without Diorama, and in singleplayer. Client thread.
 */
public final class ShareClient {
	private static @Nullable ShareSettings settings;

	private ShareClient() {
	}

	/** Hooks the channel up to this class. Called once, by the client's entrypoint. */
	public static void init() {
		ShareProtocol.onClient(ShareClient::receive);
	}

	private static void receive(byte[] data) {
		if (data.length == 0) {
			return;
		}
		if (data[0] == ShareProtocol.LOCKS) {
			ServerLocks locks = ServerLocks.decode(data);
			if (locks != null) {
				dev.diorama.ServerRules.onLocks(locks);
			}
		} else if (data[0] != ShareProtocol.SETTINGS) {
			try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data))) {
				SharedChunks.receive(in.readUnsignedByte(), in);
			} catch (java.io.IOException | RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't read a message about the shared map", e);
			}
		} else {
			settings = ShareSettings.decode(data);
			if (settings != null) {
				Diorama.LOGGER.info("This server has Diorama: {}", settings.share()
					? "it shares its map (" + settings.land().key() + " land)"
					: "it doesn't share its map");
			}
		}
	}

	/** What the server shares, or null if it has no Diorama, or one that speaks another version. */
	public static @Nullable ShareSettings settings() {
		return settings;
	}

	/** Whether the server shares its map. */
	public static boolean sharing() {
		ShareSettings known = settings;
		return known != null && known.share();
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		settings = null;
		SharedChunks.closeCurrent();
	}
}
