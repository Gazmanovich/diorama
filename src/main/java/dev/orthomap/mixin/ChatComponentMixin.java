package dev.orthomap.mixin;

import dev.orthomap.waypoint.WaypointShare;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells waypoint sharing about each message chat actually shows, right after it's added. Messages
 * Minecraft holds back (hidden chat, blocked players) never get this far, so a shared waypoint in
 * one of them is never offered.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Inject(
		method = "addMessage",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;addMessageToQueue(Lnet/minecraft/client/multiplayer/chat/GuiMessage;)V", shift = At.Shift.AFTER)
	)
	private void orthomap$onMessageShown(Component contents, @Nullable MessageSignature signature, GuiMessageSource source, @Nullable GuiMessageTag tag, CallbackInfo ci) {
		// The client's own lines include the offers themselves.
		if (source != GuiMessageSource.SYSTEM_CLIENT) {
			WaypointShare.shown(contents);
		}
	}
}
