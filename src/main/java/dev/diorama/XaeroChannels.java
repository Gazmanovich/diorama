package dev.diorama;

import java.nio.ByteBuffer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The plugin channels Xaero's Minimap and World Map share with servers. Servers built around
 * Xaero's mods send on them: Xaero's mods installed on the server, and plugins such as
 * MapModCompanion. Both channels carry the same messages, each one byte for its kind and then
 * its body. Diorama reads the level ID (kind 0, a big-endian int): the server's name for the
 * world the player is in, which tells apart worlds behind one address that share a seed.
 *
 * <p>Fabric allows one handler per channel, and registering a channel twice throws at startup.
 * So a channel is only Diorama's when the Xaero mod that owns it isn't installed; with both
 * installed, Xaero's mods keep their channels and Diorama falls back to the world seed.
 */
public final class XaeroChannels {
	/** Message kinds, the first byte of every message. */
	static final int LEVEL_ID = 0;

	public static final CustomPacketPayload.Type<Message> MINIMAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaerominimap", "main"));
	public static final CustomPacketPayload.Type<Message> WORLD_MAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaeroworldmap", "main"));
	/** VoxelMap's and JourneyMap's world ID channel, which MapModCompanion also listens on. */
	private static final Identifier WORLD_INFO = Identifier.fromNamespaceAndPath("worldinfo", "world_id");

	private XaeroChannels() {
	}

	/** One message: its kind, then its body as sent. */
	public record Message(CustomPacketPayload.Type<Message> type, int kind, byte[] body) implements CustomPacketPayload {
		/** The level ID this message carries, or null if it's another kind. */
		public @Nullable Integer levelId() {
			return this.kind == LEVEL_ID && this.body.length >= 4 ? ByteBuffer.wrap(this.body).getInt() : null;
		}
	}

	public static void register() {
		listen(MINIMAP, "xaerominimap");
		listen(WORLD_MAP, "xaeroworldmap");
	}

	private static void listen(CustomPacketPayload.Type<Message> type, String xaeroMod) {
		if (FabricLoader.getInstance().isModLoaded(xaeroMod)) {
			Diorama.LOGGER.info("{} is installed, so it keeps {} to itself", xaeroMod, type.id());
			return;
		}
		PayloadTypeRegistry.clientboundPlay().register(type, codec(type));
		// Registering a handler also tells the server Diorama listens here. Plugins need that:
		// Paper drops messages on channels the client hasn't registered.
		ClientPlayNetworking.registerGlobalReceiver(type, (message, context) -> receive(message));
	}

	/** The whole message is read, as the game rejects packets with bytes left over. */
	public static StreamCodec<FriendlyByteBuf, Message> codec(CustomPacketPayload.Type<Message> type) {
		return StreamCodec.of((buf, message) -> {
			buf.writeByte(message.kind());
			buf.writeBytes(message.body());
		}, buf -> {
			int kind = buf.isReadable() ? buf.readByte() : -1;
			byte[] body = new byte[buf.readableBytes()];
			buf.readBytes(body);
			return new Message(type, kind, body);
		});
	}

	private static void receive(Message message) {
		Integer id = message.levelId();
		if (id != null) {
			WorldKey.serverLevelId(id);
		}
	}

	/**
	 * Whether the server said it listens on one of the channels that come with level IDs. Servers
	 * declare their channels as the player joins; a server that names its worlds then sends the
	 * ID within a second or two, and one that doesn't is never waited on.
	 */
	static boolean serverMayNameWorlds() {
		return ClientPlayNetworking.canSend(MINIMAP.id()) || ClientPlayNetworking.canSend(WORLD_MAP.id()) || ClientPlayNetworking.canSend(WORLD_INFO);
	}
}
