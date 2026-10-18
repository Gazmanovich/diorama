//? if <1.21.6 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.diorama.Diorama;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=1.21.5 {
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
//?} else {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
//?}

// How ImmediateCanvas draws before 1.21.6: three render types without depth testing, so the map
// draws in painter's order like the GUI render state of later versions. Textures are bound by
// hand (RenderSystem.setShaderTexture) before each draw, so none of these has a texture shard.
//
// A mod shader that fails to compile through the game's normal path takes the player's resource
// packs (1.21.2-1.21.4) or the whole resource reload (1.21.1, 1.21.5) down with it, so the fog's
// program is compiled through a side door first; fogReady() says whether it may be drawn.
final class LegacyShaders {
	private static final Logger LOGGER = LoggerFactory.getLogger("diorama");
	private static boolean fogFailed;

	//? if >=1.21.5 {
	private static final RenderPipeline TEXTURED_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
		.withLocation(Diorama.id("pipeline/textured"))
		.withVertexShader("core/position_tex_color")
		.withFragmentShader("core/position_tex_color")
		.withSampler("Sampler0")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.withCull(false)
		.build();
	private static final RenderPipeline SOLID_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
		.withLocation(Diorama.id("pipeline/solid"))
		.withVertexShader("core/position_color")
		.withFragmentShader("core/position_color")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.build();
	// Not registered with RenderPipelines: a registered pipeline that fails to compile fails the
	// resource reload. Unregistered ones compile on first use from the current shader sources.
	private static final RenderPipeline FOG_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_COLOR_SNIPPET)
		.withLocation(Diorama.id("pipeline/fog"))
		.withVertexShader(Diorama.id("core/fog"))
		.withFragmentShader(Diorama.id("core/fog"))
		.withUniform("GameTime", UniformType.FLOAT)
		.withSampler("Sampler0")
		.withSampler("Sampler1")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withDepthWrite(false)
		.withCull(false)
		.build();

	static final RenderType TEXTURED = RenderType.create("diorama_textured", 1536, TEXTURED_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));
	static final RenderType SOLID = RenderType.create("diorama_solid", 1536, SOLID_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));
	static final RenderType FOG = RenderType.create("diorama_fog", 1536, FOG_PIPELINE, RenderType.CompositeState.builder().createCompositeState(false));

	static boolean fogReady() {
		// Compiled at most once per resource reload (the device clears its pipeline cache then).
		boolean ready = RenderSystem.getDevice().precompilePipeline(FOG_PIPELINE).isValid();
		return checked(ready);
	}
	//?} else {
	//? if >=1.21.2 {
	private static final ShaderProgram FOG_PROGRAM = new ShaderProgram(Diorama.id("core/fog"), DefaultVertexFormat.POSITION_TEX_COLOR, ShaderDefines.EMPTY);
	private static final RenderStateShard.ShaderStateShard TEXTURED_SHADER = new RenderStateShard.ShaderStateShard(CoreShaders.POSITION_TEX_COLOR);
	private static final RenderStateShard.ShaderStateShard FOG_SHADER = new RenderStateShard.ShaderStateShard(FOG_PROGRAM);
	private static boolean fogChecked;
	//?} else {
	private static ShaderInstance fogShader;
	private static final RenderStateShard.ShaderStateShard TEXTURED_SHADER = new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
	private static final RenderStateShard.ShaderStateShard FOG_SHADER = new RenderStateShard.ShaderStateShard(() -> fogShader);
	//?}

	static final RenderType TEXTURED = create("diorama_textured", DefaultVertexFormat.POSITION_TEX_COLOR, TEXTURED_SHADER);
	static final RenderType SOLID = create("diorama_solid", DefaultVertexFormat.POSITION_COLOR, RenderStateShard.POSITION_COLOR_SHADER);
	static final RenderType FOG = create("diorama_fog", DefaultVertexFormat.POSITION_TEX_COLOR, FOG_SHADER);

	private static RenderType create(String name, VertexFormat format, RenderStateShard.ShaderStateShard shader) {
		return RenderType.create(name, format, VertexFormat.Mode.QUADS, 1536, RenderType.CompositeState.builder()
			.setShaderState(shader)
			.setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
			.setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
			.setCullState(RenderStateShard.NO_CULL)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.createCompositeState(false));
	}

	static boolean fogReady() {
		//? if >=1.21.2 {
		// getProgramForLoading throws instead of triggering the resource pack recovery that the
		// shader state's getProgram would, and caches the program when it compiles.
		if (!fogChecked) {
			fogChecked = true;
			try {
				Minecraft.getInstance().getShaderManager().getProgramForLoading(FOG_PROGRAM);
			} catch (Exception e) {
				LOGGER.error("Diorama's fog shader didn't compile; the map draws without fog", e);
				return checked(false);
			}
		}
		return checked(true);
		//?} else {
		// Built by hand rather than through Fabric's core shader callback, whose failure would fail
		// the whole resource reload. The json lives in the minecraft namespace, where the vanilla
		// constructor looks; its stages name diorama:fog, which Fabric API resolves.
		if (fogShader == null && !fogFailed) {
			try {
				fogShader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), "diorama_fog", DefaultVertexFormat.POSITION_TEX_COLOR);
			} catch (Exception e) {
				LOGGER.error("Diorama's fog shader didn't compile; the map draws without fog", e);
				return checked(false);
			}
		}
		return checked(fogShader != null);
		//?}
	}
	//?}

	private static boolean checked(boolean ready) {
		if (!ready && !fogFailed) {
			fogFailed = true;
			LOGGER.error("Diorama's fog shader isn't available; the map draws without fog");
		}
		return ready;
	}

	private LegacyShaders() {
	}
}
*///?}
