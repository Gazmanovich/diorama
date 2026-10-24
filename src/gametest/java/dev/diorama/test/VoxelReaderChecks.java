package dev.diorama.test;

import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.VoxelReader;
import dev.diorama.data.ChunkSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Compares decoded IDs and solid masks with the snapshot, including modded registry IDs. It lives
 * outside {@code dev.diorama.bake}: NeoForge loads the test jar as its own module, and two modules
 * can't share a package.
 */
public final class VoxelReaderChecks {
	public static void check(ChunkSource source, int chunkX, int chunkZ) {
		ChunkSnapshot snapshot = source.chunk(chunkX, chunkZ);
		if (snapshot == null) {
			throw new AssertionError("No snapshot to check");
		}
		BlockLooks looks = BlockLooks.get();
		byte[] kinds = looks.kind;
		VoxelReader reader = new VoxelReader(source, looks);
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
				// A filled copycat reads as its look in its material.
				var material = snapshot.copycatMaterial(wx, wy, wz);
				if (material != null) {
					expected = looks.copycat(expected, Block.BLOCK_STATE_REGISTRY.getId(material));
				}
				if (reader.state(wx, wy, wz) != expected
					|| reader.solid(wx, wy, wz) != (kinds[expected] == BlockLooks.SOLID ? 1 : 0)) {
					throw new AssertionError("Decoded block state " + expected + " incorrectly at " + wx + "," + wy + "," + wz);
				}
			}
		}
	}
}
