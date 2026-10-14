package dev.diorama.mixin;

import dev.diorama.MapSession;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the map about every light section the client's light engine changed. Most light changes
 * never come as a packet: the client works out a placed torch or an opened wall itself.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
	@Inject(method = "onLightUpdate", at = @At("TAIL"))
	private void diorama$onLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
		MapSession.lightSectionChanged((ClientChunkCache) (Object) this, pos);
	}
}
