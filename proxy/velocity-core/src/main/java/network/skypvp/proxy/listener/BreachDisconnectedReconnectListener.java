package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.service.ServerRoutingService;
import org.slf4j.Logger;

/**
 * Keeps reconnecting breach participants on the extraction pod hosting their session (disconnected stand-in or offline
 * elimination spectator slot) instead of lobby or a random pod.
 *
 * <p>Proxy routing is pod-scoped ({@code serverId}). Multi-instance selection on that pod is handled by the extraction
 * backend via the published {@code instanceId} / local player index.
 */
public final class BreachDisconnectedReconnectListener {

    private final ServerRoutingService routingService;
    private final NetworkStateRegistry stateRegistry;
    private final Logger logger;

    public BreachDisconnectedReconnectListener(
            ServerRoutingService routingService,
            NetworkStateRegistry stateRegistry,
            Logger logger
    ) {
        this.routingService = routingService;
        this.stateRegistry = stateRegistry;
        this.logger = logger;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> hostServer = this.routingService.selectReconnectServerForBreachRaider(event.getPlayer().getUniqueId());
        if (hostServer.isEmpty()) {
            return;
        }
        String hostServerId = hostServer.get().getServerInfo().getName();
        Optional<NetworkStateRegistry.BreachDisconnectedSnapshot> disconnected =
            this.stateRegistry.breachDisconnectedPresence(event.getPlayer().getUniqueId());
        Optional<NetworkStateRegistry.BreachSpectatorSnapshot> spectator =
            this.stateRegistry.breachSpectatorPresence(event.getPlayer().getUniqueId());
        if (disconnected.isEmpty() && spectator.isEmpty()) {
            return;
        }
        RegisteredServer intended = event.getResult().getServer().orElse(event.getOriginalServer());
        if (intended == null || hostServerId.equalsIgnoreCase(intended.getServerInfo().getName())) {
            return;
        }
        event.setResult(ServerResult.allowed(hostServer.get()));
        this.logger.info(
                "Redirecting breach reconnect '{}' from '{}' to hosting extraction pod '{}'.",
                event.getPlayer().getUsername(),
                intended.getServerInfo().getName(),
                hostServerId
        );
    }
}
