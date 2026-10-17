package net.spidiboost.spidiban;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;

public final class ServerPacketObserver extends ChannelInboundHandlerAdapter {
    private final ClientConnection connection;
    private boolean warned;

    public ServerPacketObserver(ClientConnection connection) {
        this.connection = connection;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
        try {
            if (packet instanceof BundlePacket<?> bundle) {
                for (var inner : bundle.getPackets()) observe(inner);
            } else observe(packet);
        } catch (RuntimeException error) {
            if (!warned) {
                warned = true;
                SpidiBanClient.LOGGER.warn("Could not inspect server ban evidence; packet forwarding is unchanged", error);
            }
        }
        context.fireChannelRead(packet);
    }

    private void observe(Object packet) {
        if (packet instanceof GameMessageS2CPacket message) {
            SpidiBanAutomation.captureServerMessage(connection, message.content().getString());
        } else if (packet instanceof ProfilelessChatMessageS2CPacket message) {
            SpidiBanAutomation.captureServerMessage(connection, message.chatType().applyChatDecoration(message.message()).getString());
        }
    }
}
