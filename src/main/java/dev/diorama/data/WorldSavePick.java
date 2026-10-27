package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.MapPick;
import dev.diorama.bake.TileKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Where a player would stand at a map point, worked out from the world's own chunks, in
 * singleplayer. For tiles saved before they kept their {@link dev.diorama.bake.TileSpots} (Diorama
 * 0.3.0 and older): without it the right-click menu there only works once the player is back
 * nearby.
 *
 * <p>The pick runs on the server thread, which owns the chunks: it hands back those it holds and
 * reads the rest from the save, one at a time as the ray reaches them, without generating any.
 */
public final class WorldSavePick {
	private final IntegratedServer server;
	private final ResourceKey<Level> dimension;
	private final ClientLevel level;

	private WorldSavePick(IntegratedServer server, ClientLevel level) {
		this.server = server;
		this.dimension = level.dimension();
		this.level = level;
	}

	/** The pick for the world being played, or null outside singleplayer. Render thread. */
	public static @Nullable WorldSavePick current() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		ClientLevel level = mc.level;
		return server == null || level == null ? null : new WorldSavePick(server, level);
	}

	/**
	 * The standing spot at map point {@code (u, v)} of the surface, or null where the ray finds
	 * nothing. {@code map} answers for the chunks it still has, and for everything but chunks.
	 */
	public CompletableFuture<@Nullable BlockPos> pick(ChunkSource map, BlockLooks looks, double u, double v, boolean fogOfWar) {
		if (!this.server.isRunning()) {
			return CompletableFuture.completedFuture(null);
		}
		return this.server.submit(() -> {
			// A server that stopped meanwhile runs the task on the caller's thread, where loading a
			// chunk waits forever on the stopped server.
			if (!this.server.isSameThread()) {
				return null;
			}
			ServerLevel serverLevel = this.server.getLevel(this.dimension);
			if (serverLevel == null) {
				return null;
			}
			TileKey tile = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
			MapPick pick = MapPick.fromWorldTop(new Source(map, serverLevel, this.level), looks, tile, fogOfWar);
			return pick.pick(u, v) == MapPick.FOUND ? new BlockPos((int) Math.floor(u), pick.y, pick.z) : null;
		}).exceptionally(t -> {
			Diorama.LOGGER.warn("Couldn't read the world's chunks at map point {}, {}", u, v, t);
			return null;
		});
	}

	/** The map's chunks, and the server's where the map has none. Server thread only. */
	private static final class Source implements ChunkSource {
		private final ChunkSource map;
		private final ServerLevel serverLevel;
		private final ClientLevel level;
		private final Map<Long, ChunkSnapshot> read = new HashMap<>();

		Source(ChunkSource map, ServerLevel serverLevel, ClientLevel level) {
			this.map = map;
			this.serverLevel = serverLevel;
			this.level = level;
		}

		@Override
		public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
			ChunkSnapshot known = this.map.chunk(chunkX, chunkZ);
			if (known != null) {
				return known;
			}
			return this.read.computeIfAbsent(ChunkSnapshot.key(chunkX, chunkZ), k -> {
				// The first step of generation, which a chunk in the save is already past: this loads
				// it if it's there and makes nothing new if it isn't.
				ChunkAccess chunk = this.serverLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, true);
				return chunk == null ? null : ChunkSnapshot.fromServer(chunk, this.level);
			});
		}

		@Override
		public int minY() {
			return this.map.minY();
		}

		@Override
		public int maxY() {
			return this.map.maxY();
		}

		@Override
		public int topY() {
			return this.map.topY();
		}

		@Override
		public int roof() {
			return this.map.roof();
		}

		@Override
		public boolean isSeen(int x, int y, int z) {
			return this.map.isSeen(x, y, z);
		}
	}
}
