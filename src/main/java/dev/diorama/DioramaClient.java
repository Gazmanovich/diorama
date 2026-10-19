package dev.diorama;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=1.21.6 {
import dev.diorama.render.DioramaPipelines;
//?}
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointHud;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.Waypoints;
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

public class DioramaClient implements ClientModInitializer {
	//? if >=1.21.9 {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Diorama.id("map"));
	//?} else {
	/*// Key categories were bare translation keys; this is the one Category.register makes.
	public static final String CATEGORY = "key.category.diorama.map";
	*///?}
	public static final KeyMapping OPEN_MAP = new KeyMapping("key.diorama.open_map", InputConstants.KEY_M, CATEGORY);
	public static final KeyMapping NEW_WAYPOINT = new KeyMapping("key.diorama.new_waypoint", InputConstants.KEY_B, CATEGORY);
	public static final KeyMapping WAYPOINT_LIST = new KeyMapping("key.diorama.waypoints", InputConstants.KEY_U, CATEGORY);
	public static final KeyMapping ZOOM_IN = new KeyMapping("key.diorama.zoom_in", InputConstants.KEY_EQUALS, CATEGORY);
	public static final KeyMapping ZOOM_OUT = new KeyMapping("key.diorama.zoom_out", InputConstants.KEY_MINUS, CATEGORY);
	public static final KeyMapping SETTINGS = new KeyMapping("key.diorama.settings", InputConstants.UNKNOWN.getValue(), CATEGORY);
	public static final KeyMapping TOGGLE_MINIMAP = new KeyMapping("key.diorama.toggle_minimap", InputConstants.UNKNOWN.getValue(), CATEGORY);

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
		//? if >=1.21.6 {
		DioramaPipelines.init();
		//?}
		KeyMappingHelper.registerKeyMapping(OPEN_MAP);
		KeyMappingHelper.registerKeyMapping(NEW_WAYPOINT);
		KeyMappingHelper.registerKeyMapping(WAYPOINT_LIST);
		KeyMappingHelper.registerKeyMapping(ZOOM_IN);
		KeyMappingHelper.registerKeyMapping(ZOOM_OUT);
		KeyMappingHelper.registerKeyMapping(SETTINGS);
		KeyMappingHelper.registerKeyMapping(TOGGLE_MINIMAP);
		Settings.get();
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
