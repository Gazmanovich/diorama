//? if >=26.3 {
package dev.diorama.render.gpu;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.GpuMesher;
import dev.diorama.bake.TileBaker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws surface tiles on the GPU from {@link GpuMesher} meshes instead of tracing them: an
 * experiment, on with {@code DIORAMA_GPU_BAKE=1}.
 *
 * <p>A bake is five passes on the render thread: a shadow map from the sun; the solid faces into a
 * colour and depth target twice the tile's size, so each pixel gets four samples like the
 * tracer's; plants and glass blended over them; the water's surface into its own target; and a
 * pass that mixes water by depth, averages the samples, draws outlines and grades the colour into
 * the tile's pixels. Those are copied back to the CPU, which takes a frame or two, and handed to
 * the caller as the same ABGR ints a traced bake makes.
 */
public final class GpuTileBaker {
	public static final boolean ENABLED = "1".equals(System.getenv("DIORAMA_GPU_BAKE"));
	/**
	 * The share of frame time bakes may take, on the render thread and on the GPU each. Bakes are
	 * paid for out of a budget that grows by this share of every frame's length, at what bakes
	 * have been measured to cost, so a backlog spreads over frames instead of landing on a few.
	 */
	private static final double SHARE = Math.max(0.01, Math.min(1, Double.parseDouble(System.getProperty("diorama.gpuBakeShare", "0.1"))));
	/** The tile plus a pixel all round, which outlines compare against, at two samples each way. */
	private static final int SCENE = (Projection.TILE_PIXELS + 2) * 2;
	private static final int SHADOW_SIZE = 2048;
	private static final int FACE_BYTES = 20;
	private static final int CASTER_BYTES = 8;

