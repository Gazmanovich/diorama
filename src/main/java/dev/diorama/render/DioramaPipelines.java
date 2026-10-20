//? if >=1.21.6 {
package dev.diorama.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
//? if >=26.1 {
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
//?}
//? if >=26.2 {
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexFormat;
*///?}
//? if <26.1 {
/*import com.mojang.blaze3d.platform.DepthTestFunction;
*///?}
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.diorama.Diorama;
//? if >=26.2 {
import net.minecraft.client.renderer.BindGroupLayouts;
//?}
import net.minecraft.client.renderer.RenderPipelines;

public final class DioramaPipelines {
	/**
	 * The fog over unknown map areas: known-area mask in sampler 0, noise in sampler 1. Each version
	 * builds it like its own vanilla GUI_TEXTURED_SNIPPET, with the globals and a second sampler added.
	 */
	//? if >=26.3 {
	public static final RenderPipeline FOG = register(
		RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/fog"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/fog"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build()
	);
	//?} else if >=26.2 {
	/*public static final RenderPipeline FOG = register(
		RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/fog"))
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/fog"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build()
	);
	*///?} else if >=26.1 {
	/*public static final RenderPipeline FOG = register(
		RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/fog"))
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/fog"))
			.withSampler("Sampler0")
			.withSampler("Sampler1")
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
			.build()
	);
	*///?} else {
	/*public static final RenderPipeline FOG = register(
		RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Diorama.id("pipeline/fog"))
			.withVertexShader(Diorama.id("core/fog"))
			.withFragmentShader(Diorama.id("core/fog"))
			.withSampler("Sampler0")
			.withSampler("Sampler1")
			.withBlend(BlendFunction.TRANSLUCENT)
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.build()
	);
	*///?}

	private DioramaPipelines() {
	}

	// Fabric: into vanilla's list, which shaders compile from. NeoForge constructs mods in parallel and
	// that list isn't thread-safe, so there DioramaNeoForge hands the pipeline to NeoForge's
	// RegisterRenderPipelinesEvent instead.
	private static RenderPipeline register(RenderPipeline pipeline) {
		//? if fabric {
		return RenderPipelines.register(pipeline);
		//?} else {
		/*return pipeline;
		*///?}
	}

	// Forces class loading so the pipeline is registered before shaders compile.
	public static void init() {
	}
}
//?}
