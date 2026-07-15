package network.skypvp.shared;

import java.util.UUID;

/**
 * Published when an offline raider was eliminated mid-raid (for example their AFK body was killed) but the breach
 * instance is still active. The proxy uses this to route their next login back to the hosting extraction pod as a
 * spectator rather than the lobby.
 */
public record BreachSpectatorPresenceEvent(
        UUID playerId,
        String serverId,
        String instanceId,
        boolean present,
        long occurredAtEpochMillis
) {
}
