package dev.diorama.share;

import dev.diorama.Diorama;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * What a server with Diorama shares of its map, from {@code config/diorama-server.properties},
 * which the server writes with the defaults the first time it starts.
 *
 * @param share whether players with Diorama get the land others explored
 * @param land which land: what players have spent time near, or every finished chunk
 * @param kilobytesPerSecond the most the server sends one player each second
 */
public record ShareSettings(boolean share, Land land, int kilobytesPerSecond) {
	public static final String FILE = "diorama-server.properties";
	public static final ShareSettings DEFAULTS = new ShareSettings(true, Land.VISITED, 500);

	/** Which land the shared map shows. */
	public enum Land {
		/**
		 * Chunks a player has spent time near: vanilla counts that time in every chunk within a
		 * player's simulation distance, so land only glimpsed at the edge of the view stays off.
		 */
		VISITED,
		/** Every finished chunk in the save, for servers that pre-generate their world. */
		GENERATED;

		String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Land of(String key) {
			for (Land land : values()) {
				if (land.key().equals(key.trim().toLowerCase(Locale.ROOT))) {
					return land;
				}
			}
			return null;
		}
	}

	/** Reads the settings from {@code configFolder}, writing the defaults there if there are none yet. */
	public static ShareSettings load(Path configFolder) {
		Path file = configFolder.resolve(FILE);
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(configFolder);
				Files.writeString(file, DEFAULTS.text());
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't write the default map sharing settings to {}", file, e);
			}
			return DEFAULTS;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			Diorama.LOGGER.warn("Couldn't read the map sharing settings in {}; using the defaults", file, e);
			return DEFAULTS;
		}
		String shareText = properties.getProperty("share-map", "true").trim();
		boolean share = !shareText.equalsIgnoreCase("false");
		if (share && !shareText.equalsIgnoreCase("true")) {
			Diorama.LOGGER.warn("share-map in {} is {}, which is neither true nor false; sharing", file, shareText);
		}
		String landText = properties.getProperty("share-land", Land.VISITED.key());
		Land land = Land.of(landText);
		if (land == null) {
			Diorama.LOGGER.warn("share-land in {} is {}, which is neither visited nor generated; using visited", file, landText);
			land = Land.VISITED;
		}
		int kilobytes = DEFAULTS.kilobytesPerSecond();
		String rate = properties.getProperty("share-kilobytes-per-second");
		if (rate != null) {
			try {
				kilobytes = Math.max(1, Integer.parseInt(rate.trim()));
			} catch (NumberFormatException e) {
				Diorama.LOGGER.warn("share-kilobytes-per-second in {} is {}, which isn't a number; using {}", file, rate, kilobytes);
			}
		}
		return new ShareSettings(share, land, kilobytes);
	}

	/** The settings as the file has them, with what each means. */
	String text() {
		return """
			# Diorama on this server: which part of the map players with Diorama share.
			# Players without Diorama, and servers without it, are unaffected.

			# true: players with Diorama see land other players explored on their map, drawn with
			# their own resource pack. Only the view from the sky; caves stay what each player found.
			share-map=%s

			# Which land is shared:
			#   visited   - chunks players have spent time near (default)
			#   generated - every finished chunk in the world, for worlds pre-generated with Chunky and the like
			share-land=%s

			# The most this server sends each player for the shared map, in kilobytes a second.
			share-kilobytes-per-second=%d
			""".formatted(this.share, this.land.key(), this.kilobytesPerSecond);
	}

	/** As the server sends them: the protocol version, then the settings. */
	byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(ShareProtocol.SETTINGS);
			out.writeShort(ShareProtocol.VERSION);
			out.writeBoolean(this.share);
			out.writeByte(this.land.ordinal());
			out.writeInt(this.kilobytesPerSecond);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/**
	 * The settings in a message from the server, or null if the server speaks another version of
	 * the protocol, or the message is cut short.
	 */
	public static @Nullable ShareSettings decode(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			if (in.readUnsignedByte() != ShareProtocol.SETTINGS) {
				return null;
			}
			int version = in.readUnsignedShort();
			if (version != ShareProtocol.VERSION) {
				Diorama.LOGGER.info("The server's Diorama shares its map in another way (version {}, this one {}); not sharing", version, ShareProtocol.VERSION);
				return null;
			}
			boolean share = in.readBoolean();
			int land = in.readUnsignedByte();
			int kilobytes = in.readInt();
			return new ShareSettings(share, land < Land.values().length ? Land.values()[land] : Land.VISITED, kilobytes);
		} catch (IOException e) {
			return null;
		}
	}
}
