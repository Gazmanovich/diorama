//? if >=1.21.5 && <1.21.6 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

// A MapTexture on 1.21.5: a GpuTexture, which carries its own sampling state and has no views yet.
final class BlazeTexture implements LegacyTexture {
	private final GpuTexture texture;

	BlazeTexture(String label, int size, int levels) {
		this.texture = RenderSystem.getDevice().createTexture(label, TextureFormat.RGBA8, size, size, levels);
	}

	@Override
	public void write(int level, int x, int y, int edge, int[] abgr) {
		ByteBuffer buffer = MemoryUtil.memAlloc(edge * edge * 4).order(ByteOrder.LITTLE_ENDIAN);
		try {
			buffer.asIntBuffer().put(abgr, 0, edge * edge);
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, buffer.asIntBuffer(), NativeImage.Format.RGBA, level, x, y, edge, edge);
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	@Override
	public void bind(int slot, Sampling sampling) {
		this.texture.setAddressMode(sampling.repeats() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE);
		this.texture.setTextureFilter(FilterMode.LINEAR, sampling.crisp() ? FilterMode.NEAREST : FilterMode.LINEAR, sampling.mipmapped());
		RenderSystem.setShaderTexture(slot, this.texture);
	}

	@Override
	public void close() {
		this.texture.close();
	}
}
*///?}
