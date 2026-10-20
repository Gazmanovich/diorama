package dev.diorama.gameshim;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientWorldContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * The client's world, after Fabric API's TestClientWorldContextImpl, but centred on the player's
 * chunk rather than the chunk cache's centre, which public API doesn't expose.
 */
final class ShimClientWorld implements TestClientWorldContext {
	private final ShimContext context;

	ShimClientWorld(ShimContext context) {
		this.context = context;
	}

	@Override
	public int waitForChunksDownload(int timeout) {
		ShimThreading.checkOnTestThread("waitForChunksDownload");
		return context.waitFor(ShimClientWorld::chunksLoaded, timeout);
	}

	@Override
	public int waitForChunksRender(boolean waitForDownload, int timeout) {
		ShimThreading.checkOnTestThread("waitForChunksRender");
		return context.waitFor(minecraft -> (!waitForDownload || chunksLoaded(minecraft)) && minecraft.levelRenderer.hasRenderedAllSections(), timeout);
	}

	private static boolean chunksLoaded(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return false;
		}
		int distance = minecraft.options.getEffectiveRenderDistance();
		ChunkPos center = minecraft.player.chunkPosition();
		for (int dz = -distance; dz <= distance; dz++) {
			for (int dx = -distance; dx <= distance; dx++) {
				//? if >=26.1 {
				boolean loaded = level.getChunkSource().hasChunk(center.x() + dx, center.z() + dz);
				//?} else {
				/*boolean loaded = level.getChunkSource().hasChunk(center.x + dx, center.z + dz);
				*///?}
				if (!loaded) {
					return false;
				}
			}
		}
		return true;
	}
}
