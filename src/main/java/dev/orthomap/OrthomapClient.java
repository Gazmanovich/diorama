package dev.orthomap;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orthomap.render.OrthomapPipelines;
import dev.orthomap.waypoint.Waypoint;
import dev.orthomap.waypoint.WaypointShare;
import dev.orthomap.waypoint.WaypointEditScreen;
import dev.orthomap.waypoint.WaypointHud;
import dev.orthomap.waypoint.WaypointListScreen;
import dev.orthomap.waypoint.Waypoints;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;

public class OrthomapClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Orthomap.id("map"));
	public static final KeyMapping OPEN_MAP = new KeyMapping("key.orthomap.open_map", InputConstants.KEY_M, CATEGORY);
	public static final KeyMapping NEW_WAYPOINT = new KeyMapping("key.orthomap.new_waypoint", InputConstants.KEY_B, CATEGORY);
	public static final KeyMapping WAYPOINT_LIST = new KeyMapping("key.orthomap.waypoints", InputConstants.KEY_U, CATEGORY);
	public static final KeyMapping ZOOM_IN = new KeyMapping("key.orthomap.zoom_in", InputConstants.KEY_EQUALS, CATEGORY);
	public static final KeyMapping ZOOM_OUT = new KeyMapping("key.orthomap.zoom_out", InputConstants.KEY_MINUS, CATEGORY);
	public static final KeyMapping SETTINGS = new KeyMapping("key.orthomap.settings", InputConstants.UNKNOWN.getValue(), CATEGORY);
	public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping("key.orthomap.toggle_minimap", InputConstants.UNKNOWN.getValue(), CATEGORY);

	private static void changeZoom(int step) {
		Settings s = Settings.get();
		int next = Math.max(0, Math.min(Settings.ZOOMS.length - 1, s.minimapZoom + step));
		if (next != s.minimapZoom) {
			s.minimapZoom = next;
			s.save();
		}
	}

	@Override
	public void onInitializeClient() {
		OrthomapPipelines.init();
		KeyMappingHelper.registerKeyMapping(OPEN_MAP);
		KeyMappingHelper.registerKeyMapping(NEW_WAYPOINT);
		KeyMappingHelper.registerKeyMapping(WAYPOINT_LIST);
		KeyMappingHelper.registerKeyMapping(ZOOM_IN);
		KeyMappingHelper.registerKeyMapping(ZOOM_OUT);
		KeyMappingHelper.registerKeyMapping(SETTINGS);
		KeyMappingHelper.registerKeyMapping(TOGGLE_MINIMAP);
		Settings.get();
		HudElementRegistry.addLast(Orthomap.id("waypoints"), new WaypointHud());
		HudElementRegistry.addLast(Orthomap.id("minimap"), new MinimapHud());
		ClientChunkEvents.CHUNK_LOAD.register(MapSession::chunkLoaded);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ServerRules.onServerMessage(message));
		// A shared waypoint gets a line of its own after the message, offering to add it. The
		// message itself is left alone: hiding a signed player message would drop it from chat
		// reports and lose its trust warnings.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				WaypointShare.received(message, null);
			}
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> WaypointShare.received(message, sender));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> WaypointShare.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> ServerRules.onDisconnect());
		// Leaving a world saves the map on the next tick, but closing the window stops ticking first.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapSession.close());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			MapSession.tick(mc);
			while (OPEN_MAP.consumeClick()) {
				if (mc.gui.screen() == null && mc.level != null && ServerRules.minimapAllowed()) {
					mc.gui.setScreen(new MapScreen());
				}
			}
			while (NEW_WAYPOINT.consumeClick()) {
				Waypoint waypoint = Waypoints.atPlayer();
				if (mc.gui.screen() == null && waypoint != null) {
					mc.gui.setScreen(new WaypointEditScreen(null, waypoint, true));
				}
			}
			while (ZOOM_IN.consumeClick()) {
				changeZoom(1);
			}
			while (ZOOM_OUT.consumeClick()) {
				changeZoom(-1);
			}
			while (SETTINGS.consumeClick()) {
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(new SettingsScreen(null));
				}
			}
			while (TOGGLE_MINIMAP.consumeClick()) {
				Settings s = Settings.get();
				s.minimap = !s.minimap;
				s.save();
			}
			while (WAYPOINT_LIST.consumeClick()) {
				if (mc.gui.screen() == null && mc.level != null) {
					mc.gui.setScreen(new WaypointListScreen(null));
				}
			}
		});
	}
}
