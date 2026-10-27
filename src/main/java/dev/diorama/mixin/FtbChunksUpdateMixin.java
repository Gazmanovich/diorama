package dev.diorama.mixin;

import dev.diorama.compat.FtbChunks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After FTB Chunks applies a claim update from the server to a chunk of its client map, tells
 * {@link FtbChunks}. Only applies when FTB Chunks is installed; the task has had this one method
 * from 1.20.1 to 26.1.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.client.map.UpdateChunkFromServerTask", remap = false)
public abstract class FtbChunksUpdateMixin {
	@Inject(method = "runMapTask", at = @At("RETURN"), require = 0, remap = false)
	private void diorama$claimUpdated(CallbackInfo ci) {
		FtbChunks.updated(this);
	}
}
