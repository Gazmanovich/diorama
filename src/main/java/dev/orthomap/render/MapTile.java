package dev.orthomap.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.orthomap.Projection;
import dev.orthomap.bake.Mipmaps;
import dev.orthomap.bake.TileImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** A baked tile on the GPU, plus the coverage the fog reads. Render thread only. */
public final class MapTile {
	private @Nullable GpuTexture texture;
	private @Nullable GpuTextureView view;
	private byte @Nullable [] coverage;
	private boolean empty = true;
	long lastDrawn;

	void upload(TileImage image) {
		if (image.isFull()) {
			this.coverage = image.coverage();
			this.empty = isEmpty(this.coverage);
			if (this.empty) {
				this.close();
				return;
			}
			this.ensureTexture(image);
			int size = Projection.TILE_PIXELS;
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				this.write(image.mips()[level], level, 0, 0, size >> level);
			}
			return;
		}
		if (this.coverage == null) {
			return;
		}
		int cellsPerBlock = TileImage.BLOCK / (Projection.TILE_PIXELS / Projection.COVERAGE_CELLS);
		for (TileImage.Patch patch : image.patches()) {
			for (int y = 0; y < cellsPerBlock; y++) {
				System.arraycopy(
					patch.coverage(), y * cellsPerBlock,
					this.coverage, (patch.blockY() * cellsPerBlock + y) * Projection.COVERAGE_CELLS + patch.blockX() * cellsPerBlock,
					cellsPerBlock
				);
			}
		}
		this.empty = isEmpty(this.coverage);
		if (this.empty) {
			this.close();
			return;
		}
		if (this.ensureTexture(image)) {
			// A new texture over a previously empty tile starts blank everywhere outside the patches.
			int size = Projection.TILE_PIXELS;
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				this.write(new int[(size >> level) * (size >> level)], level, 0, 0, size >> level);
			}
		}
		for (TileImage.Patch patch : image.patches()) {
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				int edge = TileImage.BLOCK >> level;
				this.write(patch.mips()[level], level, patch.blockX() * edge, patch.blockY() * edge, edge);
			}
		}
	}

	/** Creates the GPU texture if needed; true if it was just created. */
	private boolean ensureTexture(TileImage image) {
		if (this.texture != null) {
			return false;
		}
		GpuDevice device = RenderSystem.getDevice();
		int size = Projection.TILE_PIXELS;
		this.texture = device.createTexture(
			"orthomap tile " + image.key(),
			GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
			GpuFormat.RGBA8_UNORM,
			size,
			size,
			1,
			Mipmaps.LEVELS
		);
		this.view = device.createTextureView(this.texture);
		return true;
	}

	private void write(int[] pixels, int level, int x, int y, int edge) {
		ByteBuffer buffer = MemoryUtil.memAlloc(edge * edge * 4).order(ByteOrder.LITTLE_ENDIAN);
		try {
			buffer.asIntBuffer().put(pixels, 0, edge * edge);
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, buffer, level, 0, x, y, edge, edge);
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	public @Nullable GpuTextureView view() {
		return this.view;
	}

	public boolean isEmpty() {
		return this.empty;
	}

	/** Known share of the coverage cell, 0..1. */
	public float coverage(int cellX, int cellY) {
		byte[] c = this.coverage;
		if (c == null) {
			return 0;
		}
		return (c[cellY * Projection.COVERAGE_CELLS + cellX] & 0xFF) / 255.0F;
	}

	public boolean hasCoverage() {
		return this.coverage != null;
	}

	void close() {
		if (this.view != null) {
			this.view.close();
			this.view = null;
		}
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
	}

	private static boolean isEmpty(byte[] coverage) {
		for (byte b : coverage) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}
}
