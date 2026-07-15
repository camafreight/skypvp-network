package network.skypvp.paper.nms.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * Packet listener for a headless player. Inbound client packets are ignored and {@link #tick()} is a no-op so
 * vanilla keep-alive logic cannot time out and remove the body.
 *
 * <p>Forced disconnects (most importantly the vanilla duplicate-login kick fired when the real owner reconnects) must
 * NOT be swallowed silently: the login flow waits in {@code WAITING_FOR_DUPE_DISCONNECT} until the old body leaves the
 * player list, so every disconnect overload triggers {@code forcedKickHandler}, which despawns the body properly and
 * frees the uuid slot.
 */
final class InertHeadlessPacketListener extends ServerGamePacketListenerImpl {

    private final Runnable forcedKickHandler;
    private final AtomicBoolean kickHandled = new AtomicBoolean();

    InertHeadlessPacketListener(
            MinecraftServer server,
            FakeConnection connection,
            ServerPlayer player,
            CommonListenerCookie cookie,
            Runnable forcedKickHandler) {
        super(server, connection, player, cookie);
        this.forcedKickHandler = forcedKickHandler;
    }

    private void handleForcedKick() {
        if (this.forcedKickHandler != null && this.kickHandled.compareAndSet(false, true)) {
            this.forcedKickHandler.run();
        }
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        this.handleForcedKick();
    }

    @Override
    public void disconnect(net.kyori.adventure.text.Component reason) {
        this.handleForcedKick();
    }

    @Override
    public void disconnect(net.kyori.adventure.text.Component reason, PlayerKickEvent.Cause cause) {
        this.handleForcedKick();
    }

    @Override
    public void disconnect(Component reason, PlayerKickEvent.Cause cause) {
        this.handleForcedKick();
    }

    // Extra overloads without @Override on purpose: they hook the vanilla/Paper variants when present on this
    // mapped version and are harmless dead code otherwise.
    public void disconnect(Component reason) {
        this.handleForcedKick();
    }

    public void disconnect(DisconnectionDetails details) {
        this.handleForcedKick();
    }

    public void disconnect(DisconnectionDetails details, PlayerKickEvent.Cause cause) {
        this.handleForcedKick();
    }
}
