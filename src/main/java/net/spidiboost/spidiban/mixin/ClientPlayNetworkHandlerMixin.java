package net.spidiboost.spidiban.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.spidiboost.spidiban.SpidiBanClient;
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

}
