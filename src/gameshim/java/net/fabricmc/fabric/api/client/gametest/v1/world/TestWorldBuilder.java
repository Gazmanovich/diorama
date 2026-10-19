/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Diorama's test shim: the signatures in this package are derived from Fabric API's
 * fabric-client-gametest-api-v1 for Minecraft 1.21.4, trimmed to what Diorama's client game tests
 * call, so the same tests run on Minecraft versions Fabric API doesn't ship that module for. The
 * implementation is in dev.diorama.gameshim. See NOTICE.
 */

package net.fabricmc.fabric.api.client.gametest.v1.world;

import java.util.Properties;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Creates a world through the game's own Create World screen. */
public interface TestWorldBuilder {
	/**
	 * Whether to start from settings that keep tests repeatable: a superflat world with seed 1, no
	 * structures, and no daylight cycle, weather or mob spawning. On by default.
	 */
	TestWorldBuilder setUseConsistentSettings(boolean useConsistentSettings);

	/** Changes the Create World screen's settings before the world is created. */
	TestWorldBuilder adjustSettings(Consumer<WorldCreationUiState> settingsAdjuster);

	/** Creates a singleplayer world and waits until the player is in it. */
	TestSingleplayerContext create();

	/** Not supported by the shim: throws {@link UnsupportedOperationException}. */
	TestDedicatedServerContext createServer(Properties serverProperties);
}
