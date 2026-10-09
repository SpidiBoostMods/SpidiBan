package net.spidiboost.shist.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.spidiboost.shist.ShistSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public final class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void spidiban$command(String command, CallbackInfo ci) {
        if (net.spidiboost.shist.ShistClient.handleInput("/" + command)) ci.cancel();
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void spidiban$chat(String message, CallbackInfo ci) {
        if (net.spidiboost.shist.ShistClient.handleInput(message)) ci.cancel();
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void spidiban$game(GameMessageS2CPacket packet, CallbackInfo ci) {
        // Vanilla re-enters this method on the client thread. Capturing both
        // HEAD invocations previously counted every packet twice.
        if (!MinecraftClient.getInstance().isOnThread() || packet.overlay()) return;
        ShistSession.accept(packet.content().getString());
    }

}
