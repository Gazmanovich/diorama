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

package net.fabricmc.fabric.api.client.gametest.v1.context;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * What a client game test drives the game through. Call it from the test thread only.
 *
 * <p>Unlike Fabric's implementation, the shim doesn't run the game in lockstep with the test: the
 * game keeps running at its own speed between calls.
 */
public interface ClientGameTestContext {
	/** For {@link #waitFor(Predicate, int)}: wait as long as it takes. */
	int NO_TIMEOUT = -1;

	/** Ten seconds of ticks. */
	int DEFAULT_TIMEOUT = 10 * SharedConstants.TICKS_PER_SECOND;

	/** Waits until the client has finished one more tick. */
	void waitTick();

	/** Waits until the client has finished {@code ticks} more ticks. */
	void waitTicks(int ticks);

	/**
	 * Waits until the predicate holds, checking once per tick on the client thread, and returns how
	 * many ticks that took. Throws if it still doesn't hold after {@code timeout} ticks.
	 */
	int waitFor(Predicate<Minecraft> predicate, int timeout);

	/** Opens the screen the supplier makes on the client thread; a null screen closes it. */
	void setScreen(Supplier<Screen> screen);

	/** Presses the button on the open screen labelled with this translation key, or throws. */
	void clickScreenButton(String translationKey);

	/**
	 * Saves the next complete frame as {@code screenshots/<counter>_<name>.png} in the game
	 * directory and returns its path.
	 */
	Path takeScreenshot(String name);

	TestInput getInput();

	/** Creates a world or server; see {@link TestWorldBuilder}. */
	TestWorldBuilder worldBuilder();

	/** Runs code on the client thread and waits for it; whatever it throws is thrown here. */
	<E extends Throwable> void runOnClient(FailableConsumer<Minecraft, E> action) throws E;

	/** Computes a value on the client thread and returns it; whatever it throws is thrown here. */
	<T, E extends Throwable> T computeOnClient(FailableFunction<Minecraft, T, E> function) throws E;
}
