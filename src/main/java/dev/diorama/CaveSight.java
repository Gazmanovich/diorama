package dev.diorama;

import dev.diorama.bake.BlockLooks;
import dev.diorama.data.SeenBlocks;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Marks the blocks the player can see. Spreads from their eyes through open space within
 * {@link #RADIUS}, but only into blocks a straight line from the eyes reaches without passing
 * through a solid block. Solid blocks on that line count as seen; the spread stops there. So
 * neither a cave behind a wall nor a room around a bend gets revealed.
 *
 * <p>Every block this touches lies in the cube {@link #RADIUS} around the eye block, and a single
 * spread asks about each one many times (once per line through it). So each block is read from
 * the world once, the first time it's needed, and kept in a flat array for the rest of the spread.
 */
final class CaveSight {
	static final int RADIUS = 24;
	static final int MAX_BLOCKS = 60_000;
	private static final int SIZE = 2 * RADIUS + 1;
	private static final Direction[] DIRECTIONS = Direction.values();

	/** A cell's low two bits: not read yet, open, or solid. */
	private static final byte UNKNOWN = 0;
	private static final byte OPEN = 1;
	private static final byte SOLID = 2;
	private static final byte KIND = 3;
	private static final byte VISITED = 4;

	private final ClientLevel level;
	private final byte[] cells = new byte[SIZE * SIZE * SIZE];
	/** Each block joins the queue at most once, and the spread stops a few joins past the cap. */
	private final int[] queue = new int[MAX_BLOCKS + DIRECTIONS.length];
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private BlockLooks looks;
	private int eyeX;
	private int eyeY;
	private int eyeZ;
	private int visited;

	CaveSight(ClientLevel level) {
		this.level = level;
	}

	/** How many blocks the last spread looked at. */
	int visited() {
		return this.visited;
	}

	/**
	 * Marks what's visible from {@code eyes} in {@code seen}. Returns the box around the blocks that
	 * weren't seen before as {x0, y0, z0, x1, y1, z1}; x0 is {@code Integer.MAX_VALUE} if none.
	 */
	int[] spread(BlockLooks looks, Vec3 eyes, SeenBlocks seen) {
		this.looks = looks;
		this.eyeX = Mth.floor(eyes.x);
		this.eyeY = Mth.floor(eyes.y);
		this.eyeZ = Mth.floor(eyes.z);
		Arrays.fill(this.cells, (byte) 0);
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		int minY = this.level.getMinY();
		int maxY = this.level.getMaxY();
		int r2 = RADIUS * RADIUS;
		int head = 0;
		int tail = 0;
		int start = index(0, 0, 0);
		this.queue[tail++] = start;
		this.cells[start] |= VISITED;
		this.visited = 1;
		while (head < tail && this.visited < MAX_BLOCKS) {
			int cell = this.queue[head++];
			int x = this.eyeX + cell % SIZE - RADIUS;
			int z = this.eyeZ + cell / SIZE % SIZE - RADIUS;
			int y = this.eyeY + cell / (SIZE * SIZE) - RADIUS;
			reveal(seen, x, y, z, box);
			for (Direction d : DIRECTIONS) {
				int nx = x + d.getStepX();
				int ny = y + d.getStepY();
				int nz = z + d.getStepZ();
				int dx = nx - this.eyeX;
				int dy = ny - this.eyeY;
				int dz = nz - this.eyeZ;
				if (dx * dx + dy * dy + dz * dz > r2 || ny < minY || ny > maxY) {
					continue;
				}
				int next = index(dx, dy, dz);
				if ((this.cells[next] & VISITED) != 0) {
					continue;
				}
				this.cells[next] |= VISITED;
				this.visited++;
				if (!this.inSight(eyes, nx, ny, nz)) {
					continue;
				}
				if (this.solid(next, nx, ny, nz)) {
					reveal(seen, nx, ny, nz, box);
				} else {
					this.queue[tail++] = next;
				}
			}
		}
		return box;
	}

	private static int index(int dx, int dy, int dz) {
		return ((dy + RADIUS) * SIZE + dz + RADIUS) * SIZE + dx + RADIUS;
	}

	/** True if the line from the eyes to the block's centre passes only through open blocks. */
	private boolean inSight(Vec3 eyes, int tx, int ty, int tz) {
		double dx = tx + 0.5 - eyes.x;
		double dy = ty + 0.5 - eyes.y;
		double dz = tz + 0.5 - eyes.z;
		int x = this.eyeX;
		int y = this.eyeY;
		int z = this.eyeZ;
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double maxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - eyes.x : eyes.x - x) * deltaX;
		double maxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - eyes.y : eyes.y - y) * deltaY;
		double maxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - eyes.z : eyes.z - z) * deltaZ;
		// The array cell under (x, y, z), moved along with it.
		int cell = index(0, 0, 0);
		int target = index(tx - this.eyeX, ty - this.eyeY, tz - this.eyeZ);
		int strideY = stepY * SIZE * SIZE;
		int strideZ = stepZ * SIZE;
		int x0 = this.eyeX - RADIUS;
		int y0 = this.eyeY - RADIUS;
		int z0 = this.eyeZ - RADIUS;
		for (int i = 0; i < 4 * RADIUS; i++) {
			if (maxX < maxY && maxX < maxZ) {
				x += stepX;
				cell += stepX;
				maxX += deltaX;
			} else if (maxY < maxZ) {
				y += stepY;
				cell += strideY;
				maxY += deltaY;
			} else {
				z += stepZ;
				cell += strideZ;
				maxZ += deltaZ;
			}
			if (Integer.compareUnsigned(x - x0, SIZE) >= 0 || Integer.compareUnsigned(y - y0, SIZE) >= 0 || Integer.compareUnsigned(z - z0, SIZE) >= 0) {
				// Doesn't happen: the line runs between the eye and the target, both in the cube.
				// Out here the cell would be some other block, so ask the world directly.
				if (this.read(x, y, z) == SOLID) {
					return false;
				}
				continue;
			}
			if (cell == target) {
				return true;
			}
			if (this.solid(cell, x, y, z)) {
				return false;
			}
		}
		return false;
	}

	/** Whether the block in {@code cell} at x, y, z is solid, reading it from the world the first time. */
	private boolean solid(int cell, int x, int y, int z) {
		int kind = this.cells[cell] & KIND;
		if (kind == UNKNOWN) {
			kind = this.read(x, y, z);
			this.cells[cell] |= (byte) kind;
		}
		return kind == SOLID;
	}

	private byte read(int x, int y, int z) {
		int id = BlockLooks.id(this.level.getBlockState(this.pos.set(x, y, z)));
		return id < this.looks.kind.length && this.looks.kind[id] == BlockLooks.SOLID ? SOLID : OPEN;
	}

	private static void reveal(SeenBlocks seen, int x, int y, int z, int[] box) {
		if (seen.markSeen(x, y, z)) {
			box[0] = Math.min(box[0], x);
			box[1] = Math.min(box[1], y);
			box[2] = Math.min(box[2], z);
			box[3] = Math.max(box[3], x);
			box[4] = Math.max(box[4], y);
			box[5] = Math.max(box[5], z);
		}
	}
}
