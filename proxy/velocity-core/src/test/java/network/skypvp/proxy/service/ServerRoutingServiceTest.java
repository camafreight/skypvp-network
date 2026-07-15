package network.skypvp.proxy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.state.ServerLifecycleState;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.junit.jupiter.api.Test;

class ServerRoutingServiceTest {

    @Test
    void picksLeastLoadedHealthyLobbyForInitialJoin() {
        List<ServerRoutingService.ServerRouteStatus> statuses = List.of(
                status("lobby-1", "LOBBY", true, true, true, false, 80, 500, 500),
                status("lobby-2", "LOBBY", true, true, true, false, 20, 500, 500),
                status("survival-1", "SURVIVAL", true, true, true, false, 5, 250, 250)
        );

        assertEquals("lobby-2", ServerRoutingService.selectBestInitialServerId(statuses).orElseThrow());
    }

    @Test
    void excludesCurrentServerAndStaleTargetsForFallback() {
        List<ServerRoutingService.ServerRouteStatus> statuses = List.of(
                status("lobby-1", "LOBBY", true, true, true, false, 30, 500, 500),
                status("survival-1", "SURVIVAL", true, true, true, true, 10, 250, 250),
                status("minigame-1", "MINIGAME", true, true, true, false, 15, 250, 250)
        );

        assertEquals(
                "minigame-1",
                ServerRoutingService.selectBestFallbackServerId(statuses, "lobby-1").orElseThrow()
        );
    }

    @Test
    void returnsEmptyWhenNoHealthyLobbyExists() {
        List<ServerRoutingService.ServerRouteStatus> statuses = List.of(
                status("lobby-1", "LOBBY", false, true, true, false, 0, 500, 500),
                status("lobby-2", "LOBBY", true, false, true, false, 0, 500, 500)
        );

        assertTrue(ServerRoutingService.selectBestInitialServerId(statuses).isEmpty());
    }

    @Test
    void queueSelectionUsesClusterAndSkipsSoftCapTargets() {
        List<ServerRoutingService.ServerRouteStatus> statuses = List.of(
                status("survival-1", "SURVIVAL", true, true, true, false, 250, 250, 250),
                status("survival-2", "SURVIVAL", true, true, true, false, 40, 250, 250),
                status("minigame-1", "MINIGAME", true, true, true, false, 5, 250, 250)
        );

        assertEquals(
                "survival-2",
                ServerRoutingService.selectBestTargetForQueueId(statuses, "survival", java.util.Set.of()).orElseThrow()
        );
    }

    @Test
    void queueSelectionDoesNotFallBackToGlobalFallbackServer() {
        NetworkStateRegistry registry = new NetworkStateRegistry();
        registry.applyHeartbeat(new ServerHeartbeatEvent(
                "lobby-1",
                NetworkServerRole.LOBBY,
                20,
                500,
                true,
                1_000L,
                "test",
                1L,
                null,
                0
        ));

        ProxyBootstrapConfig config = ProxyBootstrapConfig.defaultConfig();
        config.fallbackServer = "lobby-1";
        config.requireHeartbeatForRouting = false;
        config.backendServers = List.of(
                new ProxyBootstrapConfig.TrackedBackendServer("lobby-1", "LOBBY", "lobby", 500, true)
        );

        ServerRoutingService routingService = new ServerRoutingService(
                proxyServer(Map.of("lobby-1", registeredServer("lobby-1"))),
                registry,
                config,
                null
        );

        assertTrue(routingService.selectBestTargetForQueue("survival", Set.of()).isEmpty());
        assertFalse(routingService.selectBestTargetForQueue("survival", Set.of()).isPresent());
    }

    private static ServerRoutingService.ServerRouteStatus status(
            String serverId,
            String role,
            boolean joinable,
            boolean registered,
            boolean fallbackEligible,
            boolean stale,
            int onlinePlayers,
            int maxPlayers,
            int softCapacity
    ) {
        double loadRatio = onlinePlayers / (double) Math.max(1, softCapacity);
        boolean overSoftCapacity = softCapacity > 0 && onlinePlayers >= softCapacity;
        return new ServerRoutingService.ServerRouteStatus(
                serverId,
                role,
                role.toLowerCase(java.util.Locale.ROOT),
                registered,
                true,
                fallbackEligible,
                joinable,
                stale,
                onlinePlayers,
                maxPlayers,
                softCapacity,
                loadRatio,
                stale ? 60_000L : 1_000L,
                overSoftCapacity,
                ServerLifecycleState.READY,
                0,
                0,
                0,
                0,
                joinable ? 60_000L : 0L
        );
    }

        private static ProxyServer proxyServer(Map<String, RegisteredServer> servers) {
                return (ProxyServer) Proxy.newProxyInstance(
                                ProxyServer.class.getClassLoader(),
                                new Class[]{ProxyServer.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                        case "getServer" -> Optional.ofNullable(servers.get((String) args[0]));
                                        case "getAllServers" -> servers.values();
                                        case "toString" -> "TestProxyServer";
                                        default -> defaultValue(method.getReturnType());
                                }
                );
        }

        private static RegisteredServer registeredServer(String name) {
                ServerInfo serverInfo = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
                return (RegisteredServer) Proxy.newProxyInstance(
                                RegisteredServer.class.getClassLoader(),
                                new Class[]{RegisteredServer.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                        case "getServerInfo" -> serverInfo;
                                        case "toString" -> name;
                                        default -> defaultValue(method.getReturnType());
                                }
                );
        }

        private static Object defaultValue(Class<?> returnType) {
                if (!returnType.isPrimitive()) {
                        return null;
                }
                if (returnType == boolean.class) {
                        return false;
                }
                if (returnType == char.class) {
                        return '\0';
                }
                if (returnType == byte.class) {
                        return (byte) 0;
                }
                if (returnType == short.class) {
                        return (short) 0;
                }
                if (returnType == int.class) {
                        return 0;
                }
                if (returnType == long.class) {
                        return 0L;
                }
                if (returnType == float.class) {
                        return 0F;
                }
                if (returnType == double.class) {
                        return 0D;
                }
                return null;
        }
}
