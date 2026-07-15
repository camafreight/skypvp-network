package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.repository.FriendRepository;
import network.skypvp.shared.ServerTextUtil;
import org.slf4j.Logger;

/**
 * Notifies online friends when a player joins or leaves the network.
 * Vanished players do not trigger join/leave presence messages.
 */
public final class FriendPresenceListener {

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final FriendRepository friendRepository;
    private final NetworkStateRegistry stateRegistry;
    private final Logger logger;

    public FriendPresenceListener(
            ProxyServer proxyServer,
            Object plugin,
            FriendRepository friendRepository,
            NetworkStateRegistry stateRegistry,
            Logger logger
    ) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.friendRepository = Objects.requireNonNull(friendRepository, "friendRepository");
        this.stateRegistry = stateRegistry;
        this.logger = logger;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (this.isVanished(player.getUniqueId())) {
            return;
        }
        this.notifyFriendsAsync(player.getUniqueId(), player.getUsername(), true);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (this.isVanished(player.getUniqueId())) {
            return;
        }
        this.notifyFriendsAsync(player.getUniqueId(), player.getUsername(), false);
    }

    private void notifyFriendsAsync(UUID playerId, String username, boolean joined) {
        CompletableFuture.supplyAsync(() -> this.friendRepository.listFriends(playerId))
                .whenComplete((friends, error) -> {
                    if (error != null) {
                        if (this.logger != null) {
                            this.logger.warn("[Friends] Failed to load friends for presence notify: {}", error.getMessage());
                        }
                        return;
                    }
                    if (friends == null || friends.isEmpty()) {
                        return;
                    }
                    Component message = joined
                            ? ServerTextUtil.friendNetworkJoinMessage(username)
                            : ServerTextUtil.friendNetworkLeaveMessage(username);
                    this.proxyServer.getScheduler().buildTask(this.plugin, () -> this.deliver(friends, playerId, message)).schedule();
                });
    }

    private void deliver(List<UUID> friends, UUID subjectId, Component message) {
        for (UUID friendId : friends) {
            if (friendId == null || friendId.equals(subjectId)) {
                continue;
            }
            this.proxyServer.getPlayer(friendId).ifPresent(friend -> friend.sendMessage(message));
        }
    }

    private boolean isVanished(UUID playerId) {
        return this.stateRegistry != null && this.stateRegistry.isVanished(playerId);
    }
}
