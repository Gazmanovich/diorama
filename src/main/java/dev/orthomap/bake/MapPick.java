package dev.orthomap.bake;

import dev.orthomap.Projection;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Finds the block a map pixel shows, by walking the same camera ray the baker traces. The map is
 * a top view, so a screen point alone doesn't say how high the ground is; this does.
 */
public final class MapPick {
	private MapPick() {
	}

	/**
	 * The position just above the first solid block or water surface the ray through map point
	 * {@code (u, v)} reaches, or null where the map has no data.
	 */
	public static @Nullable BlockPos standingSpot(ChunkSource source, BlockLooks looks, double u, double v, int layer) {
		VoxelReader reader = new VoxelReader(source);
		boolean cave = layer != TileKey.SURFACE;
		double sin = Projection.sin();
		double cos = Projection.cos();
		int startY = cave ? Math.min(layer, source.maxY()) : Math.min(source.maxY(), source.topY());
		int limitY = cave ? Math.max(source.minY(), startY - TileBaker.CAVE_DEPTH) : source.minY();
		int x = (int) Math.floor(u);
		double z0 = (v + startY * cos) / sin;
		int by = startY - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = 1 / sin;
		double tMaxZ = (z0 - bz) / cos;
		boolean skippingRock = cave;
		while (by >= limitY) {
			int id = reader.state(x, by, bz);
			if (id == VoxelReader.UNKNOWN) {
				return null;
			}
			byte kind = looks.kind[id];
			if (skippingRock && kind != BlockLooks.SOLID) {
				skippingRock = false;
			}
			if (!skippingRock && (kind == BlockLooks.SOLID || kind == BlockLooks.LAVA || kind == BlockLooks.WATER)) {
				return new BlockPos(x, by + 1, bz);
			}
			if (tMaxY < tMaxZ) {
				by--;
				tMaxY += 1 / sin;
			} else {
				bz--;
				tMaxZ += 1 / cos;
			}
		}
		return null;
	}
}
