package dev.diorama.fabric;

import dev.diorama.Diorama;
import dev.diorama.DioramaClient;
import dev.diorama.MapSession;
import dev.diorama.MinimapHud;
import dev.diorama.ServerRules;
import dev.diorama.TrackedPlayers;
import dev.diorama.WorldKey;
import dev.diorama.XaeroChannels;
import dev.diorama.waypoint.WaypointHud;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}
import net.minecraft.client.KeyMapping;

/**
 * Diorama's entrypoint on Fabric: registers the key bindings and the HUD, and hooks the mod up to
 * Fabric API's events. Only the Fabric jars contain this package; NeoForge has its own entrypoint.
 */
public final class DioramaFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		DioramaClient.init();
		for (KeyMapping keyMapping : DioramaClient.KEY_MAPPINGS) {
			KeyMappingHelper.registerKeyMapping(keyMapping);
		}
		//? if >=1.21.6 {
		HudElementRegistry.addLast(Diorama.id("waypoints"), new WaypointHud());
		HudElementRegistry.addLast(Diorama.id("minimap"), new MinimapHud());
		//?} else {
		/*// Drawn after the whole vanilla HUD, like addLast. Each vanilla HUD layer sits 200 deeper
		// than the one before and the GUI depth-tests, so lift the vanilla fills and text these
		// draw above all of them.
		WaypointHud waypointHud = new WaypointHud();
		MinimapHud minimapHud = new MinimapHud();
		HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 5000);
			waypointHud.render(graphics, deltaTracker);
			minimapHud.render(graphics, deltaTracker);
			pose.popPose();
		});
		*///?}
		ClientChunkEvents.CHUNK_LOAD.register(MapSession::chunkLoaded);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ServerRules.onServerMessage(message));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> WaypointShare.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
			ServerRules.onDisconnect();
			WaypointStore.onLeave();
			WorldKey.onJoinOrLeave();
			XaeroChannels.onJoinOrLeave();
			TrackedPlayers.clear();
		});
		// A network moving the player to another of its servers joins again without leaving.
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			WaypointStore.onLeave();
			WorldKey.onJoinOrLeave();
			XaeroChannels.onJoinOrLeave();
			TrackedPlayers.clear();
		});
		XaeroChannels.register();
		// Leaving a world saves the map on the next tick, but closing the window stops ticking first.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapSession.close());
		ClientTickEvents.END_CLIENT_TICK.register(DioramaClient::tick);
	}
}
