package dev.orthomap.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.orthomap.waypoint.WorldProjection;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix"))
	private void orthomap$captureWorldProjection(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		WorldProjection.capture(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
}
