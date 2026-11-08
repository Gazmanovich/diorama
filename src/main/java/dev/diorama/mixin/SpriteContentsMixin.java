package dev.diorama.mixin;

import dev.diorama.bake.BlockLooks;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block colours are read from textures' images on whichever thread first meets a block, and a
 * resource reload frees the last atlas's images on the client thread: this keeps the two apart
 * (see {@link BlockLooks#closing}).
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements BlockLooks.ClosingTexture {
	@Unique
	private volatile boolean diorama$closed;

	@Inject(method = "close", at = @At("HEAD"))
	private void diorama$onClose(CallbackInfo ci) {
		BlockLooks.closing(this);
	}

	@Override
	public void diorama$markClosed() {
		this.diorama$closed = true;
	}

	@Override
	public boolean diorama$closed() {
		return this.diorama$closed;
	}
}
