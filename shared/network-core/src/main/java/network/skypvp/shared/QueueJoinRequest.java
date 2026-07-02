package network.skypvp.shared;

import java.util.UUID;

/**
 * Shared wire contract for a player requesting entry into a named queue.
 */
public record QueueJoinRequest(
        UUID playerId,
        String username,
        String queueKey,
        long occurredAtEpochMillis
) {
}
