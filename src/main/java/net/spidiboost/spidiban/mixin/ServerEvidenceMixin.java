package net.spidiboost.spidiban.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.spidiboost.spidiban.ServerPacketObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ServerEvidenceMixin {
    @Inject(method = "channelActive", at = @At("HEAD"))
    private void spidiban$observe(ChannelHandlerContext context, CallbackInfo ci) {
        if (context.pipeline().get("spidiban_evidence") == null) {
            context.pipeline().addBefore(context.name(), "spidiban_evidence", new ServerPacketObserver((ClientConnection)(Object)this));
        }
    }
}
