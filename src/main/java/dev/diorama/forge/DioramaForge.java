package dev.diorama.forge;

import dev.diorama.Diorama;
import dev.diorama.share.ShareProtocol;
import dev.diorama.share.ShareServer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

/**
 * Diorama's entrypoint on Forge, on both sides: the share channel and the server half, and on the
 * client the rest. Only the Forge jars contain this package.
 */
@Mod(Diorama.MOD_ID)
public final class DioramaForge {
	public DioramaForge() {
		// Any version accepted, missing included: Forge only lets a player in when both sides accept
		// the other's version of every channel they share. Versions of the messages are told apart
		// in the messages (ShareProtocol.VERSION).
		EventNetworkChannel channel = NetworkRegistry.newEventChannel(ShareProtocol.CHANNEL, () -> "1", version -> true, version -> true);
		channel.addListener(DioramaForge::receive);
		ShareProtocol.sender((player, data) -> {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			player.connection.send(new ClientboundCustomPayloadPacket(ShareProtocol.CHANNEL, buf));
		});
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class, event -> ShareServer.started(event.getServer(), FMLPaths.CONFIGDIR.get()));
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, event -> ShareServer.stopped(event.getServer()));
		// A Forge client says which channels it has as it logs in.
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent.class, event -> {
			if (event.getEntity() instanceof ServerPlayer player && channel.isRemotePresent(player.connection.connection)) {
				ShareServer.listening(player);
			}
		});
		// Forge's @Mod has no side of its own, and Forge reads this class's methods before it calls
		// this constructor, so everything that names client classes lives in DioramaForgeClient,
		// which only loads on the client.
		if (FMLEnvironment.dist == Dist.CLIENT) {
			DioramaForgeClient.init();
		}
	}

	// On the network thread; the buffer is only good until this returns, so it's read here.
	private static void receive(NetworkEvent event) {
		if (event instanceof NetworkEvent.ServerCustomPayloadLoginEvent || event instanceof NetworkEvent.ClientCustomPayloadLoginEvent || event.getPayload() == null) {
			return;
		}
		byte[] data = ShareProtocol.read(event.getPayload()).data();
		NetworkEvent.Context context = event.getSource().get();
		if (event instanceof NetworkEvent.ServerCustomPayloadEvent) {
			// From the server, so on the client.
			context.enqueueWork(() -> {
				if (context.getNetworkManager().isConnected()) {
					ShareProtocol.toClient(data);
				}
			});
		} else if (event instanceof NetworkEvent.ClientCustomPayloadEvent) {
			ServerPlayer player = context.getSender();
			if (player != null) {
				context.enqueueWork(() -> ShareServer.receive(player, data));
			}
		}
		context.setPacketHandled(true);
	}
}
