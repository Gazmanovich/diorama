package dev.diorama.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.diorama.waypoint.WorldProjection;
import net.minecraft.client.renderer.GameRenderer;
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import net.minecraft.client.Camera;
import org.joml.Quaternionf;
*///?}
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the projection the world is drawn with, after view bobbing, the damage tilt and
 * nausea are applied, so waypoint labels can stay pinned to their spot in the world. On Fabric
 * before 1.21.6 it also draws those waypoints, before the rest of the GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	// The GPU bake experiment draws its tiles before the frame opens any pass.
	//? if >=26.3 {
	@Inject(method = "render()V", at = @At("HEAD"))
	//?} else if >=1.21 {
	/*@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	*///?} else {
	/*@Inject(method = "render(FJZ)V", at = @At("HEAD"))
	*///?}
	private void diorama$gpuBakes(CallbackInfo ci) {
		dev.diorama.render.gpu.GpuTileBaker.frame();
	}

	//? if >=26.1 {
	// Before 26.3, renderLevel sets the projection a second time, for the hand.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		WorldProjection.capture(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
	//?} else if >=1.21.9 {
	/*// renderLevel sets the projection twice here: first for the world, then for the hand.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
		// The view rotation renderLevel builds next, for the level renderer.
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.position());
	}
	*///?} else if >=1.21.6 {
	/*// As above, but the culling projection is made before the world's is set, so the world's
	// is picked as the first Matrix4f local.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.position());
	}
	*///?} else if >=1.21.2 {
	/*// As above; the camera's position had a getter.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.getPosition());
	}
	*///?} else if >=1.21 {
	/*// renderLevel set the world's projection through resetProjectionMatrix, and it was the only
	// Matrix4f local at that point.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.getPosition());
	}
	*///?} else {
	/*// 1.20.1 sets the camera up after the world's projection, so this waits for the level
	// renderer's call: by then the camera has this frame's rotation, where the projection's call
	// would still see the last frame's. The projection is the first Matrix4f local there too. The
	// view rotation is the pose stack renderLevel turned by the camera's pitch and yaw, as the
	// level renderer gets it: 1.20.1's camera quaternion doesn't turn the same way as later ones'.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix, @Local(argsOnly = true) com.mojang.blaze3d.vertex.PoseStack view) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f(view.last().pose()), camera.getPosition());
	}
	*///?}

	// Waypoints in the world go under the whole GUI on Fabric before 1.21.6 and on Forge too, as
	// addFirst from 1.21.6 (and NeoForge's registerBelowAll) puts them. Fabric API's HUD layers and
	// Forge's GUI overlays there come after the nausea overlay and the totem animation, which this
	// frame draws before the HUD, so they're drawn as the frame starts its GUI. The GUI depth-tests
	// and vanilla draws some of it below zero (1.20.1's hotbar at -90), so the waypoints go further
	// down than any of it: 5000 down, or 900 on Forge, whose GUI reaches only 1000 below zero
	// (ForgeHooksClient.getGuiFarPlane) where vanilla's reaches 10000.
	//? if fabric && >=1.21 && <1.21.6 {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true) net.minecraft.client.DeltaTracker deltaTracker) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -5000);
		diorama$waypoints.extractRenderState(graphics, deltaTracker);
		pose.popPose();
	}
	*///?} else if fabric && <1.21 {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true, ordinal = 0) float partialTick) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -5000);
		diorama$waypoints.extractRenderState(graphics, partialTick);
		pose.popPose();
	}
	*///?} else if forge {
	/*@org.spongepowered.asm.mixin.Unique
	private static final dev.diorama.waypoint.WaypointHud diorama$waypoints = new dev.diorama.waypoint.WaypointHud();

	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=gui"))
	private void diorama$waypointsUnderGui(CallbackInfo ci, @Local net.minecraft.client.gui.GuiGraphicsExtractor graphics, @Local(argsOnly = true, ordinal = 0) float partialTick) {
		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, 0, -900);
		diorama$waypoints.extractRenderState(graphics, partialTick);
		pose.popPose();
	}
	*///?}
}
