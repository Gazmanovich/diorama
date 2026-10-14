package dev.diorama.mixin;

import dev.diorama.waypoint.WaypointShare;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A chat line that carries a shared waypoint gets an "[Add]" link on the end as chat adds it. The
 * link is part of the line, so it hides, trims and gets deleted with it, and Minecraft's own checks
 * (hidden chat, blocked players) still decide whether the line shows at all. A signed message's
 * signature, trust tag and chat report entry are untouched; the chat log and copied lines include
 * the "[Add]".
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
	private Component diorama$linkSharedWaypoint(Component contents) {
		return WaypointShare.withAddLink(contents);
	}
}
