package dev.diorama.mixin;

import dev.diorama.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if neoforge && <1.21.4 || forge {
/*import net.neoforged.neoforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Tells the map when server light for a chunk has been applied, which cave slices draw. Hooks the
 * method that applies it; the packet handler only queues that work. Also tells it when a block
 * entity got new data, which is how a copycat being filled reaches the client.
 * On NeoForge before 1.21.4 and on Forge it also runs the client's own commands from chat links.
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

	// Only reached on the client thread: on the network thread the handler hands the packet over and
	// returns by throwing.
	@Inject(method = "handleBlockEntityData", at = @At("TAIL"))
	private void diorama$onBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			MapSession.blockEntityChanged(mc.level, packet.getPos());
		}
	}

	//? if neoforge && <1.21.4 || forge {
	/*// A chat link's command, like a shared waypoint's [Add], goes through here. NeoForge 21.0 and
	// 21.3 send it straight to the server, so the client's own commands never run (NeoForge 21.1
	// checks them itself from 21.1.178, and 21.4 on always does). Forge 1.20.1 does the same.
	@Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
	private void diorama$runClientCommand(String command, CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandHandler.runCommand(command)) {
			cir.setReturnValue(true);
		}
	}
	*///?}
}
