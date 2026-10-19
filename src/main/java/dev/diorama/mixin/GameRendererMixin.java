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
 * nausea are applied, so waypoint labels can stay pinned to their spot in the world.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
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
	*///?} else {
	/*// renderLevel set the world's projection through resetProjectionMatrix, and it was the only
	// Matrix4f local at that point.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.getPosition());
	}
	*///?}
}
