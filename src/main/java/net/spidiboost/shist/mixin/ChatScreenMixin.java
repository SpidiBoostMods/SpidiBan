package net.spidiboost.shist.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.spidiboost.shist.ShistClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public final class ChatScreenMixin {
    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void spidiban$shist(String message, boolean addToHistory, CallbackInfo ci) {
        if (ShistClient.handleInput(message)) {
            if (addToHistory) MinecraftClient.getInstance().inGameHud.getChatHud().addToMessageHistory(message);
            MinecraftClient.getInstance().setScreen(null);
            ci.cancel();
        }
    }
}
