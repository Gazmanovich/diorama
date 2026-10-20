package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Compares decoded IDs and solid masks with the snapshot, including modded registry IDs. */
public final class VoxelReaderChecks {
	public static void check(ChunkSource source, int chunkX, int chunkZ) {
		ChunkSnapshot snapshot = source.chunk(chunkX, chunkZ);
		if (snapshot == null) {
			throw new AssertionError("No snapshot to check");
		}
		byte[] kinds = BlockLooks.get().kind;
		VoxelReader reader = new VoxelReader(source, kinds);
		for (int section = 0; section < snapshot.sectionCount(); section++) {
			var uniform = snapshot.uniformSection(section);
			var palette = snapshot.section(section);
			for (int i = 0; i < 4096; i++) {
				int x = i & 15;
				int y = i >> 8;
				int z = (i >> 4) & 15;
				var state = uniform != null ? uniform : palette != null ? palette.get(x, y, z) : Blocks.AIR.defaultBlockState();
				int expected = Block.BLOCK_STATE_REGISTRY.getId(state);
				int wx = (chunkX << 4) + x;
				int wy = ((snapshot.minSectionY + section) << 4) + y;
				int wz = (chunkZ << 4) + z;
				if (reader.state(wx, wy, wz) != expected
					|| reader.solid(wx, wy, wz) != (kinds[expected] == BlockLooks.SOLID ? 1 : 0)) {
					throw new AssertionError("Decoded block state " + expected + " incorrectly at " + wx + "," + wy + "," + wz);
				}
			}
		}
	}
}
