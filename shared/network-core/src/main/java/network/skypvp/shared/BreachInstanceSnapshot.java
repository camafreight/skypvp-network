package network.skypvp.shared;

/**
 * One joinable breach instance on an extraction pod, published in {@link ServerHeartbeatEvent} so the proxy can
 * route party queues to a specific instance across pods.
 */
public record BreachInstanceSnapshot(
        String instanceId,
        String mapId,
        int openSlots,
        int maxPlayers,
        boolean joinable,
        /** Party ids with at least one live raider in this instance (enables cross-pod rejoin routing). */
        java.util.List<String> activePartyIds
) {
    public BreachInstanceSnapshot(
            String instanceId,
            String mapId,
            int openSlots,
            int maxPlayers,
            boolean joinable
    ) {
        this(instanceId, mapId, openSlots, maxPlayers, joinable, java.util.List.of());
    }

    public java.util.List<String> activePartyIds() {
        return activePartyIds == null ? java.util.List.of() : activePartyIds;
    }
}
