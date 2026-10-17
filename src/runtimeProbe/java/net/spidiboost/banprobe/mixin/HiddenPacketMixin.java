package net.spidiboost.banprobe.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.spidiboost.spidiban.NativeBanProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientConnection.class, priority = 500)
public abstract class HiddenPacketMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void probe$hide(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof GameMessageS2CPacket || packet instanceof ProfilelessChatMessageS2CPacket
                || packet instanceof net.minecraft.network.packet.BundlePacket<?>) {
            NativeBanProbe.hiddenPackets++;
            ci.cancel();
        }
    }
}
