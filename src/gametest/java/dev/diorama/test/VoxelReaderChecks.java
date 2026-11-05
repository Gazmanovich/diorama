package dev.diorama.test;

import dev.diorama.Facing;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.TurnedSource;
import dev.diorama.bake.VoxelReader;
import dev.diorama.data.ChunkSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Compares decoded IDs and solid masks with the snapshot, including modded registry IDs, in every
 * facing's turned view too. It lives
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
		// Asked only whether blocks are solid, a reader builds its masks from the blocks, without
		// the IDs: this one is never asked for an ID, and tells smaller blocks apart.
		VoxelReader solidOnly = new VoxelReader(source, looks, true);
		for (int section = 0; section < snapshot.sectionCount(); section++) {
			var uniform = snapshot.uniformSection(section);
			var palette = snapshot.section(section);
			// Readers number each palette entry once, from the container's packed blocks. Where a
			// loader or mod keeps them from being read, sections go block by block: still right,
			// but without the speed, so that fails here.
			if (palette != null && snapshot.packed(section) == null) {
				throw new AssertionError("Section " + section + "'s packed blocks can't be read");
			}
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
				int solid = kinds[expected] == BlockLooks.SOLID ? looks.shape[expected] != 0 ? 2 : 1 : 0;
				if (solidOnly.solid(wx, wy, wz) != solid) {
					throw new AssertionError("Solid mask without IDs wrong for block state " + expected + " at " + wx + "," + wy + "," + wz);
				}
				if (reader.state(wx, wy, wz) != expected
					|| reader.solid(wx, wy, wz) != (kinds[expected] == BlockLooks.SOLID ? 1 : 0)) {
					throw new AssertionError("Decoded block state " + expected + " incorrectly at " + wx + "," + wy + "," + wz);
				}
			}
		}
		checkCasters(source, snapshot, looks);
		// Turned from the chunk as it is in the world: a map facing another way hands out views.
		for (Facing facing : Facing.values()) {
			if (facing != Facing.NORTH && snapshot.chunk() == snapshot) {
				checkTurned(source, snapshot, looks, facing);
			}
		}
	}

	/**
	 * The casters' skip bits: a block without one must cast nothing, being open or a whole cube
	 * with whole cubes above, west and south. The neighbours are read first, as the casters read
	 * them by then.
	 */
	private static void checkCasters(ChunkSource source, ChunkSnapshot snapshot, BlockLooks looks) {
		VoxelReader reader = new VoxelReader(source, looks, true);
		int x0 = snapshot.chunkX << 4;
		int z0 = snapshot.chunkZ << 4;
		reader.solid(x0 - 1, snapshot.minSectionY << 4, z0);
		reader.solid(x0, snapshot.minSectionY << 4, z0 + 16);
		int skippedSolid = 0;
		for (int section = 0; section < snapshot.sectionCount(); section++) {
			int sy = snapshot.minSectionY + section;
			long[] mayCast = reader.casterSection(snapshot.chunkX, sy, snapshot.chunkZ);
			if (mayCast == null) {
				throw new AssertionError("No caster bits for section " + section);
			}
			for (int i = 0; i < 4096; i++) {
				if ((mayCast[i >> 6] >>> i & 1) != 0) {
					continue;
				}
				int x = x0 + (i & 15);
				int y = (sy << 4) + (i >> 8);
				int z = z0 + ((i >> 4) & 15);
				int solid = reader.solid(x, y, z);
				if (solid != 0) {
					skippedSolid++;
				}
				if (solid != 0 && (solid != 1 || reader.solid(x, y + 1, z) != 1 || reader.solid(x - 1, y, z) != 1 || reader.solid(x, y, z + 1) != 1)) {
					throw new AssertionError("Caster skipped at " + x + "," + y + "," + z);
				}
			}
		}
		// Every generated chunk has rock under rock, so bits that only skip open blocks aren't doing the job.
		if (skippedSolid == 0) {
			throw new AssertionError("The casters skip no buried block in this chunk");
		}
	}

	/** The same chunk in a turned view: each block where the facing puts it, turned with it. */
	private static void checkTurned(ChunkSource source, ChunkSnapshot snapshot, BlockLooks looks, Facing facing) {
		TurnedSource turned = new TurnedSource(source, facing);
		VoxelReader reader = new VoxelReader(turned, looks);
		VoxelReader solidOnly = new VoxelReader(turned, looks, true);
		int viewX = facing.blockX(snapshot.chunkX, snapshot.chunkZ);
		int viewZ = facing.blockZ(snapshot.chunkX, snapshot.chunkZ);
		for (int section = 0; section < snapshot.sectionCount(); section++) {
			var uniform = snapshot.uniformSection(section);
			var palette = snapshot.section(section);
			for (int i = 0; i < 4096; i++) {
				int wx = (viewX << 4) + (i & 15);
				int wy = ((snapshot.minSectionY + section) << 4) + (i >> 8);
				int wz = (viewZ << 4) + ((i >> 4) & 15);
				int worldX = facing.worldBlockX(wx, wz);
				int worldZ = facing.worldBlockZ(wx, wz);
				if (worldX >> 4 != snapshot.chunkX || worldZ >> 4 != snapshot.chunkZ) {
					throw new AssertionError(facing + " view of " + wx + "," + wz + " is outside the chunk");
				}
				var state = uniform != null ? uniform : palette != null ? palette.get(worldX & 15, i >> 8, worldZ & 15) : Blocks.AIR.defaultBlockState();
				int expected = Block.BLOCK_STATE_REGISTRY.getId(facing.turned(state));
				var material = snapshot.copycatMaterial(worldX, wy, worldZ);
				if (material != null) {
					expected = looks.copycat(expected, Block.BLOCK_STATE_REGISTRY.getId(facing.turned(material)));
				}
				int solid = looks.kind[expected] == BlockLooks.SOLID ? looks.shape[expected] != 0 ? 2 : 1 : 0;
				if (reader.state(wx, wy, wz) != expected || solidOnly.solid(wx, wy, wz) != solid) {
					throw new AssertionError(facing + " view read block state " + reader.state(wx, wy, wz) + " for " + expected + " at " + wx + "," + wy + "," + wz);
				}
			}
		}
	}
}
