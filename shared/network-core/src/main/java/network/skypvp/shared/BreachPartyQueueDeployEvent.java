package network.skypvp.shared;

import java.util.List;
import java.util.UUID;

/** Proxy → extraction: reserve a specific breach instance for a queued party before members are transferred. */
public record BreachPartyQueueDeployEvent(
        UUID partyId,
        UUID leaderId,
        String targetServerId,
        String instanceId,
        List<UUID> memberIds,
        long occurredAtEpochMillis
) {
}
