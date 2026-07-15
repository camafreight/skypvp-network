package network.skypvp.shared;

import java.util.UUID;

/**
 * Published when a mid-raid disconnect creates or clears a disconnected raider's stand-in body on an extraction backend.
 * The proxy uses this to route reconnecting players straight back to the pod hosting their live session.
 */
public record BreachDisconnectedPresenceEvent(
        UUID playerId,
        String serverId,
        String instanceId,
        boolean present,
        long occurredAtEpochMillis
) {
}