	private static final VertexFormat CORNER = VertexFormat.builder(0)
		.addAttribute("Corner", GpuFormat.RGBA8_UNORM)
		.build();
	private static final VertexFormat FACE = VertexFormat.builder(1)
		.addAttribute("Cell", GpuFormat.RGBA16_SINT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("Occlusion", GpuFormat.RGBA8_UNORM)
		.addAttribute("FaceLight", GpuFormat.RGBA8_UNORM)
		.build();
	private static final VertexFormat CASTER = VertexFormat.builder(1)
		.addAttribute("Cell", GpuFormat.RGBA16_SINT)
		.build();

	private static final BindGroupLayout BAKE = BindGroupLayout.builder()
		.withUniform("DioramaBake", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout SHADOW_MAP = BindGroupLayout.builder()
		.withUniform("ShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	private static final BindGroupLayout VOXELS = BindGroupLayout.builder()
		.withUniform("Voxels", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
		.build();
	private static final BindGroupLayout SCENE_SAMPLERS = BindGroupLayout.builder()
		.withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("WaterColor", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("WaterDepth", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("SceneInfo", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	private static final ColorTargetState HDR = new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
	/** The overlay bits a sample shows; plants and glass leave them as they are. */
	private static final ColorTargetState INFO = new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL);
	private static final ColorTargetState INFO_KEPT = new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_NONE);

	// Not registered: compiled the first time they're used, so a broken shader only stops the experiment.
	private static final RenderPipeline SHADOW = faces("bake_shadow", "SHADOW", CASTER)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	private static final RenderPipeline OPAQUE = faces("bake_opaque", null, FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(VOXELS)
		.withColorTargetState(0, HDR)
		.withColorTargetState(1, INFO)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	// Colour blends by the face's weight; alpha, which says the pixel is known, stays.
	private static final RenderPipeline TINTED = faces("bake_tinted", "TINTED", FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(VOXELS)
		.withColorTargetState(0, new ColorTargetState(Optional.of(BlendFunction.ENTITY_OUTLINE_BLIT), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
		.withColorTargetState(1, INFO_KEPT)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build();
	private static final RenderPipeline WATER = faces("bake_water", "WATER", FACE)
		.withBindGroupLayout(SHADOW_MAP)
		.withBindGroupLayout(VOXELS)
		.withColorTargetState(HDR)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.build();
	private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
		.withLocation(Diorama.id("pipeline/bake_composite"))
		.withVertexShader(Diorama.id("core/bake_composite"))
		.withFragmentShader(Diorama.id("core/bake_composite"))
		.withBindGroupLayout(BAKE)
		.withBindGroupLayout(SCENE_SAMPLERS)
		.withColorTargetState(0, ColorTargetState.DEFAULT)
		.withColorTargetState(1, INFO)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	private static RenderPipeline.Builder faces(String name, @Nullable String define, VertexFormat instances) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Diorama.id("pipeline/" + name))
			.withVertexShader(Diorama.id("core/bake"))
			.withFragmentShader(Diorama.id("core/bake"))
			.withBindGroupLayout(BAKE)
			.withVertexBinding(0, CORNER)
			.withVertexBinding(1, instances)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false);
		if (define != null) {
			builder.withShaderDefine(define);
		}
		return builder;
	}

	private static final int UBO_SIZE = uboSize();

	private static int uboSize() {
		Std140SizeCalculator size = new Std140SizeCalculator();
		// See include/bake.glsl: eleven vec4s, the four jitters, the mode, two ivec4s, the 13 sky rays.
		for (int i = 0; i < 11 + 4 + 1 + 2 + 13; i++) {
			size.putVec4();
		}
		return size.get();
	}

	private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();
	/** Jobs drawn and waiting for their pixels to come back. */
	private static final java.util.Set<Job> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static @Nullable Targets targets;
	private static long timestampSlot;
	// The bake budget, in nanoseconds of frame time, and the measured cost of a bake.
	private static long lastFrame;
	private static double budget;
	private static double renderCost = 1e6;
	private static double gpuCost = 1e6;

	private GpuTileBaker() {
	}

	/** What a bake cost, for the experiment's numbers. Times are nanoseconds, -1 where not known. */
	public record Timing(long meshNanos, long packNanos, long recordNanos, long gpuNanos, long latencyNanos, int faces, int casters, int bytes) {
	}

	/** {@code info} holds the {@code Overlays} bits per pixel when the mesh asked for them. */
	public record Result(int @Nullable [] pixels, byte @Nullable [] info, Timing timing) {
	}

	/**
	 * Packs a mesh for the GPU and queues it; any thread. {@code done} gets the pixels on the
	 * render thread, or null pixels if the GPU couldn't draw it.
	 */
	public static void submit(GpuMesher.Mesh mesh, Consumer<Result> done) {
		QUEUE.add(new Job(mesh, done));
	}

	/**
	 * Fails every job not finished yet, queued or drawn, so their callers fall back at once. For
	 * waits on the render thread, which the GPU's results need to arrive. A result that still
	 * arrives later is dropped.
	 */
	public static void cancelAll() {
		Job job;
		while ((job = QUEUE.poll()) != null) {
			job.fail();
		}
		for (Job pending : PENDING) {
			pending.fail();
		}
	}

	/** Draws queued tiles. Call on the render thread at the start of a frame, outside any pass. */
	public static void frame() {
		long now = System.nanoTime();
		double cost = Math.max(renderCost, gpuCost);
		if (lastFrame != 0) {
			// Never more than a few bakes' worth saved up, so a long pause doesn't buy a burst.
			budget = Math.min(budget + SHARE * (now - lastFrame), 3 * cost);
		}
		lastFrame = now;
		while (budget >= cost) {
			Job job = QUEUE.poll();
			if (job == null) {
				return;
			}
			budget -= cost;
			try {
				if (job.mesh.empty) {
					// Rays start below the view's limit: the tracer leaves every pixel unknown.
					job.done(new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS], job.mesh.overlays == null ? null : new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS], 0, 0);
				} else if (!draw(job)) {
					job.fail();
				}
			} catch (Throwable t) {
				Diorama.LOGGER.error("GPU bake of {} failed", job.mesh.key, t);
				job.fail();
			}
		}
	}

	private static boolean draw(Job job) {
		long start = System.nanoTime();
		CompiledRenderPipeline shadow = RenderSystem.getCompiledPipelineNullable(SHADOW);
		CompiledRenderPipeline opaque = RenderSystem.getCompiledPipelineNullable(OPAQUE);
		CompiledRenderPipeline tinted = RenderSystem.getCompiledPipelineNullable(TINTED);
		CompiledRenderPipeline water = RenderSystem.getCompiledPipelineNullable(WATER);
		CompiledRenderPipeline composite = RenderSystem.getCompiledPipelineNullable(COMPOSITE);
		if (shadow == null || opaque == null || tinted == null || water == null || composite == null) {
			return false;
		}
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		Targets t = targets();
		int slot = (int) (timestampSlot++ % (Targets.TIMESTAMPS / 2)) * 2;
		encoder.writeTimestamp(t.timestamps, slot);
		encoder.writeToBuffer(t.ubo.slice(), job.uniforms);

		GpuBuffer faceBuffer = null;
		GpuBuffer casterBuffer = null;
		GpuBuffer voxelBuffer = null;
		try {
			voxelBuffer = job.voxels == null ? null : device.createBuffer(() -> "Diorama bake voxels", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, job.voxels);
			GpuBufferSlice voxels = (voxelBuffer != null ? voxelBuffer : t.noVoxels).slice();
			faceBuffer = job.faceCount() == 0 ? null : device.createBuffer(() -> "Diorama bake faces", GpuBuffer.USAGE_VERTEX, job.faces);
			casterBuffer = job.casterCount == 0 ? null : device.createBuffer(() -> "Diorama bake casters", GpuBuffer.USAGE_VERTEX, job.casters);
			RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			GpuBuffer indices = quads.getBuffer(6);
			IndexType indexType = quads.type();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

			try (RenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Diorama bake shadow")
				.withDepthAttachment(t.shadowView, OptionalDouble.of(0.0))
				.build())) {
				if (casterBuffer != null) {
					pass.setPipeline(shadow);
					pass.setUniform("DioramaBake", t.ubo);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setVertexBuffer(1, casterBuffer.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.drawIndexed(6, job.casterCount, 0, 0, 0);
				}
			}
			try (RenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Diorama bake scene")
				.withColorAttachment(t.sceneColorView, Optional.of(VOID))
				.withColorAttachment(t.sceneInfoView, Optional.of(CLEAR))
				.withDepthAttachment(t.sceneDepthView, OptionalDouble.of(0.0))
				.build())) {
				if (faceBuffer != null && job.opaqueCount + job.tintedCount > 0) {
					pass.setPipeline(opaque);
					pass.setUniform("DioramaBake", t.ubo);
					pass.setUniform("ShadowMap", t.shadowView, nearest);
				pass.setUniform("Voxels", voxels);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setIndexBuffer(indices, indexType);
					if (job.opaqueCount > 0) {
						pass.setVertexBuffer(1, faceBuffer.slice(0, (long) job.opaqueCount * FACE_BYTES));
						pass.drawIndexed(6, job.opaqueCount, 0, 0, 0);
					}
					if (job.tintedCount > 0) {
						pass.setPipeline(tinted);
						pass.setVertexBuffer(1, faceBuffer.slice((long) job.opaqueCount * FACE_BYTES, (long) job.tintedCount * FACE_BYTES));
						pass.drawIndexed(6, job.tintedCount, 0, 0, 0);
					}
				}
			}
			try (RenderPass pass = encoder.createRenderPass(
				() -> "Diorama bake water", t.waterColorView, Optional.of(CLEAR), t.waterDepthView, OptionalDouble.of(0.0)
			)) {
				if (faceBuffer != null && job.waterCount > 0) {
					pass.setPipeline(water);
					pass.setUniform("DioramaBake", t.ubo);
					pass.setUniform("ShadowMap", t.shadowView, nearest);
				pass.setUniform("Voxels", voxels);
					pass.setVertexBuffer(0, t.corners.slice());
					pass.setIndexBuffer(indices, indexType);
					pass.setVertexBuffer(1, faceBuffer.slice((long) (job.opaqueCount + job.tintedCount) * FACE_BYTES, (long) job.waterCount * FACE_BYTES));
					pass.drawIndexed(6, job.waterCount, 0, 0, 0);
				}
			}
			try (RenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "Diorama bake composite")
			.withColorAttachment(t.outputView, Optional.empty())
			.withColorAttachment(t.outputInfoView, Optional.empty())
			.build())) {
				pass.setPipeline(composite);
				pass.setUniform("DioramaBake", t.ubo);
				pass.setUniform("SceneColor", t.sceneColorView, nearest);
				pass.setUniform("SceneDepth", t.sceneDepthView, nearest);
				pass.setUniform("WaterColor", t.waterColorView, nearest);
				pass.setUniform("WaterDepth", t.waterDepthView, nearest);
			pass.setUniform("SceneInfo", t.sceneInfoView, nearest);
				pass.draw(3, 1, 0, 0);
			}
			encoder.writeTimestamp(t.timestamps, slot + 1);

			int size = Projection.TILE_PIXELS;
			GpuBuffer readback = device.createBuffer(() -> "Diorama bake readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) size * size * 4);
		boolean wantsInfo = job.mesh.overlays != null;
		GpuBuffer infoReadback = wantsInfo ? device.createBuffer(() -> "Diorama bake info readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) size * size) : null;
		// The colour finishes the job; the overlay bits, read back first, ride along.
		byte[][] info = new byte[1][];
			GpuQueryPool timestamps = t.timestamps;
			long[] recorded = new long[1];
			PENDING.add(job);
			if (infoReadback != null) {
				encoder.copyTextureToBuffer(t.outputInfo, infoReadback, 0L, () -> {
					try (GpuBufferSlice.MappedView view = infoReadback.map(true, false)) {
						byte[] bits = new byte[size * size];
						view.data().get(bits);
						info[0] = bits;
					} catch (Throwable e) {
						Diorama.LOGGER.error("Reading back GPU tile overlays {} failed", job.mesh.key, e);
					} finally {
						infoReadback.close();
					}
				}, 0);
			}
			encoder.copyTextureToBuffer(t.output, readback, 0L, () -> {
				try {
					int[] pixels = new int[size * size];
					try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
						view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
					}
					job.done(pixels, info[0], recorded[0], gpuNanos(timestamps, slot));
				} catch (Throwable e) {
					Diorama.LOGGER.error("Reading back GPU tile {} failed", job.mesh.key, e);
					job.fail();
				} finally {
					readback.close();
				}
			}, 0);
			recorded[0] = System.nanoTime() - start;
			renderCost = renderCost * 0.9 + recorded[0] * 0.1;
		} finally {
			if (faceBuffer != null) {
				faceBuffer.close();
			}
			if (casterBuffer != null) {
				casterBuffer.close();
			}
			if (voxelBuffer != null) {
				voxelBuffer.close();
			}
			job.free();
		}
		return true;
	}

	private static long gpuNanos(GpuQueryPool pool, int slot) {
		OptionalLong[] values = pool.getValues(slot, 2);
		if (values[0].isEmpty() || values[1].isEmpty()) {
			return -1;
		}
		return (long) ((values[1].getAsLong() - values[0].getAsLong()) * (double) RenderSystem.getDevice().getDeviceInfo().timestampPeriod());
	}

	// What a ray that never meets a block shows: the tracer's near-black floor.
	private static final Vector4f VOID = new Vector4f(0.01F, 0.01F, 0.015F, 1.0F);
	private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 0);

	private static Targets targets() {
		if (targets == null) {
			targets = new Targets();
		}
		return targets;
	}

	/** The textures and buffers every bake reuses. Bakes run one after another in stream order, so one set does. */
	private static final class Targets {
		static final int TIMESTAMPS = 128;
		final GpuTexture shadow;
		final GpuTextureView shadowView;
		final GpuTexture sceneColor;
		final GpuTextureView sceneColorView;
		final GpuTexture sceneInfo;
		final GpuTextureView sceneInfoView;
		final GpuTexture outputInfo;
		final GpuTextureView outputInfoView;
		final GpuTexture sceneDepth;
		final GpuTextureView sceneDepthView;
		final GpuTexture waterColor;
		final GpuTextureView waterColorView;
		final GpuTexture waterDepth;
		final GpuTextureView waterDepthView;
		final GpuTexture output;
		final GpuTextureView outputView;
		final GpuBuffer corners;
		final GpuBuffer ubo;
		/** Bound in place of the voxels in views that don't use them: a table of one empty section. */
		final GpuBuffer noVoxels;
		final GpuQueryPool timestamps;

		Targets() {
			GpuDevice device = RenderSystem.getDevice();
			int sampled = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
			this.shadow = device.createTexture("Diorama bake shadow", sampled, GpuFormat.D32_FLOAT, SHADOW_SIZE, SHADOW_SIZE, 1, 1);
			this.shadowView = device.createTextureView(this.shadow);
			this.sceneColor = device.createTexture("Diorama bake scene", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.sceneColorView = device.createTextureView(this.sceneColor);
			this.sceneInfo = device.createTexture("Diorama bake scene overlays", sampled, GpuFormat.R8_UNORM, SCENE, SCENE, 1, 1);
			this.sceneInfoView = device.createTextureView(this.sceneInfo);
			this.outputInfo = device.createTexture(
				"Diorama bake output overlays", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, GpuFormat.R8_UNORM,
				Projection.TILE_PIXELS, Projection.TILE_PIXELS, 1, 1
			);
			this.outputInfoView = device.createTextureView(this.outputInfo);
			this.sceneDepth = device.createTexture("Diorama bake scene depth", sampled, GpuFormat.D32_FLOAT, SCENE, SCENE, 1, 1);
			this.sceneDepthView = device.createTextureView(this.sceneDepth);
			this.waterColor = device.createTexture("Diorama bake water", sampled, GpuFormat.RGBA16_FLOAT, SCENE, SCENE, 1, 1);
			this.waterColorView = device.createTextureView(this.waterColor);
			this.waterDepth = device.createTexture("Diorama bake water depth", sampled, GpuFormat.D32_FLOAT, SCENE, SCENE, 1, 1);
			this.waterDepthView = device.createTextureView(this.waterDepth);
			int size = Projection.TILE_PIXELS;
			this.output = device.createTexture("Diorama bake output", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
			this.outputView = device.createTextureView(this.output);
			ByteBuffer corners = MemoryUtil.memAlloc(16);
			try {
				// (0,0), (1,0), (1,1), (0,1): round the quad, as the shared quad indices expect.
				corners.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
				corners.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 0);
				corners.put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 0);
				corners.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 0);
				corners.flip();
				this.corners = device.createBuffer(() -> "Diorama bake corners", GpuBuffer.USAGE_VERTEX, corners);
			} finally {
				MemoryUtil.memFree(corners);
			}
			this.ubo = device.createBuffer(() -> "Diorama bake UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
			ByteBuffer none = MemoryUtil.memAlloc(16).order(ByteOrder.LITTLE_ENDIAN);
			try {
				none.putInt(GpuMesher.SECTION_EMPTY).putInt(0).putInt(0).putInt(0).flip();
				this.noVoxels = device.createBuffer(() -> "Diorama bake no voxels", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, none);
			} finally {
				MemoryUtil.memFree(none);
			}
			this.timestamps = device.createTimestampQueryPool(TIMESTAMPS);
		}
	}

	private static final AtomicLong STAT_COUNT = new AtomicLong();
	private static final AtomicLong STAT_MESH = new AtomicLong();
	private static final AtomicLong STAT_PACK = new AtomicLong();
	private static final AtomicLong STAT_RECORD = new AtomicLong();
	private static final AtomicLong STAT_GPU = new AtomicLong();
	private static final AtomicLong STAT_LATENCY = new AtomicLong();

	private static void log(Timing timing) {
		long n = STAT_COUNT.incrementAndGet();
		STAT_MESH.addAndGet(timing.meshNanos());
		STAT_PACK.addAndGet(timing.packNanos());
		STAT_RECORD.addAndGet(timing.recordNanos());
		STAT_GPU.addAndGet(Math.max(0, timing.gpuNanos()));
		STAT_LATENCY.addAndGet(timing.latencyNanos());
		if (n % 25 == 0) {
			Diorama.LOGGER.info(
				"GPU baked {} tiles; average ms: mesh {}, pack {}, render thread {}, GPU {}, submit to pixels {}",
				n, ms(STAT_MESH, n), ms(STAT_PACK, n), ms(STAT_RECORD, n), ms(STAT_GPU, n), ms(STAT_LATENCY, n)
			);
		}
	}

	private static String ms(AtomicLong total, long n) {
		return String.format(java.util.Locale.ROOT, "%.2f", total.get() / (double) n / 1e6);
	}

	/** One tile on its way: its packed faces and uniforms, made on the thread that submitted it. */
	private static final class Job {
		final GpuMesher.Mesh mesh;
		final Consumer<Result> callback;
		final long submitted = System.nanoTime();
		/** Set by the first of done or fail; the other is then ignored. */
		final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
		final long packNanos;
		final int opaqueCount;
		final int tintedCount;
		final int waterCount;
		final int casterCount;
		@Nullable ByteBuffer faces;
		@Nullable ByteBuffer casters;
		@Nullable ByteBuffer uniforms;
		@Nullable ByteBuffer voxels;

		Job(GpuMesher.Mesh mesh, Consumer<Result> callback) {
			this.mesh = mesh;
			this.callback = callback;
			long start = System.nanoTime();
			this.opaqueCount = mesh.opaque.count;
			this.tintedCount = mesh.tinted.count;
			this.waterCount = mesh.water.count;
			this.casterCount = mesh.casters.count;
			int originX = mesh.originX;
			int originZ = mesh.originZ;
			ByteBuffer faces = MemoryUtil.memAlloc(Math.max(1, this.faceCount() * FACE_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
			putFaces(faces, mesh.opaque, originX, originZ);
			putFaces(faces, mesh.tinted, originX, originZ);
			putFaces(faces, mesh.water, originX, originZ);
			faces.flip();
			this.faces = faces;
			ByteBuffer casters = MemoryUtil.memAlloc(Math.max(1, this.casterCount * CASTER_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
			int[] d = mesh.casters.data;
			for (int q = 0; q < this.casterCount; q++) {
				int i = q * 8;
				casters.putShort((short) (d[i] - originX)).putShort((short) d[i + 1]).putShort((short) (d[i + 2] - originZ)).putShort((short) d[i + 3]);
			}
			casters.flip();
			this.casters = casters;
			this.uniforms = uniforms(mesh, originX, originZ);
			if (mesh.voxels != null) {
				ByteBuffer voxels = MemoryUtil.memAlloc(mesh.voxelCount * 4).order(ByteOrder.LITTLE_ENDIAN);
				voxels.asIntBuffer().put(mesh.voxels, 0, mesh.voxelCount);
				this.voxels = voxels;
			}
			this.packNanos = System.nanoTime() - start;
		}

		int faceCount() {
			return this.opaqueCount + this.tintedCount + this.waterCount;
		}

		private static void putFaces(ByteBuffer out, GpuMesher.Quads quads, int originX, int originZ) {
			int[] d = quads.data;
			for (int q = 0; q < quads.count; q++) {
				int i = q * 8;
				out.putShort((short) (d[i] - originX)).putShort((short) d[i + 1]).putShort((short) (d[i + 2] - originZ)).putShort((short) (d[i + 3] | d[i + 7] << 4));
				int color = d[i + 4];
				out.put((byte) (color >> 16)).put((byte) (color >> 8)).put((byte) color).put((byte) (color >>> 24));
				int ao = d[i + 5];
				out.put((byte) (ao >>> 24)).put((byte) (ao >> 16)).put((byte) (ao >> 8)).put((byte) ao);
				int sky = d[i + 6];
				out.put((byte) (sky >>> 24)).put((byte) (sky >> 16)).put((byte) (sky >> 8)).put((byte) sky);
			}
		}

		/** The bake's uniform block: see {@code include/bake.glsl}. */
		private static ByteBuffer uniforms(GpuMesher.Mesh mesh, int originX, int originZ) {
			MapStyle style = MapStyle.get();
			double sin = mesh.sin;
			double cos = mesh.cos;
			double minV = mesh.key.minV();
			double maxV = minV + Projection.TILE_UNITS;
			int minY = mesh.minY;
			// Camera depth t = -(y + v cos) / sin: smallest at the top of the tile's south edge.
			double tNear = -(mesh.topY + 2 + maxV * cos) / sin;
			double tFar = -(minY - 2 + minV * cos) / sin;

			double elevation = Math.toRadians(style.sunElevation());
			double lx = Math.cos(elevation) * TileBaker.SUN_AZIMUTH_X;
			double ly = Math.sin(elevation);
			double lz = Math.cos(elevation) * TileBaker.SUN_AZIMUTH_Z;
			double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
			lx /= ll;
			ly /= ll;
			lz /= ll;
			// Axes across the sun: one level, one up its face.
			double ux = -lz;
			double uz = lx;
			double ul = Math.sqrt(ux * ux + uz * uz);
			ux /= ul;
			uz /= ul;
			// v = u x l, up the sun's face.
			double vx = -uz * ly;
			double vy = uz * lx - ux * lz;
			double vz = ux * ly;
			double[] uRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			double[] vRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			double[] dRange = {Double.MAX_VALUE, -Double.MAX_VALUE};
			int x0 = mesh.casterX0 - originX;
			int x1 = mesh.casterX1 - originX;
			int z0 = mesh.casterZ0 - originZ;
			int z1 = mesh.casterZ1 - originZ;
			int y0 = mesh.casterY0 - 1;
			int y1 = Math.max(mesh.casterY1, mesh.topY) + 1;
			for (int c = 0; c < 8; c++) {
				double px = (c & 1) == 0 ? x0 : x1;
				double py = (c & 2) == 0 ? y0 : y1;
				double pz = (c & 4) == 0 ? z0 : z1;
				extend(uRange, px * ux + pz * uz);
				extend(vRange, px * vx + py * vy + pz * vz);
				extend(dRange, px * lx + py * ly + pz * lz);
			}
			double uExtent = uRange[1] - uRange[0];
			double vExtent = vRange[1] - vRange[0];
			double texel = Math.max(uExtent, vExtent) / SHADOW_SIZE;

			ByteBuffer out = MemoryUtil.memAlloc(UBO_SIZE);
			Std140Builder b = Std140Builder.intoBuffer(out);
			b.putVec4((float) sin, (float) cos, (float) (originZ * sin - minV), (float) (-cos * originZ - tNear));
			b.putVec4(0, (float) (tFar - tNear), 0, 0);
			b.putVec4((float) lx, (float) ly, (float) lz, (float) TileBaker.SHADOW_DISTANCE);
			b.putVec4((float) ux, 0, (float) uz, (float) -uRange[0]);
			b.putVec4((float) vx, (float) vy, (float) vz, (float) -vRange[0]);
			b.putVec4((float) (2 / uExtent), (float) (2 / vExtent), (float) (dRange[0] - 1), (float) (dRange[1] + 1));
			b.putVec4(style.ambient(), style.sunStrength(), style.skyOcclusion(), style.heightShade());
			b.putVec4(style.sunTint()[0], style.sunTint()[1], style.sunTint()[2], 0);
			b.putVec4(style.shadowTint()[0], style.shadowTint()[1], style.shadowTint()[2], 0);
			b.putVec4(style.saturation(), style.exposure(), style.outline(), style.clearWater() ? 1 : 0);
			b.putVec4((float) (1.5 * texel + 0.02), 0, 0, 0);
			for (double[] j : TileBaker.SUN_JITTER) {
				b.putVec4((float) j[0], (float) j[1], (float) j[2], 0);
			}
			b.putVec4(mesh.lit ? 1 : 0, mesh.cave ? 1 : 0, mesh.topY, 0);
			b.putIVec4(mesh.voxelOrigin[0], mesh.voxelOrigin[1], mesh.voxelOrigin[2], mesh.rayTop);
			b.putIVec4(mesh.voxelSize[0], mesh.voxelSize[1], mesh.voxelSize[2], 0);
			for (int r = 0; r < TileBaker.SKY_RAYS.length; r++) {
				double[] d = TileBaker.SKY_RAYS[r];
				b.putVec4((float) d[0], (float) d[1], (float) d[2], (float) TileBaker.SKY_WEIGHTS[r]);
			}
			out.position(0);
			out.limit(UBO_SIZE);
			return out;
		}

		private static void extend(double[] range, double value) {
			range[0] = Math.min(range[0], value);
			range[1] = Math.max(range[1], value);
		}

		void done(int[] pixels, byte @Nullable [] info, long recordNanos, long gpuNanos) {
			PENDING.remove(this);
			if (!this.completed.compareAndSet(false, true)) {
				return;
			}
			if (gpuNanos > 0) {
				gpuCost = gpuCost * 0.9 + gpuNanos * 0.1;
			}
			Timing timing = new Timing(
				this.mesh.buildNanos, this.packNanos, recordNanos, gpuNanos, System.nanoTime() - this.submitted,
				this.faceCount(), this.casterCount, this.faceCount() * FACE_BYTES + this.casterCount * CASTER_BYTES
			);
			log(timing);
			this.callback.accept(new Result(pixels, info, timing));
		}

		void fail() {
			PENDING.remove(this);
			if (!this.completed.compareAndSet(false, true)) {
				return;
			}
			this.free();
			this.callback.accept(new Result(null, null, new Timing(this.mesh.buildNanos, this.packNanos, -1, -1, -1, this.faceCount(), this.casterCount, 0)));
		}

		void free() {
			if (this.faces != null) {
				MemoryUtil.memFree(this.faces);
				this.faces = null;
			}
			if (this.casters != null) {
				MemoryUtil.memFree(this.casters);
				this.casters = null;
			}
			if (this.uniforms != null) {
				MemoryUtil.memFree(this.uniforms);
				this.uniforms = null;
			}
			if (this.voxels != null) {
				MemoryUtil.memFree(this.voxels);
				this.voxels = null;
			}
		}
	}
}
//?}
