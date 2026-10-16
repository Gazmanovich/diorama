package dev.diorama.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//? if >=26.1 {
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//?} else {
/*import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
*///?}
import dev.diorama.Diorama;
//? if >=26.1 {
import net.minecraft.client.renderer.BindGroupLayouts;
//?}
import net.minecraft.client.renderer.RenderPipelines;

public final class DioramaPipelines {
	/** The fog over unknown map areas: known-area mask in sampler 0, noise in sampler 1. */
	//? if >=26.1 {
	public static final RenderPipeline FOG = RenderPipelines.register(
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
	//?} else {
	/*// Built like vanilla's GUI_TEXTURED_SNIPPET, with the globals and a second sampler added.
	public static final RenderPipeline FOG = RenderPipelines.register(
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

	/** Forces class loading so the pipeline is registered before shaders compile. */
	public static void init() {
	}
}
