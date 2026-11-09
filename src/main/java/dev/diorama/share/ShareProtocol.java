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

	/** Where messages from the server go on the client; the client's entrypoint sets it. */
	private static volatile @Nullable Consumer<byte[]> client;
	/** How the server half sends; the loader's common entrypoint sets it. */
	private static volatile @Nullable BiConsumer<ServerPlayer, byte[]> sender;

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
