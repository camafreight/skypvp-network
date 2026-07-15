package network.skypvp.paper.nms.impl;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A {@link Connection} with no real Netty socket. All outbound packets are discarded and the link is always
 * reported as connected so vanilla keep-alive / disconnect paths never tear down a headless player body.
 */
final class FakeConnection extends Connection {

    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 0);
        this.virtualHost = new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public void channelActive(io.netty.channel.ChannelHandlerContext context) {
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext context) {
    }

    @Override
    public void exceptionCaught(io.netty.channel.ChannelHandlerContext context, Throwable throwable) {
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {
    }

    @Override
    public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void tick() {
    }

    @Override
    public void disconnect(Component reason) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setReadOnly() {
    }
}
