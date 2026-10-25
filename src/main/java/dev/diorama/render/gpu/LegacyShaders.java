//? if <1.21.6 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
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
import net.minecraft.client.renderer.RenderPipelines;
//?} else if >=1.21.2 {
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.ShaderProgram;
import org.lwjgl.opengl.GL11;
//?} else {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
//?}
//? if <1.21 {
import com.mojang.blaze3d.vertex.BufferUploader;
//?}

// How ImmediateCanvas draws before 1.21.6: three render types without depth testing, so the map
// draws in painter's order like the GUI render state of later versions. Textures are bound by
// hand (RenderSystem.setShaderTexture) before each draw, so none of these has a texture shard.
//
// A mod shader that fails to compile through the game's normal path takes the player's resource
// packs (1.21.2-1.21.4) or the whole resource reload (1.21.1, 1.21.5) down with it, so the fog's
// program only ever comes through a side door; fogReady() says whether it may be drawn. A resource
// reload can change the shader's sources, so the fog is checked again after each one.
//? if >=1.21 {
final class LegacyShaders {
//?} else {
// Before 1.21 shader shards and render type states were protected, so only a subclass of
// RenderType can build these. It's never made.
final class LegacyShaders extends RenderType {
//?}
	private static final Logger LOGGER = LoggerFactory.getLogger("diorama");

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

	private static boolean fogFailed;

	static boolean fogReady() {
		// Compiled at most once per resource reload (the device clears its pipeline cache then).
		boolean ready = RenderSystem.getDevice().precompilePipeline(FOG_PIPELINE).isValid();
		if (!ready && !fogFailed) {
			fogFailed = true;
			LOGGER.error("Diorama's fog shader didn't compile; the map draws without fog");
		}
		return ready;
	}

	static void draw(RenderType type, MeshData mesh) {
		type.draw(mesh);
	}

	static void drawFog(MeshData mesh) {
		FOG.draw(mesh);
	}
	//?} else {
	//? if >=1.21.2 {
	private static final ShaderProgram FOG_PROGRAM = new ShaderProgram(Diorama.id("core/fog"), DefaultVertexFormat.POSITION_TEX_COLOR, ShaderDefines.EMPTY);
	private static final RenderStateShard.ShaderStateShard TEXTURED_SHADER = new RenderStateShard.ShaderStateShard(CoreShaders.POSITION_TEX_COLOR);
	// No shader shard: drawFog sets the program fogReady() got through the side door. A shard would
	// fetch it through the normal path, which on a compile failure clears the player's resource packs.
	private static final RenderStateShard.ShaderStateShard FOG_SHADER = RenderStateShard.NO_SHADER;
	private static CompiledShaderProgram fogProgram;
	//?} else {
	private static ShaderInstance fogShader;
	private static final RenderStateShard.ShaderStateShard TEXTURED_SHADER = new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
	private static final RenderStateShard.ShaderStateShard FOG_SHADER = new RenderStateShard.ShaderStateShard(() -> fogShader);
	//?}
	// Each resource reload makes new instances of vanilla's own programs; this is the one seen at the
	// last check, so a different one means the fog has to be checked again.
	private static Object fogReload;
	private static boolean fogFailed;

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
		ShaderManager shaders = Minecraft.getInstance().getShaderManager();
		Object reload = shaders.getProgram(CoreShaders.POSITION_TEX_COLOR);
		if (reload != fogReload) {
			fogReload = reload;
			fogFailed = false;
		}
		fogProgram = null;
		if (fogFailed) {
			return false;
		}
		// Asked every frame: once compiled it comes from the shader manager's cache, which a reload
		// replaces, so a program from before a reload is never drawn. getProgramForLoading throws
		// where getProgram would start the resource pack recovery.
		try {
			fogProgram = shaders.getProgramForLoading(FOG_PROGRAM);
			return true;
		} catch (Exception e) {
			fogFailed = true;
			LOGGER.error("Diorama's fog shader didn't compile; the map draws without fog", e);
			return false;
		}
		//?} else {
		ShaderInstance reload = GameRenderer.getPositionTexColorShader();
		if (reload != fogReload) {
			fogReload = reload;
			fogFailed = false;
			if (fogShader != null) {
				fogShader.close();
				fogShader = null;
			}
		}
		// Built by hand rather than through Fabric's core shader callback, whose failure would fail
		// the whole resource reload. The json lives in the minecraft namespace, where the vanilla
		// constructor looks; its stages name diorama:fog, which Fabric API resolves. The game doesn't
		// know about it, so it's closed and built again here after a reload. The json also has the
		// blend and attributes 1.20.1 reads, as vanilla's position_tex_color does there: without
		// them the program turns blending off, and its inputs aren't bound to the vertex format's
		// slots. 1.21 ignores both.
		if (fogShader == null && !fogFailed) {
			try {
				fogShader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), "diorama_fog", DefaultVertexFormat.POSITION_TEX_COLOR);
			} catch (Exception e) {
				fogFailed = true;
				LOGGER.error("Diorama's fog shader didn't compile; the map draws without fog", e);
			}
		}
		return fogShader != null;
		//?}
	}

	// The depth test shard for "always" leaves depth testing as it found it, and before 1.21.2 the
	// GUI's flush turns it on, so earlier GUI shapes could hide parts of the map. Off for the draw,
	// then back as it was.
	static void draw(RenderType type, MeshData mesh) {
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		RenderSystem.disableDepthTest();
		//? if >=1.21 {
		type.draw(mesh);
		//?} else {
		// What RenderType.draw does from 1.21.
		type.setupRenderState();
		BufferUploader.drawWithShader(mesh);
		type.clearRenderState();
		//?}
		if (depth) {
			RenderSystem.enableDepthTest();
		}
	}

	static void drawFog(MeshData mesh) {
		//? if >=1.21.2 {
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		FOG.setupRenderState();
		RenderSystem.disableDepthTest();
		RenderSystem.setShader(fogProgram);
		BufferUploader.drawWithShader(mesh);
		FOG.clearRenderState();
		if (depth) {
			RenderSystem.enableDepthTest();
		}
		//?} else {
		draw(FOG, mesh);
		//?}
	}
	//?}

	private LegacyShaders() {
		//? if <1.21 {
		super("diorama", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
		//?}
	}
}
*///?}
