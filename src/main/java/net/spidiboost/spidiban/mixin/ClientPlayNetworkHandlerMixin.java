package net.spidiboost.spidiban.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.spidiboost.spidiban.SpidiBanClient;
import net.spidiboost.spidiban.SpidiBanAutomation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback for clients where another mod takes over the client-command
 * dispatcher.  It consumes only the two exact local commands before either
 * can be transmitted to a server.
 */
@Mixin(ClientPlayNetworkHandler.class)
public final class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void spidiban$commands(String command, CallbackInfo ci) {
        if (SpidiBanClient.tryHandleInput(command)) ci.cancel();
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void spidiban$chatCommands(String message, CallbackInfo ci) {
        if (SpidiBanClient.tryHandleInput(message)) ci.cancel();
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void spidiban$serverGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        SpidiBanAutomation.onIncomingMessage(packet.content().getString());
    }

    @Inject(method = "onChatMessage", at = @At("HEAD"))
    private void spidiban$serverChatMessage(ChatMessageS2CPacket packet, CallbackInfo ci) {
        String message = packet.unsignedContent() == null
                ? packet.body().content()
                : packet.unsignedContent().getString();
        SpidiBanAutomation.onIncomingMessage(message);
    }
}
