package dev.diorama;

import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The plugin channels Xaero's Minimap and World Map share with servers. Servers built around
 * Xaero's mods send on them: Xaero's mods installed on the server, and plugins such as
 * MapModCompanion. Both channels carry the same messages, each one byte for its kind and then
 * its body. Diorama reads:
 * <ul>
 *   <li>the level ID (kind 0, a big-endian int): the server's name for the world the player is
 *       in, which tells apart worlds behind one address that share a seed;</li>
 *   <li>the handshake (kind 1, the server's protocol version as an int), which Diorama answers
 *       like Xaero's mods do, since servers only track players for clients that answered;</li>
 *   <li>tracked players (kind 2, one player in network NBT, see {@link TrackedPlayers}) and the
 *       tracker reset (kind 3, empty), sent before the server sends everyone again.</li>
 * </ul>
 *
 * <p>Fabric allows one handler per channel, and registering a channel twice throws at startup.
 * So a channel is only Diorama's when the Xaero mod that owns it isn't installed. With both of
 * Xaero's mods installed, they keep their channels and Diorama names worlds by their seed.
 */
public final class XaeroChannels {
	/** Message kinds, the first byte of every message. */
	static final int LEVEL_ID = 0;
	static final int HANDSHAKE = 1;
	static final int TRACKED_PLAYER = 2;
	static final int TRACKER_RESET = 3;
	/** The protocol version Xaero's mods answer with, which the player tracker needs. */
	private static final int PROTOCOL = 3;

	/** The channels, public for tests that send on them. */
	public static final CustomPacketPayload.Type<Message> MINIMAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaerominimap", "main"));
	public static final CustomPacketPayload.Type<Message> WORLD_MAP = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xaeroworldmap", "main"));
	/** VoxelMap's and JourneyMap's world ID channel, which MapModCompanion also listens on. */
	private static final Identifier WORLD_INFO = Identifier.fromNamespaceAndPath("worldinfo", "world_id");

	/** Whether Diorama listens on at least one of the channels. */
	private static boolean listening;

	private XaeroChannels() {
	}

	/** One message: its kind, then its body as sent. */
	public record Message(CustomPacketPayload.Type<Message> type, int kind, byte[] body) implements CustomPacketPayload {
		/** The level ID this message carries, or null if it's another kind. */
		public @Nullable Integer levelId() {
			return this.kind == LEVEL_ID && this.body.length >= 4 ? ByteBuffer.wrap(this.body).getInt() : null;
		}
	}

	/** Listens on the channels no Xaero mod has. Call once, at startup. */
	public static void register() {
		// Fair-Play Minimap used the minimap's channel name in some versions.
		listen(MINIMAP, "xaerominimap", "xaerominimapfair");
		listen(WORLD_MAP, "xaeroworldmap");
	}

	private static void listen(CustomPacketPayload.Type<Message> type, String... xaeroMods) {
		for (String mod : xaeroMods) {
			if (FabricLoader.getInstance().isModLoaded(mod)) {
				Diorama.LOGGER.info("{} is installed, so it keeps {} to itself", mod, type.id());
				return;
			}
		}
		listening = true;
		PayloadTypeRegistry.clientboundPlay().register(type, codec(type));
		PayloadTypeRegistry.serverboundPlay().register(type, codec(type));
		// Registering a handler also tells the server Diorama listens here. Plugins need that:
		// Paper drops messages on channels the client hasn't registered.
		ClientPlayNetworking.registerGlobalReceiver(type, (message, context) -> receive(message));
	}

	/** The whole message is read, as the game rejects packets with bytes left over. Public for tests. */
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
		switch (message.kind()) {
			case LEVEL_ID -> {
				Integer id = message.levelId();
				if (id != null) {
					WorldKey.serverLevelId(id);
				}
			}
			case HANDSHAKE -> ClientPlayNetworking.send(new Message(message.type(), HANDSHAKE, ByteBuffer.allocate(4).putInt(PROTOCOL).array()));
			case TRACKED_PLAYER -> {
				CompoundTag tag = new FriendlyByteBuf(Unpooled.wrappedBuffer(message.body())).readNbt();
				if (tag != null) {
					TrackedPlayers.update(tag);
				}
			}
			case TRACKER_RESET -> TrackedPlayers.clear();
			default -> {
			}
		}
	}

	static boolean listening() {
		return listening;
	}

	/**
	 * Whether the server said it listens on one of the channels that come with level IDs: Xaero's
	 * own, which Xaero's server mod and XaeroTracker listen on, or VoxelMap's, which
	 * MapModCompanion does. Servers declare their channels as the player joins.
	 */
	static boolean serverMayNameWorlds() {
		return ClientPlayNetworking.canSend(MINIMAP.id()) || ClientPlayNetworking.canSend(WORLD_MAP.id()) || ClientPlayNetworking.canSend(WORLD_INFO);
	}
}
