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

/**
 * A dedicated server. The shim can't start one, so {@code TestWorldBuilder.createServer} throws;
 * this type is here so tests that use it on newer versions still compile.
 */
public interface TestDedicatedServerContext extends TestServerContext, AutoCloseable {
	TestDedicatedServerConnection connect();

	@Override
	void close();
}
