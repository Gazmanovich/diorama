package dev.diorama.mixin;

//? if >=1.21.9 {
import dev.diorama.XaeroChannels;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if >=1.21 {
/*import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
*///?} else {
/*import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
*///?}

/**
 * Queues the level ID {@link CustomPayloadMixin} read where its message arrives among the
 * packets. Before 1.21.9, packets and tasks share the client's one queue, and nothing is needed
 * here.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class CustomPayloadPacketMixin {
	//? if >=1.21.9 {
	// HEAD: the first call, on the network thread before the loader hands the message to the client
	// thread, which calls this again; or on the client thread for a message in a bundle.
	@Inject(method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V", at = @At("HEAD"))
	private void diorama$queueLevelId(ClientCommonPacketListener listener, CallbackInfo ci) {
		XaeroChannels.queuePeeked(((ClientboundCustomPayloadPacket) (Object) this).payload(), listener);
	}
	//?}
}
