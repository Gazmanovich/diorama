package dev.diorama.gameshim;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientWorldContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** A singleplayer world in play, after Fabric API's TestSingleplayerContextImpl. */
final class ShimSingleplayer implements TestSingleplayerContext {
	private static final int SHUTDOWN_TIMEOUT_TICKS = 60 * 20;

	private final ShimContext context;
	private final TestWorldSave worldSave;
	private final MinecraftServer server;
	private final ShimServer serverContext;
	private final ShimClientWorld clientWorld;

	ShimSingleplayer(ShimContext context, TestWorldSave worldSave, MinecraftServer server) {
		this.context = context;
		this.worldSave = worldSave;
		this.server = server;
		this.serverContext = new ShimServer(server);
		this.clientWorld = new ShimClientWorld(context);
	}

	@Override
	public TestWorldSave getWorldSave() {
		return worldSave;
	}

	@Override
	public TestClientWorldContext getClientWorld() {
		return clientWorld;
	}

	@Override
	public TestServerContext getServer() {
		return serverContext;
	}

	@Override
	public void close() {
		ShimThreading.checkOnTestThread("close");
		// Leaving blocks the client until the integrated server has saved and stopped.
		context.runOnClient(minecraft -> {
			if (minecraft.level == null) {
				throw new IllegalStateException("Exited the world before closing singleplayer context");
			}
			//? if >=1.21.6 {
			minecraft.level.disconnect(Component.translatable("menu.savingLevel"));
			minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
			//?} else {
			/*minecraft.level.disconnect();
			minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
			*///?}
		});
		context.waitFor(minecraft -> minecraft.level == null && server.isShutdown(), SHUTDOWN_TIMEOUT_TICKS);
		//? if >=26.1 {
		// As Fabric's runner does from 26.1, before the title screen replaces the saving screen.
		context.waitTicks(2);
		//?}
		context.setScreen(TitleScreen::new);
	}
}
