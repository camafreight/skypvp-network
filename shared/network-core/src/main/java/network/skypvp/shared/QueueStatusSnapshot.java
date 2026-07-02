package network.skypvp.shared;

import java.util.UUID;

/**
 * Snapshot of a single player's queue state as shown by proxy commands.
 */
public record QueueStatusSnapshot(
        UUID playerId,
        String username,
        String queueKey,
        int position,
        int queueSize,
        String bestTargetServerId
) {
}
