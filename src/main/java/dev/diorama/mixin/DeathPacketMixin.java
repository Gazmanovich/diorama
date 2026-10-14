package dev.diorama.mixin;

import dev.diorama.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks where the player died. The server sends this packet for every death, instant respawn or not. */
@Mixin(ClientPacketListener.class)
public abstract class DeathPacketMixin {
	// TAIL: the handler first re-queues itself onto the client thread, and only that second run
	// gets this far.
	@Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
	private void diorama$onDeath(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && packet.playerId() == mc.player.getId()) {
			Waypoints.died(mc.player);
		}
	}
}
