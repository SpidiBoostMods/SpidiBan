package net.spidiboost.spidiban.mixin;

import net.minecraft.client.Keyboard;
import net.spidiboost.spidiban.SpidiBanAutomation;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void spidiban$blockInventoryAndEscape(long window, int key, int scancode, int action,
                                                  int modifiers, CallbackInfo ci) {
        if (!SpidiBanAutomation.shouldLockControls() || action == GLFW.GLFW_RELEASE) return;
        if (key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_ESCAPE) ci.cancel();
    }
}
