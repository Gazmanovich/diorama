package dev.diorama.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;

/**
 * Pauses the singleplayer world while a test waits for the map to finish baking, as {@code /tick
 * freeze} does. A new world never goes quiet by itself: water and lava from world generation keep
 * flowing and vines keep growing, so some tile near spawn re-bakes every second or two, and a wait
 * for 30 ticks with nothing baking ran to its 1200-tick limit, a minute here and two on boat.dev.
 * Paused, chunks still load and send and the player still moves; blocks, fluids, mobs and time
 * stand still. Nothing is paused on a dedicated server. 1.20.1 has no tick freeze, so there the map
 * screen pauses the game instead while it's open, as a pause menu would; with another screen or
 * none the world keeps running.
 */
final class WorldPause implements AutoCloseable {
	private final ClientGameTestContext context;
	private final boolean paused;

	private WorldPause(ClientGameTestContext context, boolean paused) {
		this.context = context;
		this.paused = paused;
	}

	static WorldPause pause(ClientGameTestContext context) {
		//? if >=1.21 {
		boolean paused = context.computeOnClient(mc -> {
			MinecraftServer server = mc.getSingleplayerServer();
			// A world the test froze itself stays as the test left it.
			if (server == null || server.tickRateManager().isFrozen()) {
				return false;
			}
			server.execute(() -> server.tickRateManager().setFrozen(true));
			return true;
		});
		//?} else {
		/*boolean paused = context.computeOnClient(mc -> mc.getSingleplayerServer() != null);
		if (paused) {
			context.runOnClient(mc -> dev.diorama.MapScreen.pauseForTests(true));
		}
		*///?}
		return new WorldPause(context, paused);
	}

	@Override
	public void close() {
		if (!this.paused) {
			return;
		}
		//? if >=1.21 {
		this.context.runOnClient(mc -> {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server != null) {
				server.execute(() -> server.tickRateManager().setFrozen(false));
			}
		});
		// Until the client hears the world runs again, so what the test does next finds it running.
		// The shim's ticks don't wait for the server, so one tick isn't enough there.
		this.context.waitFor(mc -> mc.level == null || !mc.level.tickRateManager().isFrozen(), 200);
		//?} else {
		/*this.context.runOnClient(mc -> dev.diorama.MapScreen.pauseForTests(false));
		*///?}
	}
}
