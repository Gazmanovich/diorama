package dev.diorama.bake;

import dev.diorama.Facing;
import dev.diorama.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;

/** A {@link ChunkSource} of the world's chunks in turned space; see {@link Facing}. */
public final class TurnedSource implements ChunkSource {
	private final ChunkSource world;
	private final Facing facing;

	public TurnedSource(ChunkSource world, Facing facing) {
		this.world = world;
		this.facing = facing;
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		ChunkSnapshot chunk = this.world.chunk(this.facing.worldBlockX(chunkX, chunkZ), this.facing.worldBlockZ(chunkX, chunkZ));
		return chunk == null ? null : chunk.turned(this.facing);
	}

	@Override
	public int topBelow(int chunkX, int chunkZ, int ceiling) {
		return this.world.topBelow(this.facing.worldBlockX(chunkX, chunkZ), this.facing.worldBlockZ(chunkX, chunkZ), ceiling);
	}

	@Override
	public int minY() {
		return this.world.minY();
	}

	@Override
	public int maxY() {
		return this.world.maxY();
	}

	@Override
	public int topY() {
		return this.world.topY();
	}

	@Override
	public int roof() {
		return this.world.roof();
	}

	@Override
	public boolean depthCaves() {
		return this.world.depthCaves();
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		return this.world.isSeen(this.facing.worldBlockX(x, z), y, this.facing.worldBlockZ(x, z));
	}
}
