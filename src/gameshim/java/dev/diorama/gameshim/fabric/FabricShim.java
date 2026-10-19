package dev.diorama.gameshim.fabric;

import java.util.List;

import dev.diorama.gameshim.GameShim;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;

/** The shim's entrypoint on Fabric: the tests are the {@code fabric-client-gametest} entrypoints. */
public final class FabricShim implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (!GameShim.enabled()) {
			return;
		}
		GameShim.LOGGER.info("Diorama game shim: running the {} entrypoints once the game has loaded", GameShim.ENTRYPOINT);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> GameShim.onTickEnd(minecraft, FabricShim::tests));
	}

	private static List<GameShim.Test> tests() {
		return FabricLoader.getInstance().getEntrypointContainers(GameShim.ENTRYPOINT, FabricClientGameTest.class).stream()
			.map(test -> new GameShim.Test(test.getProvider().getMetadata().getId() + " " + test.getEntrypoint().getClass().getName(), test.getEntrypoint()))
			.toList();
	}
}
