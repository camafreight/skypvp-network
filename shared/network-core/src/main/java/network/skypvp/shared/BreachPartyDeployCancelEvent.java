package network.skypvp.shared;

import java.util.List;
import java.util.UUID;

/**
 * Proxy → extraction: abort a pending breach party deploy (member disconnected or left the party mid-transfer).
 * Releases held arrival slots and cancels pending joins so members are not force-admitted after they left.
 */
public record BreachPartyDeployCancelEvent(
        UUID partyId,
        String targetServerId,
        String instanceId,
        List<UUID> memberIds,
        String reason,
        long occurredAtEpochMillis
) {
}
