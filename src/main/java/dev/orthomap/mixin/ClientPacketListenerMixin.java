package dev.orthomap.mixin;

import dev.orthomap.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the map when the server sends new light for a chunk, which cave slices draw. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLightUpdatePacket", at = @At("TAIL"))
	private void orthomap$onLightUpdate(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			MapSession.lightUpdated(mc.level, packet.x(), packet.z());
		}
	}
}
