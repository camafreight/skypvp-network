package network.skypvp.shared;

import java.time.Instant;
import java.util.UUID;

public record PlayerSessionSnapshot(
        UUID playerId,
        String username,
        String currentServerId,
        Instant connectedAt
) {
}
