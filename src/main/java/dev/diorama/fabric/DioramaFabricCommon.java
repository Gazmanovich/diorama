package dev.diorama.fabric;

import dev.diorama.share.ShareProtocol;
import dev.diorama.share.ShareServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
//? if >=1.21 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.minecraft.network.FriendlyByteBuf;
*///?}

/**
 * Diorama's entrypoint on both sides on Fabric: the share channel, and the server half on a
 * dedicated server. Runs before {@link DioramaFabric} on the client. Names no client class, so a
 * dedicated server can load it.
 */
public final class DioramaFabricCommon implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ShareServer.started(server, FabricLoader.getInstance().getConfigDir()));
		ServerLifecycleEvents.SERVER_STOPPED.register(ShareServer::stopped);
		//? if >=1.21 {
		PayloadTypeRegistry.clientboundPlay().register(ShareProtocol.TYPE, ShareProtocol.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShareProtocol.TYPE, ShareProtocol.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ShareProtocol.TYPE, (packet, context) -> ShareServer.receive(context.player(), packet.data()));
		ShareProtocol.sender((player, data) -> ServerPlayNetworking.send(player, new ShareProtocol.Packet(data)));
		// The client says which channels it has while it's configured, before it plays.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler, ShareProtocol.TYPE)) {
				ShareServer.listening(handler.player);
			}
		});
		//?} else {
		/*ServerPlayNetworking.registerGlobalReceiver(ShareProtocol.CHANNEL, (server, player, handler, buf, responseSender) -> {
			// Read here: the buffer is only good until this returns.
			byte[] data = ShareProtocol.read(buf).data();
			server.execute(() -> ShareServer.receive(player, data));
		});
		ShareProtocol.sender((player, data) -> {
			FriendlyByteBuf buf = PacketByteBufs.create();
			buf.writeBytes(data);
			ServerPlayNetworking.send(player, ShareProtocol.CHANNEL, buf);
		});
		// Here the client says which channels it has once it plays, after it joined.
		S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
			if (channels.contains(ShareProtocol.CHANNEL)) {
				server.execute(() -> ShareServer.listening(handler.player));
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler, ShareProtocol.CHANNEL)) {
				ShareServer.listening(handler.player);
			}
		});
		*///?}
	}
}
