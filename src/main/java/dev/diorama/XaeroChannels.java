package dev.diorama;

import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
//? if fabric {
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
//?} else if neoforge {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
*///?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.MCRegisterPacketHandler;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
*///?}
//? if fabric && >=1.21 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else if fabric {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
*///?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
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
 * <p>Servers track players on one channel per client, the minimap's when the client answered
 * there. Diorama takes them from that channel only, so a server moving a player from one channel
 * to the other can't remove them. It doesn't answer at all while Xaero's World Map is installed:
 * an answer moves the server's tracking from the World Map's channel to Diorama.
 *
 * <p>Fabric and NeoForge allow one handler per channel, and registering a channel twice throws at
 * startup. So a channel is only Diorama's when the Xaero mod that owns it isn't installed. With
 * both of Xaero's mods installed, they keep their channels and Diorama names worlds by their seed.
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
	/** The channel tracked players come on: the first Diorama answered on, the minimap's over the World Map's. */
	private static CustomPacketPayload.@Nullable Type<Message> trackerChannel;

	private XaeroChannels() {
	}

	//? if <1.21 {
	/*// Payload types came after 1.20.1. There a channel is only its id, and its messages are read
	// and written by hand (see read and write). This stands in for the game's payload interface,
	// so the rest of the class is the same on every version.
	public interface CustomPacketPayload {
		record Type<T>(ResourceLocation id) {
		}
	}
	*///?}

	/** One message: its kind, then its body as sent. */
	public record Message(CustomPacketPayload.Type<Message> type, int kind, byte[] body) implements CustomPacketPayload {
		/** The level ID this message carries, or null if it's another kind. */
		public @Nullable Integer levelId() {
			return this.kind == LEVEL_ID && this.body.length >= 4 ? ByteBuffer.wrap(this.body).getInt() : null;
		}
	}

	//? if fabric && >=1.21 {
	/** Listens on the channels no Xaero mod has. Call once, at startup. */
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			PayloadTypeRegistry.clientboundPlay().register(type, codec(type));
			PayloadTypeRegistry.serverboundPlay().register(type, codec(type));
			// Registering a handler also tells the server Diorama listens here. Plugins need that:
			// Paper drops messages on channels the client hasn't registered.
			ClientPlayNetworking.registerGlobalReceiver(type, (message, context) -> receive(message));
		}
	}
	//?} else if fabric {
	/*// Listens on the channels no Xaero mod has. Call once, at startup.
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// Registering a handler also tells the server Diorama listens here. Plugins need that:
			// Paper drops messages on channels the client hasn't registered. A channel that already
			// has a handler isn't taken over: here Fabric returns false instead of throwing.
			ClientPlayNetworking.registerGlobalReceiver(type.id(), (client, handler, buf, sender) -> {
				// The handler runs on the network thread, and the buffer is only good until it
				// returns. So the message is read here and handled on the client thread, as later
				// versions do, unless the player has left by then.
				Message message = read(type, buf);
				client.execute(() -> {
					if (handler.getConnection().isConnected()) {
						receive(message);
					}
				});
			});
		}
	}
	*///?} else if forge {
	/*// Listens on the channels no Xaero mod has. Call once, at startup.
	public static void register() {
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// Version "1.0" like the NeoForge jars, and any version accepted either way: Forge only
			// lets a player in when both sides accept the other's version of every channel they
			// share, and a server without the channel has none. Forge tells servers that aren't
			// Forge about its channels as the player joins, which Paper needs before it passes
			// messages on.
			NetworkRegistry.newEventChannel(type.id(), () -> "1.0", version -> true, version -> true)
				.<NetworkEvent.ServerCustomPayloadEvent>addListener(event -> {
					if (event instanceof NetworkEvent.ServerCustomPayloadLoginEvent) {
						return;
					}
					// Read here: the buffer is only good until this returns. Handled on the client thread.
					Message message = read(type, event.getPayload());
					NetworkEvent.Context context = event.getSource().get();
					context.enqueueWork(() -> receive(message));
					context.setPacketHandled(true);
				});
		}
	}
	*///?} else if >=1.21.6 {
	/*// Listens on the channels no Xaero mod has. NeoForge calls it once, at startup.
	public static void register(RegisterPayloadHandlersEvent event) {
		// Registered the way Xaero's mods register theirs: a NeoForge server with Xaero's mod on it
		// refuses a client whose channel has another version or direction. Optional, so servers
		// without the channel still let the player in. As the player joins a server that isn't
		// NeoForge, NeoForge tells it about optional channels, which Paper needs before it passes
		// messages on.
		PayloadRegistrar registrar = event.registrar("1.0").optional();
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			// From NeoForge 21.6 the client's handler is its own argument, and NeoForge won't start
			// with a clientbound channel that has none. The server side (a LAN guest's messages to
			// the integrated server) has nothing to do.
			registrar.playBidirectional(type, codec(type), (message, context) -> {}, (message, context) -> receive(message));
		}
	}
	*///?} else {
	/*// Listens on the channels no Xaero mod has. NeoForge calls it once, at startup.
	public static void register(RegisterPayloadHandlersEvent event) {
		// Registered the way Xaero's mods register theirs: a NeoForge server with Xaero's mod on it
		// refuses a client whose channel has another version or direction. Optional, so servers
		// without the channel still let the player in. As the player joins a server that isn't
		// NeoForge, NeoForge tells it about optional channels, which Paper needs before it passes
		// messages on.
		PayloadRegistrar registrar = event.registrar("1.0").optional();
		for (CustomPacketPayload.Type<Message> type : unclaimed()) {
			registrar.playBidirectional(type, codec(type), (message, context) -> {
				// The integrated server shares the registration, so a LAN guest's messages come here too.
				if (context.flow() == PacketFlow.CLIENTBOUND) {
					receive(message);
				}
			});
		}
	}
	*///?}

	/** The channels whose Xaero mod isn't installed. */
	private static List<CustomPacketPayload.Type<Message>> unclaimed() {
		List<CustomPacketPayload.Type<Message>> unclaimed = new ArrayList<>(2);
		// Fair-Play Minimap used the minimap's channel name in some versions.
		if (unclaimed(MINIMAP, "xaerominimap", "xaerominimapfair")) {
			unclaimed.add(MINIMAP);
		}
		if (unclaimed(WORLD_MAP, "xaeroworldmap")) {
			unclaimed.add(WORLD_MAP);
		}
		listening = !unclaimed.isEmpty();
		return unclaimed;
	}

	private static boolean unclaimed(CustomPacketPayload.Type<Message> type, String... xaeroMods) {
		for (String mod : xaeroMods) {
			if (modLoaded(mod)) {
				Diorama.LOGGER.info("{} is installed, so it keeps {} to itself", mod, type.id());
				return false;
			}
		}
		return true;
	}

	private static boolean modLoaded(String mod) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(mod);
		//?} else {
		/*return ModList.get().isLoaded(mod);
		*///?}
	}

	//? if >=1.21 {
	/** Public for tests. */
	public static StreamCodec<FriendlyByteBuf, Message> codec(CustomPacketPayload.Type<Message> type) {
		return StreamCodec.of(XaeroChannels::write, buf -> read(type, buf));
	}
	//?}

	/** Public for tests. */
	public static void write(FriendlyByteBuf buf, Message message) {
		buf.writeByte(message.kind());
		buf.writeBytes(message.body());
	}

	/** The whole message is read, as the game rejects packets with bytes left over. Public for tests. */
	public static Message read(CustomPacketPayload.Type<Message> type, FriendlyByteBuf buf) {
		int kind = buf.isReadable() ? buf.readByte() : -1;
		byte[] body = new byte[buf.readableBytes()];
		buf.readBytes(body);
		return new Message(type, kind, body);
	}

	private static void receive(Message message) {
		switch (message.kind()) {
			case LEVEL_ID -> {
				Integer id = message.levelId();
				if (id != null) {
					WorldKey.serverLevelId(id);
				}
			}
			case HANDSHAKE -> answer(message.type());
			case TRACKED_PLAYER -> {
				if (message.type() == trackerChannel) {
					CompoundTag tag = readNbt(message.body());
					if (tag != null) {
						TrackedPlayers.update(tag);
					}
				}
			}
			case TRACKER_RESET -> {
				if (message.type() == trackerChannel) {
					TrackedPlayers.clear();
				}
			}
			default -> {
			}
		}
	}

	private static void answer(CustomPacketPayload.Type<Message> channel) {
		if (modLoaded("xaeroworldmap")) {
			return;
		}
		//? if !fabric {
		/*// NeoForge throws on a message to a server that didn't declare the channel. Servers that
		// track players declare it, as they read the answer.
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null || !hasChannel(connection, channel.id())) {
			Diorama.LOGGER.info("The server sent a handshake on {} but doesn't listen there, so it isn't answered", channel.id());
			return;
		}
		*///?}
		if (trackerChannel == null || channel == MINIMAP && trackerChannel != MINIMAP) {
			trackerChannel = channel;
			TrackedPlayers.clear();
		}
		Message answer = new Message(channel, HANDSHAKE, ByteBuffer.allocate(4).putInt(PROTOCOL).array());
		//? if fabric && >=1.21 {
		ClientPlayNetworking.send(answer);
		//?} else if fabric {
		/*FriendlyByteBuf buf = PacketByteBufs.create();
		write(buf, answer);
		ClientPlayNetworking.send(channel.id(), buf);
		*///?} else if neoforge {
		/*connection.send(answer);
		*///?} else {
		/*FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		write(buf, answer);
		connection.send(new ServerboundCustomPayloadPacket(channel.id(), buf));
		*///?}
	}

	private static @Nullable CompoundTag readNbt(byte[] body) {
		try {
			return new FriendlyByteBuf(Unpooled.wrappedBuffer(body)).readNbt();
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read a tracked player from the server", e);
			return null;
		}
	}

	/** Left the server, or a network moved the player to another of its servers. */
	public static void onJoinOrLeave() {
		trackerChannel = null;
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
		//? if fabric {
		return ClientPlayNetworking.canSend(MINIMAP.id()) || ClientPlayNetworking.canSend(WORLD_MAP.id()) || ClientPlayNetworking.canSend(WORLD_INFO);
		//?} else {
		/*ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection != null && (hasChannel(connection, MINIMAP.id()) || hasChannel(connection, WORLD_MAP.id()) || hasChannel(connection, WORLD_INFO));
		*///?}
	}

	//? if neoforge {
	/*private static boolean hasChannel(ClientPacketListener connection, Identifier channel) {
		return connection.hasChannel(channel);
	}
	*///?} else if forge {
	/*// Whether the server said it listens on the channel: a Forge server in the mod list it sends as
	// the player logs in, any other server with minecraft:register. Forge's own channels check the same.
	private static boolean hasChannel(ClientPacketListener connection, Identifier channel) {
		Connection network = connection.getConnection();
		ConnectionData forge = NetworkHooks.getConnectionData(network);
		MCRegisterPacketHandler.ChannelList other = NetworkHooks.getChannelList(network);
		return forge != null && forge.getChannels().containsKey(channel) || other != null && other.getRemoteLocations().contains(channel);
	}
	*///?}
}
