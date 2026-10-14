package dev.diorama.bake;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jspecify.annotations.Nullable;

/**
 * What a bake records for the map overlays: a few bits per pixel about the block it shows, so
 * switching the chunk grid and slime chunks on and off only redraws them. {@code slimeSeed} is
 * the world seed when slime chunks can be worked out (singleplayer, overworld), else null.
 * {@code spawnSpots} records spawn spots, which depend on light, so turning them on or off bakes
 * again and light changes refresh them.
 */
public record Overlays(@Nullable Long slimeSeed, boolean spawnSpots) {
	/** A line on the west edge of a chunk. */
	public static final int CHUNK_WEST = 1;
	/** A line on the north edge of a chunk, on top faces. */
	public static final int CHUNK_NORTH = 2;
	/** In a slime chunk. */
	public static final int SLIME = 4;
	/** The middle of a top face where a monster could spawn at night: no block light. */
	public static final int SPAWN_AT_NIGHT = 8;
	/** The same with little enough sky light that monsters can spawn there by day too. */
	public static final int SPAWN_ANY_TIME = 16;
	/** By day, overworld monsters need the light at their feet at most this bright. */
	public static final int DAYLIGHT_SPAWN_LIMIT = 7;

	/** Minecraft's own rule: a tenth of chunks, picked from the world seed. */
	public static boolean slimeChunk(long seed, int chunkX, int chunkZ) {
		return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
	}
}
