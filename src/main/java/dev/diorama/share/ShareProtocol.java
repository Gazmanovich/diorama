package dev.diorama.share;

import dev.diorama.Diorama;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21 {
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The one channel Diorama's two halves talk on, when the server has Diorama too. Every message is
 * one byte for its kind, then its body. This side of it has no client classes in it, so it loads
 * on a dedicated server; the loader's entrypoints hand messages to {@link ShareServer} and, on the
 * client, to whatever {@link #onClient} set.
 *
 * <p>Both sides only send once they know the other side listens: the server once the client has
 * said it has the channel, the client once the server's {@link ShareSettings} came. So a server
 * without Diorama, and a client without it, never see a message.
 */
public final class ShareProtocol {
	public static final Identifier CHANNEL = Diorama.id("share");
	/** Changes when a message changes shape; a client and server on different versions don't talk. */
	public static final int VERSION = 1;

	/** Server to client: the server's {@link ShareSettings}, sent as the player joins. */
	public static final int SETTINGS = 0;
	/**
	 * Client to server: which regions does the level have? Its dimension. Server to client, the
	 * answer: the dimension, the server's clock in milliseconds, a count, and each region's x and z.
	 */
	public static final int REGIONS = 1;
	/**
	 * Client to server: these regions' tables. The dimension, a count, each region's x and z. Server
	 * to client, one message a region: the dimension, its x and z, then deflated, for each of its
	 * 1,024 chunks ({@code (z << 5) | x}) when the save wrote it if it's shared, else 0, and for a
	 * shared one how high it reaches and how deep its sky view goes, as shorts.
	 */
	public static final int TABLES = 2;
	/**
	 * Client to server: these chunks. The dimension, whether they come before what's waiting, a
	 * count, each chunk's x and z, most wanted first. Server to client, one message a chunk: the
	 * dimension, its x and z, when it was saved, and the packed chunk's length and bytes; a length
	 * of 0 if it isn't shared.
	 */
	public static final int CHUNKS = 3;
	/** The most chunks one ask names, so it fits in a message to the server. */
	public static final int MAX_ASK = 3000;
	/** The biggest packed chunk sent; one past it is said not to be shared. */
	public static final int MAX_CHUNK_BYTES = 1 << 19;

	/** Where messages from the server go on the client; the client's entrypoint sets it. */
	private static volatile @Nullable Consumer<byte[]> client;
	/** How the server half sends; the loader's common entrypoint sets it. */
	private static volatile @Nullable BiConsumer<ServerPlayer, byte[]> sender;
	/** How the client sends to the server; the client's entrypoint sets it. */
	private static volatile @Nullable Consumer<byte[]> serverSender;

	private ShareProtocol() {
	}

	//? if <1.21 {
	/*// Payload types came after 1.20.1: a channel was only its id there, and the loaders read and
	// write its bytes by hand.
	public record Packet(byte[] data) {
	}
	*///?} else {
	/** A message, as it travels. */
	public record Packet(byte[] data) implements CustomPacketPayload {
		@Override
		public Type<Packet> type() {
			return TYPE;
		}
	}

	public static final CustomPacketPayload.Type<Packet> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
	public static final StreamCodec<FriendlyByteBuf, Packet> CODEC = StreamCodec.of(ShareProtocol::write, ShareProtocol::read);
	//?}

	public static void write(FriendlyByteBuf buf, Packet packet) {
		buf.writeBytes(packet.data());
	}

	/** The whole message: the game rejects packets with bytes left over. */
	public static Packet read(FriendlyByteBuf buf) {
		byte[] data = new byte[buf.readableBytes()];
		buf.readBytes(data);
		return new Packet(data);
	}

	/** Sets where the server's messages go on this client. */
	public static void onClient(Consumer<byte[]> receiver) {
		client = receiver;
	}

	/** Sets how the client sends to the server. */
	public static void toServer(Consumer<byte[]> send) {
		serverSender = send;
	}

	/** Sends a message to the server, which has the channel. Client thread. */
	public static void sendToServer(byte[] data) {
		Consumer<byte[]> send = serverSender;
		if (send != null) {
			send.accept(data);
		}
	}

	/** A chunk's x and z in one long, as the server queues them. */
	public static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	/** Sets how the server half sends to a player. */
	public static void sender(BiConsumer<ServerPlayer, byte[]> send) {
		sender = send;
	}

	/** A message from the server, on the client thread. */
	public static void toClient(byte[] data) {
		Consumer<byte[]> receiver = client;
		if (receiver != null) {
			receiver.accept(data);
		}
	}

	/** Sends a message to a player who has the channel. */
	static void send(ServerPlayer player, byte[] data) {
		BiConsumer<ServerPlayer, byte[]> send = sender;
		if (send != null) {
			send.accept(player, data);
		}
	}
}
