package dev.orthomap;

import com.mojang.blaze3d.platform.InputConstants;
import dev.orthomap.render.OrthomapPipelines;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;

public class OrthomapClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Orthomap.id("map"));
	public static final KeyMapping OPEN_MAP = new KeyMapping("key.orthomap.open_map", InputConstants.KEY_M, CATEGORY);

	@Override
	public void onInitializeClient() {
		OrthomapPipelines.init();
		KeyMappingHelper.registerKeyMapping(OPEN_MAP);
		HudElementRegistry.addLast(Orthomap.id("minimap"), new MinimapHud());
		ClientChunkEvents.CHUNK_LOAD.register(MapSession::chunkLoaded);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> CaveRules.onServerMessage(message));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> CaveRules.onDisconnect());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			MapSession.tick(mc);
			while (OPEN_MAP.consumeClick()) {
				if (mc.gui.screen() == null && mc.level != null) {
					mc.gui.setScreen(new MapScreen());
				}
			}
		});
	}
}
