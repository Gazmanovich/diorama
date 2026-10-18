package dev.diorama.gameshim;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Creates a world the way Fabric API's TestWorldBuilderImpl does: through the Create World screen. */
final class ShimWorldBuilder implements TestWorldBuilder {
	private static final int WORLD_LOAD_TIMEOUT_TICKS = 60 * 20;

	private final ShimContext context;
	private boolean useConsistentSettings = true;
	private Consumer<WorldCreationUiState> settingsAdjuster = settings -> {
	};

	ShimWorldBuilder(ShimContext context) {
		this.context = context;
	}

	@Override
	public TestWorldBuilder setUseConsistentSettings(boolean useConsistentSettings) {
		this.useConsistentSettings = useConsistentSettings;
		return this;
	}

	@Override
	public TestWorldBuilder adjustSettings(Consumer<WorldCreationUiState> settingsAdjuster) {
		this.settingsAdjuster = Objects.requireNonNull(settingsAdjuster, "settingsAdjuster");
		return this;
	}

	@Override
	public TestSingleplayerContext create() {
		ShimThreading.checkOnTestThread("create");
		checkNoWorld(context, "create a world");
		Path saveDirectory = context.computeOnClient(minecraft -> {
			CreateWorldScreen.openFresh(minecraft, minecraft.screen);
			if (!(minecraft.screen instanceof CreateWorldScreen screen)) {
				throw new AssertionError("CreateWorldScreen.openFresh did not open the Create World screen");
			}
			WorldCreationUiState settings = screen.getUiState();
			if (useConsistentSettings) {
				setConsistentSettings(settings);
			}
			settingsAdjuster.accept(settings);
			return minecraft.getLevelSource().getBaseDir().resolve(settings.getTargetFolder());
		});
		// Creating the world blocks the client until the integrated server is up.
		context.clickScreenButton("selectWorld.create");
		return joinLoadedWorld(context, new ShimWorldSave(context, saveDirectory));
	}

	@Override
	public TestDedicatedServerContext createServer(Properties serverProperties) {
		throw new UnsupportedOperationException("The Diorama game shim can't start a dedicated server; Fabric API's client game tests can from Minecraft 1.21.4");
	}

	private static void setConsistentSettings(WorldCreationUiState settings) {
		settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
			settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)));
		settings.setSeed("1");
		settings.setGenerateStructures(false);
		settings.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		settings.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		settings.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
	}

	static void checkNoWorld(ShimContext context, String action) {
		context.runOnClient(minecraft -> {
			if (minecraft.getSingleplayerServer() != null || minecraft.level != null) {
				throw new IllegalStateException("Cannot " + action + " while in a world");
			}
		});
	}

	/**
	 * Waits until the world has loaded and the player is in it, getting past the experimental
	 * settings warning and the backup prompt, like Fabric's ClientGameTestImpl.waitForWorldLoad.
	 */
	static ShimSingleplayer joinLoadedWorld(ShimContext context, ShimWorldSave save) {
		for (int i = 0; ; i++) {
			if (context.computeOnClient(minecraft -> isExperimentalWarning(minecraft.screen))) {
				context.clickScreenButton("gui.yes");
			}
			if (context.computeOnClient(minecraft -> minecraft.screen instanceof BackupConfirmScreen)) {
				context.clickScreenButton("selectWorld.backupJoinSkipButton");
			}
			if (context.computeOnClient(minecraft -> minecraft.level != null && minecraft.player != null
				&& !(minecraft.screen instanceof LevelLoadingScreen) && !(minecraft.screen instanceof ReceivingLevelScreen))) {
				break;
			}
			if (i >= WORLD_LOAD_TIMEOUT_TICKS) {
				throw new AssertionError("Timeout loading world");
			}
			context.waitTick();
		}
		MinecraftServer server = context.computeOnClient(minecraft -> minecraft.getSingleplayerServer());
		if (server == null) {
			throw new AssertionError("The world loaded without an integrated server");
		}
		return new ShimSingleplayer(context, save, server);
	}

	private static boolean isExperimentalWarning(Screen screen) {
		return screen instanceof ConfirmScreen
			&& screen.getTitle().getContents() instanceof TranslatableContents title
			&& "selectWorld.warning.experimental.title".equals(title.getKey());
	}
}
