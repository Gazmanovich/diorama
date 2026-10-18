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

import net.minecraft.SharedConstants;

/** The world as the client has it. */
public interface TestClientWorldContext {
	int DEFAULT_CHUNK_LOAD_TIMEOUT = SharedConstants.TICKS_PER_MINUTE;

	/** Waits until the chunks within render distance of the player have arrived. */
	int waitForChunksDownload(int timeout);

	/** Waits until the chunks within render distance have arrived, if asked, and been rendered. */
	int waitForChunksRender(boolean waitForDownload, int timeout);
}
