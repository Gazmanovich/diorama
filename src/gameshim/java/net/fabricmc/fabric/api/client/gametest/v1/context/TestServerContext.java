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

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import net.minecraft.server.MinecraftServer;

/** A running server: the integrated one of a singleplayer world, or a dedicated one. */
public interface TestServerContext {
	/** Runs a command on the server thread, as the server. */
	void runCommand(String command);

	/** Runs code on the server thread and waits for it; whatever it throws is thrown here. */
	<E extends Throwable> void runOnServer(FailableConsumer<MinecraftServer, E> action) throws E;

	/** Computes a value on the server thread and returns it; whatever it throws is thrown here. */
	<T, E extends Throwable> T computeOnServer(FailableFunction<MinecraftServer, T, E> function) throws E;
}
