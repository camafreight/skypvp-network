package network.skypvp.extraction.integration;

import java.util.UUID;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.BreachDisconnectedPresenceEvent;
import network.skypvp.shared.BreachSpectatorPresenceEvent;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.RedisEventPublisher;

/** Publishes mid-raid disconnected/spectator reconnect hints to the network proxy. */
public final class BreachDisconnectedPresenceBridge {

    private final PaperCorePlugin core;
    private final Logger logger;

    public BreachDisconnectedPresenceBridge(PaperCorePlugin core, Logger logger) {
        this.core = core;
        this.logger = logger;
    }

    public void publishPresent(UUID playerId, String instanceId) {
        this.publishDisconnected(playerId, instanceId, true);
    }

    public void publishCleared(UUID playerId) {
        this.publishDisconnected(playerId, null, false);
    }

    public void publishSpectatorPresent(UUID playerId, String instanceId) {
        this.publishSpectator(playerId, instanceId, true);
    }

    public void publishSpectatorCleared(UUID playerId) {
        this.publishSpectator(playerId, null, false);
    }

    private void publishDisconnected(UUID playerId, String instanceId, boolean present) {
        if (playerId == null || this.core == null) {
            return;
        }
        RedisEventPublisher publisher = this.core.redisPublisher();
        if (publisher == null) {
            return;
        }
        String serverId = this.core.serverId();
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        try {
            publisher.publishJson(
                    NetworkChannels.BREACH_DISCONNECTED_PRESENCE,
                    new BreachDisconnectedPresenceEvent(playerId, serverId, instanceId, present, System.currentTimeMillis()));
        } catch (RuntimeException exception) {
            this.logger.warning("[BreachDisconnected] Failed to publish disconnected presence for " + playerId + ": " + exception.getMessage());
        }
    }

    private void publishSpectator(UUID playerId, String instanceId, boolean present) {
        if (playerId == null || this.core == null) {
            return;
        }
        RedisEventPublisher publisher = this.core.redisPublisher();
        if (publisher == null) {
            return;
        }
        String serverId = this.core.serverId();
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        try {
            publisher.publishJson(
                    NetworkChannels.BREACH_SPECTATOR_PRESENCE,
                    new BreachSpectatorPresenceEvent(playerId, serverId, instanceId, present, System.currentTimeMillis()));
        } catch (RuntimeException exception) {
            this.logger.warning("[BreachDisconnected] Failed to publish spectator presence for " + playerId + ": " + exception.getMessage());
        }
    }
}
