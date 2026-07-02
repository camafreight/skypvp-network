package network.skypvp.shared;

import java.util.UUID;

/**
 * Shared wire contract for a queued player being allocated to a destination server.
 */
public record QueueTransferEvent(
        UUID playerId,
        String username,
        String queueKey,
        String destinationServerId,
        long occurredAtEpochMillis
) {
}
