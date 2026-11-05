package dev.diorama.render.gpu;

//? if >=26.3 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.diorama.render.DioramaPipelines;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
//?}
import org.jspecify.annotations.Nullable;

/**
 * The fog drawn by fog.fsh into an offscreen texture at half the screen's resolution, for the map
 * to show scaled up: the fog costs a quarter of the pixels. The texture holds the fog's own
 * colour and cover, not blended, so the map draws it like any texture. Render thread only.
 */
public final class FogLayer implements AutoCloseable {
	private @Nullable MapTexture target;
	private int size;
	//? if >=26.3 {
	private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("diorama fog layer");
	private final Projection ortho = new Projection();
	//?}

	/** Whether this Minecraft version draws the fog through a layer; where not, it's drawn directly. */
	public static boolean supported() {
		//? if >=26.3 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	/** The texture {@link #render} last drew into, {@link #size} texels on a side. */
	public @Nullable MapTexture texture() {
		return this.target;
	}

	public int size() {
		return this.size;
	}

	/**
	 * Draws the fog over the {@code width} x {@code height} texels at the layer's top left, as
	 * fog.fsh does over a view from mask point (u0, v0) to (u1, v1) with these parameters. False if
	 * it couldn't; then nothing is drawn.
	 */
	public boolean render(MapTexture mask, MapTexture noise, MapTexture detail, int width, int height, float u0, float v0, float u1, float v1, int params) {
		int need = 64;
		while (need < Math.max(width, height)) {
			need *= 2;
		}
		if (need > MAX_SIZE || !supported()) {
			return false;
		}
		if (this.target == null || this.size != need) {
			if (this.target != null) {
				this.target.close();
			}
			this.target = MapTexture.createTarget("diorama fog layer", need);
			this.size = need;
		}
		//? if >=26.3 {
		var device = RenderSystem.getDevice();
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4)) {
			BufferBuilder mesh = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
			// Counter-clockwise in the layer's y-up projection, so the quad faces the camera; its top
			// edge (v0) lands in the texture's first row, where the map reads v 0.
			mesh.addVertex(0, 0, 0).setUv(u0, v0).setColor(params);
			mesh.addVertex(width, 0, 0).setUv(u1, v0).setColor(params);
			mesh.addVertex(width, height, 0).setUv(u1, v1).setColor(params);
			mesh.addVertex(0, height, 0).setUv(u0, v1).setColor(params);
			try (MeshData data = mesh.buildOrThrow()) {
				GpuBuffer vertices = device.createBuffer(() -> "diorama fog layer quad", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
				try {
					RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
					GpuBuffer indexBuffer = indices.getBuffer(6);
					// The game's own ortho, which knows the device's depth range, as the GUI's.
					this.ortho.setupOrtho(1000, 11000, this.size, this.size, false);
					GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f().setTranslation(0, 0, -11000));
					GpuBufferSlice projectionSlice = this.projection.getBuffer(this.ortho);
					StateTexture into = (StateTexture) this.target;
					try (RenderPass pass = device.createCommandEncoder().createRenderPass(
						() -> "diorama fog layer", into.view(), Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
						pass.setPipeline(RenderSystem.getCompiledPipeline(DioramaPipelines.FOG_LAYER));
						pass.setUniform("Projection", projectionSlice);
						var globals = RenderSystem.getGlobalSettingsUniform();
						if (globals != null) {
							pass.setUniform("Globals", globals);
						}
						pass.setUniform("DynamicTransforms", transforms);
						pass.setUniform("Sampler0", ((StateTexture) mask).view(), StateTexture.samplerFor(Sampling.CLAMP_LINEAR));
						pass.setUniform("Sampler1", ((StateTexture) noise).view(), StateTexture.samplerFor(Sampling.REPEAT_LINEAR));
						pass.setUniform("Sampler2", ((StateTexture) detail).view(), StateTexture.samplerFor(Sampling.CLAMP_LINEAR));
						pass.setVertexBuffer(0, vertices.slice());
						pass.setIndexBuffer(indexBuffer, indices.type());
						pass.drawIndexed(6, 1, 0, 0, 0);
					}
				} finally {
					vertices.close();
				}
			}
		}
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	/** The largest layer: a 4K screen's half. */
	private static final int MAX_SIZE = 2048;

	@Override
	public void close() {
		if (this.target != null) {
			this.target.close();
			this.target = null;
		}
		//? if >=26.3 {
		this.projection.close();
		//?}
	}
}
