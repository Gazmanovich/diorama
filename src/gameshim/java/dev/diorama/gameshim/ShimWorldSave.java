package dev.diorama.gameshim;

import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

/** A singleplayer save, after Fabric API's TestWorldSaveImpl. */
final class ShimWorldSave implements TestWorldSave {
	private final ShimContext context;
	private final Path saveDirectory;

	ShimWorldSave(ShimContext context, Path saveDirectory) {
		this.context = context;
		this.saveDirectory = saveDirectory;
	}

	@Override
	public Path getSaveDirectory() {
		return saveDirectory;
	}

	@Override
	public TestSingleplayerContext open() {
		ShimThreading.checkOnTestThread("open");
		ShimWorldBuilder.checkNoWorld(context, "open a world");
		// Opening the world blocks the client until the integrated server is up.
		// 1.20.1 opens a save from a screen to go back to, instead of with a cancel callback.
		//? if <1.21 {
		/*context.runOnClient(minecraft -> minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, saveDirectory.getFileName().toString()));
		*///?} else {
		context.runOnClient(minecraft -> minecraft.createWorldOpenFlows().openWorld(saveDirectory.getFileName().toString(), () -> {
			throw new AssertionError("Level loading should not be canceled");
		}));
		//?}
		return ShimWorldBuilder.joinLoadedWorld(context, this);
	}
}
