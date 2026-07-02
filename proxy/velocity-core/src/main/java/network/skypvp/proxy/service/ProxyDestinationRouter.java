package network.skypvp.proxy.service;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import network.skypvp.shared.NetworkRoutes;
import org.slf4j.Logger;

public final class ProxyDestinationRouter {
    private final ProxyServer proxyServer;
    private final ServerRoutingService routingService;
    private final QueueService queueService;
    private final Logger logger;

    public ProxyDestinationRouter(
            ProxyServer proxyServer,
            ServerRoutingService routingService,
            QueueService queueService,
            Logger logger
    ) {
        this.proxyServer = proxyServer;
        this.routingService = routingService;
        this.queueService = queueService;
        this.logger = logger;
    }

    public void connectExact(Player player, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>That server is unavailable.<reset>"));
            return;
        }
        Optional<RegisteredServer> target = this.proxyServer.getServer(serverId);
        if (target.isEmpty()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>That server is not registered on the proxy right now.<reset>"));
            return;
        }
        if (player.getCurrentServer().map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(serverId)).orElse(false)) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset><#888888>You are already connected there.<reset>"));
            return;
        }
        player.createConnectionRequest(target.get()).fireAndForget();
    }

    public void route(Player player, String destinationKey) {
        if (destinationKey == null || destinationKey.isBlank()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>That destination is unavailable.<reset>"));
            return;
        }
        if (this.isAlreadyAtDestination(player, destinationKey)) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>➤ <reset><#888888>You are already connected to that destination.<reset>"));
            return;
        }
        Set<String> excluded = player.getCurrentServer().map(connection -> Set.of(connection.getServerInfo().getName())).orElse(Set.of());
        Optional<RegisteredServer> target = this.routingService.selectBestTargetForQueue(destinationKey, excluded);
        if (target.isPresent()) {
            player.createConnectionRequest(target.get()).fireAndForget();
            player.sendMessage(
                    ServerTextUtil.miniMessageComponent(
                            "<#FFD700>➤ <reset><#888888>Routing you to <reset><#FFB300>"
                                    + destinationKey
                                    + "<reset><#888888>.<reset>"
                    )
            );
            return;
        }
        QueueService.QueueJoinResult result = this.queueService.joinQueue(
                player.getUniqueId(),
                player.getUsername(),
                destinationKey.trim().toLowerCase(Locale.ROOT)
        );
        if (!result.valid()) {
            player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>That destination could not be queued.<reset>"));
        } else if (result.requiresSwapConfirmation()) {
            player.sendMessage(
                    ServerTextUtil.miniMessageComponent(
                            "<#FFD700>⚠ <reset><#888888>You are already in the <reset><#FFFFFF>"
                                    + result.queueKey()
                                    + "<reset><#888888> queue.<reset>"
                    )
            );
            player.sendMessage(
                    ServerTextUtil.miniMessageComponent(
                            "<#FFD700>➤ <reset><#888888>Run <reset><#FFFFFF>/hub<reset><#888888> again to leave and join <reset><#FFFFFF>"
                                    + result.targetQueueKey()
                                    + "<reset><#888888>.<reset>"
                    )
            );
        } else if (result.alreadyQueued()) {
            player.sendMessage(
                    ServerTextUtil.miniMessageComponent(
                            "<#FFD700>⌛ <reset><#888888>You are already queued for <reset><#FFFFFF>"
                                    + result.queueKey()
                                    + "<reset><#888888> at position <reset><#FFB300>"
                                    + result.position()
                                    + "<reset><#888888>.<reset>"
                    )
            );
        } else {
            player.sendMessage(
                    ServerTextUtil.miniMessageComponent(
                            "<#FFD700>⌛ <reset><#888888>No healthy lobby is free. Joined the <reset><#FFB300>"
                                    + result.queueKey()
                                    + "<reset><#888888> queue at position <reset><#FFB300>"
                                    + result.position()
                                    + "<reset><#888888>.<reset>"
                    )
            );
            this.logger.info("Queued '{}' for route key '{}' at position {}.", player.getUsername(), result.queueKey(), result.position());
        }
    }

    public void routeToLobby(Player player) {
        this.route(player, NetworkRoutes.LOBBY);
    }

    private boolean isAlreadyAtDestination(Player player, String destinationKey) {
        String requested = destinationKey == null ? "" : destinationKey.trim();
        if (requested.isBlank()) {
            return false;
        }
        Optional<String> currentServerOpt = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
        if (currentServerOpt.isEmpty()) {
            return false;
        }
        String currentServerId = currentServerOpt.get();
        if (currentServerId.equalsIgnoreCase(requested)) {
            return true;
        }
        Optional<ServerRoutingService.ServerRouteStatus> currentStatus = this.routingService.describeServer(currentServerId);
        if (currentStatus.isEmpty()) {
            return false;
        }
        ServerRoutingService.ServerRouteStatus status = currentStatus.get();
        return requested.equalsIgnoreCase(status.cluster()) || requested.equalsIgnoreCase(status.role());
    }
}
