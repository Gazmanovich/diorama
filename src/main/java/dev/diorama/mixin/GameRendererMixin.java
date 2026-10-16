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
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix"))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		WorldProjection.capture(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
	//?} else {
	/*// renderLevel sets the projection twice here: first for the world, then for the hand.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix", ordinal = 0))
	private void diorama$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
		// The view rotation renderLevel builds next, for the level renderer.
		Camera camera = ((GameRenderer) (Object) this).getMainCamera();
		WorldProjection.capture(projectionMatrix, new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf())), camera.position());
	}
	*///?}
}
