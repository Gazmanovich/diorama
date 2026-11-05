package dev.diorama.render.gpu;

//? if >=1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import dev.diorama.render.DioramaPipelines;
import org.joml.Matrix4f;
//?}
//? if >=26.2 {
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.Optional;
import org.joml.Vector4f;
//?} else if >=1.21.6 {
/*import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalInt;
import org.joml.Vector3f;
import org.joml.Vector4f;
*///?}
//? if >=26.3 {
import java.util.OptionalDouble;
//?}
import org.jspecify.annotations.Nullable;

/**
 * The fog drawn by fog.fsh into an offscreen texture at half the screen's resolution, for the map
 * to show scaled up: the fog costs a quarter of the pixels. The texture holds the fog's own
 * colour and cover, written rather than blended, so the map draws it like any texture. From
 * 1.21.6 the GUI draws later from render state, so this draws straight away into its own pass;
 * before, the canvas draws it in between its other draws. Render thread only.
 */
public final class FogLayer implements AutoCloseable {
	private @Nullable MapTexture target;
	private int size;
	//? if >=26.1 {
	private final net.minecraft.client.renderer.ProjectionMatrixBuffer projection = new net.minecraft.client.renderer.ProjectionMatrixBuffer("diorama fog layer");
	private final net.minecraft.client.renderer.Projection ortho = new net.minecraft.client.renderer.Projection();
	//?} else if >=1.21.6 {
	/*private final net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer projection =
		new net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer("diorama fog layer", 1000, 11000, false);
	*///?}

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
	 * it couldn't; then the caller draws the fog directly.
	 */
	public boolean render(MapCanvas canvas, MapTexture mask, MapTexture noise, MapTexture detail, int width, int height, float u0, float v0, float u1, float v1, int params) {
		int need = 64;
		while (need < Math.max(width, height)) {
			need *= 2;
		}
		if (need > MAX_SIZE) {
			return false;
		}
		if (this.target == null || this.size != need) {
			if (this.target != null) {
				this.target.close();
			}
			this.target = MapTexture.createTarget("diorama fog layer", need);
			this.size = need;
		}
		//? if >=1.21.6 {
		var device = RenderSystem.getDevice();
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4)) {
		//? if >=26.2 {
		BufferBuilder mesh = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		//?} else {
		/*BufferBuilder mesh = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		*///?}
		quad(mesh, width, height, u0, v0, u1, v1, params);
		try (MeshData data = mesh.buildOrThrow()) {
			GpuBuffer vertices = device.createBuffer(() -> "diorama fog layer quad", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
			try {
				//? if >=26.2 {
				RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
				//?} else {
				/*RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
				*///?}
				GpuBuffer indexBuffer = indices.getBuffer(6);
				// The GUI's own ortho, y up: the quad's top edge (v0) lands in the texture's first
				// row, where the map reads v 0.
				//? if >=26.1 {
				this.ortho.setupOrtho(1000, 11000, this.size, this.size, false);
				GpuBufferSlice projectionSlice = this.projection.getBuffer(this.ortho);
				//?} else {
				/*GpuBufferSlice projectionSlice = this.projection.getBuffer(this.size, this.size);
				*///?}
				Matrix4f modelView = new Matrix4f().setTranslation(0, 0, -11000);
				//? if >=26.2 {
				GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView);
				//?} else if >=1.21.11 {
				/*GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
				*///?} else {
				/*GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f(), 0);
				*///?}
				StateTexture into = (StateTexture) this.target;
				//? if >=26.3 {
				try (RenderPass pass = device.createCommandEncoder().createRenderPass(
					() -> "diorama fog layer", into.view(), Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(DioramaPipelines.FOG_LAYER));
				//?} else if >=26.2 {
				/*try (RenderPass pass = device.createCommandEncoder().createRenderPass(
					com.mojang.renderpearl.api.commands.RenderPassDescriptor.create(() -> "diorama fog layer")
						.withColorAttachment(into.view(), Optional.of(new Vector4f(0, 0, 0, 0)))
						.withRenderArea(new RenderPass.RenderArea(0, 0, this.size, this.size)))) {
					pass.setPipeline(DioramaPipelines.FOG_LAYER);
				*///?} else {
				/*try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "diorama fog layer", into.view(), OptionalInt.of(0))) {
					pass.setPipeline(DioramaPipelines.FOG_LAYER);
				*///?}
					pass.setUniform("Projection", projectionSlice);
					var globals = RenderSystem.getGlobalSettingsUniform();
					if (globals != null) {
						pass.setUniform("Globals", globals);
					}
					pass.setUniform("DynamicTransforms", transforms);
					bind(pass, "Sampler0", (StateTexture) mask, Sampling.CLAMP_LINEAR);
					bind(pass, "Sampler1", (StateTexture) noise, Sampling.REPEAT_LINEAR);
					bind(pass, "Sampler2", (StateTexture) detail, Sampling.CLAMP_LINEAR);
					//? if >=26.2 {
					pass.setVertexBuffer(0, vertices.slice());
					pass.setIndexBuffer(indexBuffer, indices.type());
					pass.drawIndexed(6, 1, 0, 0, 0);
					//?} else {
					/*pass.setVertexBuffer(0, vertices);
					pass.setIndexBuffer(indexBuffer, indices.type());
					pass.drawIndexed(0, 0, 6, 1);
					*///?}
				}
			} finally {
				vertices.close();
			}
		}
		}
		return true;
		//?} else {
		/*return ((ImmediateCanvas) canvas).fogLayer(this.target, this.size, mask, noise, detail, width, height, u0, v0, u1, v1, params);
		*///?}
	}

	//? if >=1.21.6 {
	/** The quad over the layer's texels, counter-clockwise in its y-up projection so it faces the camera. */
	private static void quad(BufferBuilder mesh, int width, int height, float u0, float v0, float u1, float v1, int params) {
		mesh.addVertex(0, 0, 0).setUv(u0, v0).setColor(params);
		mesh.addVertex(width, 0, 0).setUv(u1, v0).setColor(params);
		mesh.addVertex(width, height, 0).setUv(u1, v1).setColor(params);
		mesh.addVertex(0, height, 0).setUv(u0, v1).setColor(params);
	}

	private static void bind(RenderPass pass, String name, StateTexture texture, Sampling sampling) {
		//? if >=26.3 {
		pass.setUniform(name, texture.view(), StateTexture.samplerFor(sampling));
		//?} else if >=1.21.11 {
		/*pass.bindTexture(name, texture.view(), StateTexture.samplerFor(sampling));
		*///?} else {
		/*texture.sampleWith(sampling);
		pass.bindSampler(name, texture.view());
		*///?}
	}
	//?}

	/** The largest layer: a 4K screen's half. */
	private static final int MAX_SIZE = 2048;

	@Override
	public void close() {
		if (this.target != null) {
			this.target.close();
			this.target = null;
		}
		//? if >=1.21.6 {
		this.projection.close();
		//?}
	}
}
