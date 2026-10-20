package dev.diorama.mixin;

import dev.diorama.MapSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if neoforge && <1.21.4 {
/*import net.neoforged.neoforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Tells the map when server light for a chunk has been applied, which cave slices draw. Hooks the
 * method that applies it; the packet handler only queues that work.
 * On NeoForge before 1.21.4 it also runs the client's own commands from chat links.
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

	//? if neoforge && <1.21.4 {
	/*// A chat link's command, like a shared waypoint's [Add], goes through here. NeoForge 21.0 and
	// 21.3 send it straight to the server, so the client's own commands never run (NeoForge 21.1
	// checks them itself from 21.1.178, and 21.4 on always does).
	@Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
	private void diorama$runClientCommand(String command, CallbackInfoReturnable<Boolean> cir) {
		if (ClientCommandHandler.runCommand(command)) {
			cir.setReturnValue(true);
		}
	}
	*///?}
}
