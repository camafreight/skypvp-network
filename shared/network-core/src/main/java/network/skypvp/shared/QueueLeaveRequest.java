package network.skypvp.shared;

import java.util.UUID;

/**
 * Shared wire contract for a player leaving an existing queue.
 */
public record QueueLeaveRequest(
        UUID playerId,
        String queueKey,
        long occurredAtEpochMillis
) {
}
