package dev.orthomap.bake;

import dev.orthomap.Projection;
import dev.orthomap.data.ChunkSnapshot;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Ray traces one map tile through the block data.
 *
 * <p>Every texture pixel fires four rays along the camera direction. Each ray walks the blocks in
 * its column (the camera faces due north, so a ray never changes x) until it hits something solid.
 * That hit is then lit: in the surface view by the sun, with a second ray toward it for shadows;
 * in a cave slice by the game's own block and sky light. Corner ambient occlusion and partial
 * colour from plants, glass and water come on top.
 */
public final class TileBaker {
	/** Rotated-grid sample offsets inside a pixel. */
	private static final double[][] SAMPLES = {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}};

	// Direction toward the sun: high in the west, slightly south, so shadows fall to the east.
	private static final double SUN_X;
	private static final double SUN_Y;
	private static final double SUN_Z;
	/** Small per-sample nudges of the sun direction, which soften shadow edges. */
	private static final double[][] SUN_JITTER = {{0.03, 0.0, -0.02}, {-0.02, 0.0, 0.03}, {0.02, 0.0, 0.02}, {-0.03, 0.0, -0.03}};

	static {
		double x = -0.55;
		double y = 0.78;
		double z = 0.30;
		double len = Math.sqrt(x * x + y * y + z * z);
		SUN_X = x / len;
		SUN_Y = y / len;
		SUN_Z = z / len;
	}

	private static final float AMBIENT = 0.50F;
	private static final float SUN = 0.62F;
	private static final float[] AO_LEVELS = {0.6F, 0.74F, 0.87F, 1.0F};
	private static final float SATURATION = 1.18F;
	private static final float EXPOSURE = 1.06F;
	/** How far below the cut a cave slice looks. */
	public static final int CAVE_DEPTH = 48;
	private static final int SHADOW_STEPS = 96;
	/** Longest shadow, in blocks along the sun direction. Bounds how far an edit can reach. */
	public static final double SHADOW_DISTANCE = 40;
	/** How far east, north and down from a block its shadow can land. */
	public static final int SHADOW_REACH_X = (int) Math.ceil(SHADOW_DISTANCE * 0.58) + 1;
	public static final int SHADOW_REACH_Z = (int) Math.ceil(SHADOW_DISTANCE * 0.32) + 1;
	public static final int SHADOW_REACH_Y = (int) Math.ceil(SHADOW_DISTANCE * 0.82) + 1;

	private static final float[] SRGB_TO_LINEAR = new float[256];
	private static final int[] LINEAR_TO_SRGB = new int[4096];

	static {
		for (int i = 0; i < 256; i++) {
			double v = i / 255.0;
			SRGB_TO_LINEAR[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
		}
		for (int i = 0; i < 4096; i++) {
			double v = i / 4095.0;
			double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
			LINEAR_TO_SRGB[i] = (int) Math.round(Math.max(0, Math.min(1, s)) * 255);
		}
	}

	private final TileKey key;
	private final BlockLooks looks;
	private final VoxelReader reader;
	private final boolean cave;
	private final int startY;
	private final int limitY;
	private final int topY;
	/** Cave slice that may only show blocks the player has seen. */
	private final boolean fogOfWar;
	private final ChunkSource source;

	// Result of the last trace, in linear light.
	private float outR;
	private float outG;
	private float outB;

	private TileBaker(TileKey key, ChunkSource source, BlockLooks looks) {
		this.key = key;
		this.looks = looks;
		this.reader = new VoxelReader(source);
		this.cave = key.isCave();
		this.source = source;
		this.fogOfWar = this.cave && source.fogOfWar();
		this.topY = localTop(key, source);
		this.startY = this.cave ? Math.min(key.layer(), source.maxY()) : this.topY;
		this.limitY = this.cave ? Math.max(source.minY(), this.startY - CAVE_DEPTH) : source.minY();
	}

	/**
	 * One above the tallest block any ray or shadow ray of this tile can touch. Starting rays here
	 * instead of at the tallest block in the whole dimension skips a lot of empty sky.
	 */
	private static int localTop(TileKey key, ChunkSource source) {
		int globalTop = Math.min(source.maxY(), source.topY());
		double minU = key.minU();
		double minV = key.minV();
		int x0 = Math.floorDiv((int) Math.floor(minU) - 1, 16);
		int x1 = Math.floorDiv((int) Math.ceil(minU + Projection.TILE_UNITS) + SHADOW_REACH_X + 1, 16);
		int z0 = Math.floorDiv((int) Math.floor(Projection.zAt(minV, source.minY())) - 1, 16);
		int z1 = Math.floorDiv((int) Math.ceil(Projection.zAt(minV + Projection.TILE_UNITS, globalTop)) + SHADOW_REACH_Z + 1, 16);
		int top = Integer.MIN_VALUE;
		for (int cx = x0 - 2; cx <= x1; cx++) {
			for (int cz = z0; cz <= z1 + 1; cz++) {
				ChunkSnapshot chunk = source.chunk(cx, cz);
				if (chunk != null && chunk.maxY != Integer.MIN_VALUE) {
					top = Math.max(top, chunk.maxY + 1);
				}
			}
		}
		return top == Integer.MIN_VALUE ? globalTop : Math.min(globalTop, top);
	}

	/** Bakes the whole tile, or only the {@link TileImage#BLOCK}-pixel blocks set in {@code blocks}. */
	public static TileImage bake(TileKey key, ChunkSource source, BlockLooks looks, @Nullable BitSet blocks) {
		TileBaker baker = new TileBaker(key, source, looks);
		int size = Projection.TILE_PIXELS;
		if (blocks == null) {
			int[] pixels = new int[size * size];
			baker.bakePixels(0, 0, size, size, pixels);
			return TileImage.full(key, Mipmaps.build(pixels, size), coverage(pixels, size, size));
		}
		int perRow = size / TileImage.BLOCK;
		List<TileImage.Patch> patches = new ArrayList<>();
		for (int i = blocks.nextSetBit(0); i >= 0; i = blocks.nextSetBit(i + 1)) {
			int bx = i % perRow;
			int by = i / perRow;
			int[] pixels = new int[TileImage.BLOCK * TileImage.BLOCK];
			baker.bakePixels(bx * TileImage.BLOCK, by * TileImage.BLOCK, TileImage.BLOCK, TileImage.BLOCK, pixels);
			patches.add(new TileImage.Patch(bx, by, Mipmaps.build(pixels, TileImage.BLOCK), coverage(pixels, TileImage.BLOCK, TileImage.BLOCK)));
		}
		if (VoxelReader.DEBUG && !baker.reader.missing.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (long m : baker.reader.missing) {
				int cx = (int) (m >> 32);
				int cz = (int) m;
				sb.append(' ').append(cx).append(',').append(cz).append(source.chunk(cx, cz) == null ? "(still missing)" : "(present now)");
			}
			dev.orthomap.Orthomap.LOGGER.info("Partial bake of {} ({} blocks) hit unknown chunks:{}", key, blocks.cardinality(), sb);
		}
		return TileImage.partial(key, patches);
	}

	private void bakePixels(int px0, int py0, int width, int height, int[] out) {
		if (this.startY <= this.limitY) {
			return;
		}
		double minU = this.key.minU();
		double minV = this.key.minV();
		double unitsPerPixel = 1.0 / Projection.PIXELS_PER_UNIT;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float r = 0;
				float g = 0;
				float b = 0;
				int known = 0;
				for (int s = 0; s < SAMPLES.length; s++) {
					double u = minU + (px0 + x + SAMPLES[s][0]) * unitsPerPixel;
					double v = minV + (py0 + y + SAMPLES[s][1]) * unitsPerPixel;
					if (this.trace(u, v, s)) {
						r += this.outR;
						g += this.outG;
						b += this.outB;
						known++;
					}
				}
				if (known > 0) {
					out[y * width + x] = abgr(r / known, g / known, b / known, known * 255 / SAMPLES.length);
				}
			}
		}
	}

	/** Traces one ray. Returns false when it ran into a chunk the map has no data for. */
	private boolean trace(double u, double v, int sample) {
		final double sin = Projection.SIN;
		final double cos = Projection.COS;
		final int x = (int) Math.floor(u);
		final double fx = u - x;
		final double y0 = this.startY;
		final double z0 = Projection.zAt(v, y0);

		int by = this.startY - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = (y0 - by) / sin;
		double tMaxZ = (z0 - bz) / cos;
		final double tDeltaY = 1.0 / sin;
		final double tDeltaZ = 1.0 / cos;
		double t = 0;
		boolean enteredTop = true;

		boolean skippingRock = this.cave;
		// Colour picked up from plants and glass on the way down, applied to the surface below.
		float overR = 0;
		float overG = 0;
		float overB = 0;
		float through = 1;
		// Water the ray passed through.
		int waterId = -1;
		double waterT = 0;
		int waterBy = 0;
		int waterBz = 0;

		while (by >= this.limitY) {
			int id = this.reader.state(x, by, bz);
			if (id == VoxelReader.UNKNOWN) {
				return false;
			}
			byte kind = this.looks.kind[id];
			if (skippingRock) {
				if (kind == BlockLooks.SOLID) {
					// Still inside the rock above the first open space below the cut.
					if (tMaxY < tMaxZ) {
						by--;
						t = tMaxY;
						tMaxY += tDeltaY;
						enteredTop = true;
					} else {
						bz--;
						t = tMaxZ;
						tMaxZ += tDeltaZ;
						enteredTop = false;
					}
					continue;
				}
				skippingRock = false;
			}

			switch (kind) {
				case BlockLooks.SOLID, BlockLooks.LAVA -> {
					if (this.fogOfWar && !this.source.isSeen(x, by, bz)
						&& !(enteredTop ? this.source.isSeen(x, by + 1, bz) : this.source.isSeen(x, by, bz + 1))) {
						return false;
					}
					double hitY = y0 - t * sin;
					double hitZ = z0 - t * cos;
					this.shade(id, x, by, bz, fx, hitY, hitZ, enteredTop, sample);
					if (through < 1) {
						// The overlay tints the albedo; approximate by mixing it in after lighting.
						float light = luminance(this.outR, this.outG, this.outB) / Math.max(0.05F, luminance(this.albedoR, this.albedoG, this.albedoB));
						this.outR = this.outR * through + overR * light;
						this.outG = this.outG * through + overG * light;
						this.outB = this.outB * through + overB * light;
					}
					if (waterId >= 0) {
						this.mixWater(waterId, x, waterBy, waterBz, u, z0, waterT, t, sample);
					}
					return true;
				}
				case BlockLooks.WATER -> {
					if (waterId < 0) {
						waterId = id;
						waterT = t;
						waterBy = by;
						waterBz = bz;
					}
				}
				case BlockLooks.PLANT, BlockLooks.GLASS -> {
					float w = kind == BlockLooks.PLANT ? 0.4F : 0.25F;
					this.albedo(id, x, bz, true);
					overR += through * w * this.albedoR;
					overG += through * w * this.albedoG;
					overB += through * w * this.albedoB;
					through *= 1 - w;
				}
				default -> {
				}
			}

			if (tMaxY < tMaxZ) {
				by--;
				t = tMaxY;
				tMaxY += tDeltaY;
				enteredTop = true;
			} else {
				bz--;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				enteredTop = false;
			}
		}

		if (this.fogOfWar && (skippingRock || waterId < 0 || !this.source.isSeen(x, waterBy, waterBz))) {
			return false;
		}
		if (skippingRock) {
			// Solid rock all the way down the slice: draw the cut face.
			this.outR = 0.0065F;
			this.outG = 0.0068F;
			this.outB = 0.0085F;
		} else if (waterId >= 0) {
			this.outR = 0;
			this.outG = 0;
			this.outB = 0;
			this.mixWater(waterId, x, waterBy, waterBz, u, z0, waterT, waterT + 64, sample);
		} else {
			this.outR = 0.01F;
			this.outG = 0.01F;
			this.outB = 0.015F;
		}
		return true;
	}

	private float albedoR;
	private float albedoG;
	private float albedoB;

	/** Sets {@code albedo*} to the block's linear colour for its top or front face at this column. */
	private void albedo(int id, int x, int z, boolean top) {
		BlockLooks l = this.looks;
		int base = top ? l.top[id] : l.side[id];
		int tinted = top ? l.topTinted[id] : l.sideTinted[id];
		float w = top ? l.topTintWeight[id] : l.sideTintWeight[id];
		int tint = this.tint(id, x, z);
		float bw = 1 - w;
		this.albedoR = SRGB_TO_LINEAR[(base >> 16) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 16) & 0xFF] * SRGB_TO_LINEAR[(tint >> 16) & 0xFF] * w;
		this.albedoG = SRGB_TO_LINEAR[(base >> 8) & 0xFF] * bw + SRGB_TO_LINEAR[(tinted >> 8) & 0xFF] * SRGB_TO_LINEAR[(tint >> 8) & 0xFF] * w;
		this.albedoB = SRGB_TO_LINEAR[base & 0xFF] * bw + SRGB_TO_LINEAR[tinted & 0xFF] * SRGB_TO_LINEAR[tint & 0xFF] * w;
	}

	private int tint(int id, int x, int z) {
		byte kind = this.looks.tint[id];
		if (kind == BlockLooks.TINT_NONE) {
			int constant = this.looks.constantTint[id];
			return constant < 0 ? 0xFFFFFF : constant;
		}
		ChunkSnapshot snapshot = this.reader.snapshot(x, z);
		if (snapshot == null) {
			return 0xFFFFFF;
		}
		int i = ((z & 15) << 4) | (x & 15);
		return switch (kind) {
			case BlockLooks.TINT_GRASS -> snapshot.grass[i];
			case BlockLooks.TINT_FOLIAGE -> snapshot.foliage[i];
			case BlockLooks.TINT_DRY_FOLIAGE -> snapshot.dryFoliage[i];
			case BlockLooks.TINT_WATER -> snapshot.water[i];
			default -> 0xFFFFFF;
		};
	}

	private void shade(int id, int x, int by, int bz, double fx, double hitY, double hitZ, boolean top, int sample) {
		this.albedo(id, x, bz, top);
		// The open cell in front of the face the ray hit.
		int frontY = top ? by + 1 : by;
		int frontZ = top ? bz : bz + 1;
		float ao = top
			? this.ambientOcclusionTop(x, by + 1, bz, fx, hitZ - bz)
			: this.ambientOcclusionSouth(x, by, bz + 1, fx, hitY - by);
		int blockLight = this.reader.blockLight(x, frontY, frontZ);
		float emission = this.looks.emission[id] / 15.0F;

		float lr;
		float lg;
		float lb;
		if (this.cave) {
			int skyLight = this.reader.skyLight(x, frontY, frontZ);
			float bl = blockLight / 15.0F;
			float sl = skyLight / 15.0F * 0.8F;
			float warm = (float) Math.pow(bl, 1.5);
			float cool = (float) Math.pow(sl, 1.5);
			// Unlit caves stay readable; torches, lava and daylight make them glow.
			float base = 0.42F;
			lr = (base * 0.92F + warm * 0.9F + cool * 0.6F) * ao;
			lg = (base * 0.96F + warm * 0.75F + cool * 0.65F) * ao;
			lb = (base * 1.08F + warm * 0.5F + cool * 0.75F) * ao;
			float depth = (float) (this.startY - hitY);
			float fade = 1 - Math.min(0.5F, depth / 60.0F);
			lr *= fade;
			lg *= fade;
			lb *= fade;
		} else {
			int skyLight = this.reader.skyLight(x, frontY, frontZ);
			float sky = 0.45F + 0.55F * skyLight / 15.0F;
			double[] j = SUN_JITTER[sample];
			double sx = SUN_X + j[0];
			double sy = SUN_Y + j[1];
			double sz = SUN_Z + j[2];
			double lambert = top ? sy : Math.max(0, sz);
			float direct = 0;
			if (lambert > 0) {
				double ox = x + fx;
				double oy = top ? by + 1 + 1e-4 : hitY;
				double oz = top ? hitZ : bz + 1 + 1e-4;
				direct = this.inShadow(ox, oy, oz, sx, sy, sz) ? 0 : (float) (SUN * lambert);
			}
			float ambient = AMBIENT * ao * sky;
			lr = ambient * 0.90F + direct * 1.06F;
			lg = ambient * 0.95F + direct * 1.00F;
			lb = ambient * 1.08F + direct * 0.90F;
			float glow = blockLight / 15.0F;
			glow = glow * glow * 0.25F;
			lr += glow;
			lg += glow * 0.8F;
			lb += glow * 0.5F;
		}
		if (emission > 0) {
			float e = 0.35F + emission * 0.85F;
			lr = Math.max(lr, e);
			lg = Math.max(lg, e);
			lb = Math.max(lb, e);
		}
		if (!top) {
			// Faces that aren't up read darker, the way Minecraft shades its own block sides.
			lr *= 0.82F;
			lg *= 0.82F;
			lb *= 0.82F;
		}
		this.outR = this.albedoR * lr;
		this.outG = this.albedoG * lg;
		this.outB = this.albedoB * lb;
	}

	private void mixWater(int waterId, int x, int by, int bz, double u, double z0, double tEntry, double tHit, int sample) {
		this.albedo(waterId, x, bz, true);
		float wr = this.albedoR;
		float wg = this.albedoG;
		float wb = this.albedoB;
		float light;
		if (this.cave) {
			int bl = this.reader.blockLight(x, by + 1, bz);
			int sl = this.reader.skyLight(x, by + 1, bz);
			light = 0.42F + (float) Math.pow(Math.max(bl, sl * 0.8F) / 15.0F, 1.5) * 0.8F;
		} else {
			double sy = SUN_Y + SUN_JITTER[sample][1];
			double ox = u;
			double oy = this.startY - tEntry * Projection.SIN + 1e-4;
			double oz = z0 - tEntry * Projection.COS;
			boolean shadow = this.inShadow(ox, oy, oz, SUN_X, sy, SUN_Z);
			light = AMBIENT + (shadow ? 0 : (float) (SUN * sy));
		}
		double depth = Math.max(0, tHit - tEntry);
		float a = (float) Math.max(0.5, Math.min(0.94, 1 - Math.exp(-0.2 * depth)));
		this.outR = this.outR * (1 - a) + wr * light * a;
		this.outG = this.outG * (1 - a) + wg * light * a;
		this.outB = this.outB * (1 - a) + wb * light * a;
	}

	/** Walks from a point toward the sun; true if a solid block is in the way. */
	private boolean inShadow(double ox, double oy, double oz, double dx, double dy, double dz) {
		int cx = (int) Math.floor(ox);
		int cy = (int) Math.floor(oy);
		int cz = (int) Math.floor(oz);
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? cx + 1 - ox : ox - cx) * tDeltaX;
		double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? cy + 1 - oy : oy - cy) * tDeltaY;
		double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? cz + 1 - oz : oz - cz) * tDeltaZ;
		for (int i = 0; i < SHADOW_STEPS; i++) {
			if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > SHADOW_DISTANCE) {
				return false;
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				cx += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				cy += stepY;
				tMaxY += tDeltaY;
			} else {
				cz += stepZ;
				tMaxZ += tDeltaZ;
			}
			if (cy >= this.topY) {
				return false;
			}
			int id = this.reader.state(cx, cy, cz);
			if (id == VoxelReader.UNKNOWN) {
				return false;
			}
			if (this.looks.kind[id] == BlockLooks.SOLID) {
				return true;
			}
		}
		return false;
	}

	private boolean occludes(int x, int y, int z) {
		int id = this.reader.state(x, y, z);
		return id != VoxelReader.UNKNOWN && this.looks.kind[id] == BlockLooks.SOLID;
	}

	/** Smooth corner occlusion on a top face, from the eight blocks around the cell above it. */
	private float ambientOcclusionTop(int x, int y, int z, double fx, double fz) {
		boolean n = this.occludes(x, y, z - 1);
		boolean s = this.occludes(x, y, z + 1);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float nw = corner(w, n, this.occludes(x - 1, y, z - 1));
		float ne = corner(e, n, this.occludes(x + 1, y, z - 1));
		float sw = corner(w, s, this.occludes(x - 1, y, z + 1));
		float se = corner(e, s, this.occludes(x + 1, y, z + 1));
		return bilinear(nw, ne, sw, se, fx, fz);
	}

	/** Same for a south face; {@code z} is the open layer in front of it, {@code fy} runs bottom to top. */
	private float ambientOcclusionSouth(int x, int y, int z, double fx, double fy) {
		boolean up = this.occludes(x, y + 1, z);
		boolean down = this.occludes(x, y - 1, z);
		boolean w = this.occludes(x - 1, y, z);
		boolean e = this.occludes(x + 1, y, z);
		float tw = corner(w, up, this.occludes(x - 1, y + 1, z));
		float te = corner(e, up, this.occludes(x + 1, y + 1, z));
		float bw = corner(w, down, this.occludes(x - 1, y - 1, z));
		float be = corner(e, down, this.occludes(x + 1, y - 1, z));
		return bilinear(tw, te, bw, be, fx, 1 - fy);
	}

	private static float corner(boolean side1, boolean side2, boolean diagonal) {
		if (side1 && side2) {
			return AO_LEVELS[0];
		}
		return AO_LEVELS[3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (diagonal ? 1 : 0)];
	}

	/** {@code a b} on the first row, {@code c d} on the second. */
	private static float bilinear(float a, float b, float c, float d, double fx, double fy) {
		float top = (float) (a + (b - a) * fx);
		float bottom = (float) (c + (d - c) * fx);
		return (float) (top + (bottom - top) * fy);
	}

	private static float luminance(float r, float g, float b) {
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	private static int abgr(float r, float g, float b, int alpha) {
		float l = luminance(r, g, b);
		r = (l + (r - l) * SATURATION) * EXPOSURE;
		g = (l + (g - l) * SATURATION) * EXPOSURE;
		b = (l + (b - l) * SATURATION) * EXPOSURE;
		int ri = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (r * 4095)))];
		int gi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (g * 4095)))];
		int bi = LINEAR_TO_SRGB[Math.min(4095, Math.max(0, (int) (b * 4095)))];
		return (alpha << 24) | (bi << 16) | (gi << 8) | ri;
	}

	private static byte[] coverage(int[] pixels, int width, int height) {
		int cellPixels = Projection.TILE_PIXELS / Projection.COVERAGE_CELLS;
		int cellsX = width / cellPixels;
		int cellsY = height / cellPixels;
		byte[] out = new byte[cellsX * cellsY];
		for (int cy = 0; cy < cellsY; cy++) {
			for (int cx = 0; cx < cellsX; cx++) {
				int sum = 0;
				for (int y = 0; y < cellPixels; y++) {
					int row = (cy * cellPixels + y) * width + cx * cellPixels;
					for (int x = 0; x < cellPixels; x++) {
						sum += pixels[row + x] >>> 24;
					}
				}
				out[cy * cellsX + cx] = (byte) (sum / (cellPixels * cellPixels));
			}
		}
		return out;
	}
}
