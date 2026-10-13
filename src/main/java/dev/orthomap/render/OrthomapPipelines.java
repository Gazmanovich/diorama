package dev.orthomap.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.orthomap.Orthomap;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

public final class OrthomapPipelines {
	/** The fog over unknown map areas: known-area mask in sampler 0, noise in sampler 1. */
	public static final RenderPipeline FOG = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Orthomap.id("pipeline/fog"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withVertexShader(Orthomap.id("core/fog"))
			.withFragmentShader(Orthomap.id("core/fog"))
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build()
	);

	private OrthomapPipelines() {
	}

	/** Forces class loading so the pipeline is registered before shaders compile. */
	public static void init() {
	}
}
