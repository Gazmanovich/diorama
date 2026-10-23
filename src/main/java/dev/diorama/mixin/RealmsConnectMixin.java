package dev.diorama.mixin;

import com.mojang.realmsclient.dto.RealmsServer;
import dev.diorama.WorldKey;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.realms.RealmsConnect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes which realm the player is joining. The connection only knows the realm by the address it's
 * running at, and a realm that went to sleep wakes up at another one.
 */
@Mixin(RealmsConnect.class)
public abstract class RealmsConnectMixin {
	@Inject(method = "connect", at = @At("HEAD"))
	private void diorama$onConnect(RealmsServer server, ServerAddress address, CallbackInfo ci) {
		WorldKey.joiningRealm(server.id);
	}
}
