//? if >=1.21.6 {
package dev.diorama.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
//? if >=26.2 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
//?} else if >=1.21.11 {
/*import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
*///?} else {
/*import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
*///?}
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.gui.render.TextureSetup;
import org.lwjgl.system.MemoryUtil;

// A MapTexture as a GpuTexture and a view of it, drawn through GUI render state (1.21.6 on).
final class StateTexture implements MapTexture {
	private final GpuTexture texture;
	private final GpuTextureView view;

	StateTexture(String label, int size, int levels) {
		GpuDevice device = RenderSystem.getDevice();
		//? if >=26.2 {
		this.texture = device.createTexture(
			label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, levels
		);
		//?} else {
		/*this.texture = device.createTexture(
			label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, size, size, 1, levels
		);
		*///?}
		this.view = device.createTextureView(this.texture);
	}

	@Override
	public void write(int level, int x, int y, int edge, int[] abgr) {
		ByteBuffer buffer = MemoryUtil.memAlloc(edge * edge * 4).order(ByteOrder.LITTLE_ENDIAN);
		try {
			buffer.asIntBuffer().put(abgr, 0, edge * edge);
			//? if >=26.2 {
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, buffer, level, 0, x, y, edge, edge);
			//?} else if >=1.21.9 {
			/*RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, buffer, NativeImage.Format.RGBA, level, 0, x, y, edge, edge);
			*///?} else {
			/*RenderSystem.getDevice().createCommandEncoder().writeToTexture(this.texture, buffer.asIntBuffer(), NativeImage.Format.RGBA, level, 0, x, y, edge, edge);
			*///?}
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	// The texture as the only one of a draw, sampled the given way.
	TextureSetup single(Sampling sampling) {
		//? if >=1.21.11 {
		return TextureSetup.singleTexture(this.view, sampler(sampling));
		//?} else {
		/*this.sampleWith(sampling);
		return TextureSetup.singleTexture(this.view);
		*///?}
	}

	// Two textures for one draw, each sampled its own way.
	static TextureSetup pair(StateTexture first, Sampling firstSampling, StateTexture second, Sampling secondSampling) {
		//? if >=1.21.11 {
		return TextureSetup.doubleTexture(first.view, sampler(firstSampling), second.view, sampler(secondSampling));
		//?} else {
		/*first.sampleWith(firstSampling);
		second.sampleWith(secondSampling);
		return TextureSetup.doubleTexture(first.view, second.view);
		*///?}
	}

	//? if >=1.21.11 {
	private static GpuSampler sampler(Sampling sampling) {
		return switch (sampling) {
			case TILE_SMOOTH, TILE_CRISP -> RenderSystem.getSamplerCache().getSampler(
				AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, sampling.crisp() ? FilterMode.NEAREST : FilterMode.LINEAR, true
			);
			case CLAMP_LINEAR -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			case CLAMP_CRISP -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			case REPEAT_LINEAR -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
		};
	}
	//?} else {
	/*// Before 1.21.11 a texture carried its own sampling state instead of each draw choosing a
	// sampler. Render state draws at the end of the frame, so the last setting in a frame wins;
	// each texture is only ever drawn one way per frame.
	private void sampleWith(Sampling sampling) {
		this.texture.setAddressMode(sampling.repeats() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE);
		this.texture.setTextureFilter(FilterMode.LINEAR, sampling.crisp() ? FilterMode.NEAREST : FilterMode.LINEAR, sampling.mipmapped());
	}
	*///?}

	@Override
	public void close() {
		this.view.close();
		this.texture.close();
	}
}
//?}
