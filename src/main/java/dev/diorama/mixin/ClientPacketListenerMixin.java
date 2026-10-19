package dev.diorama.mixin;

import dev.diorama.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the map when server light for a chunk has been applied, which cave slices draw. Hooks the
 * method that applies it; the packet handler only queues that work.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "applyLightData", at = @At("TAIL"))
	//? if >=1.21.2 {
	private void diorama$onLightApplied(int x, int z, ClientboundLightUpdatePacketData lightData, boolean scheduleRebuild, CallbackInfo ci) {
	//?} else {
	/*// There was no scheduleRebuild parameter.
	private void diorama$onLightApplied(int x, int z, ClientboundLightUpdatePacketData lightData, CallbackInfo ci) {
	*///?}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			MapSession.lightUpdated(mc.level, x, z);
		}
	}
}
