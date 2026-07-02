package network.skypvp.shared;

import java.util.UUID;

public record PlayerSessionEvent(
        PlayerSessionAction action,
        UUID playerId,
        String username,
        String serverId,
        long occurredAtEpochMillis
) {
}
