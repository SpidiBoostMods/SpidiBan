package net.spidiboost.spidiban.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.spidiboost.spidiban.SpidiBanClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles local aliases before the Fabric command dispatcher or another chat
 * mod can swallow them. This is the earliest reliable point for a submitted
 * chat line in Minecraft 1.21.4.
 */
@Mixin(ChatScreen.class)
public final class ChatScreenMixin {
    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void spidiban$fromChatScreen(String message, boolean addToHistory, CallbackInfo ci) {
        if (SpidiBanClient.tryHandleInput(message)) {
            if (addToHistory) MinecraftClient.getInstance().inGameHud.getChatHud().addToMessageHistory(message);
            MinecraftClient.getInstance().setScreen(null);
            ci.cancel();
        }
    }
}
