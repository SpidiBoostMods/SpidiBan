package net.spidiboost.spidiban.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.spidiboost.spidiban.SpidiBanAutomation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Input.class)
public abstract class ClientInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void spidiban$lockMovementDuringQueue(CallbackInfo ci) {
        if (!SpidiBanAutomation.shouldLockControls()) return;
        Input input = (Input) (Object) this;
        input.movementForward = 0.0F;
        input.movementSideways = 0.0F;
        input.playerInput = new PlayerInput(false, false, false, false, false, false, false);
    }
}
