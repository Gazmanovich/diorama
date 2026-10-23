package dev.diorama.mixin;

import com.mojang.realmsclient.dto.RealmsServer;
import dev.diorama.WorldKey;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which realm a connection's server entry is for. The entry only has the address the realm
 * is running at, and a realm that went to sleep wakes up at another one.
 */
@Mixin(RealmsServer.class)
public abstract class RealmsServerMixin {
	@Inject(method = "toServerData", at = @At("RETURN"))
	private void diorama$noteRealm(String ip, CallbackInfoReturnable<ServerData> cir) {
		WorldKey.realmEntry(cir.getReturnValue(), ((RealmsServer) (Object) this).id);
	}
}
