package network.skypvp.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.junit.jupiter.api.Test;

class NetworkStateRegistryTest {

    @Test
    void rejectsOlderGenerationHeartbeat() {
        NetworkStateRegistry registry = new NetworkStateRegistry();

        registry.applyHeartbeat(new ServerHeartbeatEvent(
                "minigame-1",
                NetworkServerRole.MINIGAME,
                12,
                250,
                true,
                1_000L,
                "k8s",
                5L,
                null,
                0
        ));

        registry.applyHeartbeat(new ServerHeartbeatEvent(
                "minigame-1",
                NetworkServerRole.MINIGAME,
                1,
                250,
                false,
                2_000L,
                "k8s",
                4L,
                null,
                0
        ));

        ServerHeartbeatEvent selected = registry.heartbeatFor("minigame-1").orElseThrow();
        assertEquals(5L, selected.orchestrationGeneration());
        assertEquals(12, selected.onlinePlayers());
        assertTrue(selected.joinable());
    }

    @Test
    void rejectsOlderTimestampWithinSameGeneration() {
        NetworkStateRegistry registry = new NetworkStateRegistry();

        registry.applyHeartbeat(new ServerHeartbeatEvent(
                "lobby-1",
                NetworkServerRole.LOBBY,
                30,
                500,
                true,
                10_000L,
                "k8s",
                7L,
                null,
                0
        ));

        registry.applyHeartbeat(new ServerHeartbeatEvent(
                "lobby-1",
                NetworkServerRole.LOBBY,
                0,
                500,
                false,
                9_000L,
                "k8s",
                7L,
                null,
                0
        ));

        ServerHeartbeatEvent selected = registry.heartbeatFor("lobby-1").orElseThrow();
        assertEquals(7L, selected.orchestrationGeneration());
        assertEquals(10_000L, selected.occurredAtEpochMillis());
        assertEquals(30, selected.onlinePlayers());
    }
}
