package dev.diorama.neoforge;

import dev.diorama.Diorama;
import dev.diorama.share.ShareProtocol;
import dev.diorama.share.ShareServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
//? if <1.21.6 {
/*import net.minecraft.network.protocol.PacketFlow;
*///?}

/**
 * Diorama's entrypoint on both sides on NeoForge: the share channel, and the server half on a
 * dedicated server. {@link DioramaNeoForge} is the client's. Names no client class, so a dedicated
 * server can load it.
 */
@Mod(Diorama.MOD_ID)
public final class DioramaNeoForgeCommon {
	public DioramaNeoForgeCommon(IEventBus modBus) {
		modBus.addListener(RegisterPayloadHandlersEvent.class, DioramaNeoForgeCommon::register);
		ShareProtocol.sender((player, data) -> player.connection.send(new ShareProtocol.Packet(data)));
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> ShareServer.started(event.getServer(), FMLPaths.CONFIGDIR.get()));
		NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> ShareServer.stopped(event.getServer()));
		// The client says which channels it has while it's configured, before it plays.
		NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
			if (event.getEntity() instanceof ServerPlayer player && player.connection.hasChannel(ShareProtocol.TYPE)) {
				ShareServer.listening(player);
			}
		});
	}

	// Optional, so a server without Diorama lets a player with it in, and the other way round. The
	// channel's version stays "1": NeoForge refuses players whose version differs, so versions of
	// the messages are told apart in the messages instead (ShareProtocol.VERSION).
	private static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1").optional();
		//? if >=1.21.6 {
		registrar.playBidirectional(ShareProtocol.TYPE, ShareProtocol.CODEC,
			(packet, context) -> {
				if (context.player() instanceof ServerPlayer player) {
					ShareServer.receive(player, packet.data());
				}
			},
			(packet, context) -> ShareProtocol.toClient(packet.data()));
		//?} else {
		/*registrar.playBidirectional(ShareProtocol.TYPE, ShareProtocol.CODEC, (packet, context) -> {
			if (context.flow() == PacketFlow.CLIENTBOUND) {
				ShareProtocol.toClient(packet.data());
			} else if (context.player() instanceof ServerPlayer player) {
				ShareServer.receive(player, packet.data());
			}
		});
		*///?}
	}
}
