package network.skypvp.shared;

public record ServerHeartbeatEvent(
        String serverId,
        NetworkServerRole role,
        int onlinePlayers,
        int maxPlayers,
        boolean joinable,
        long occurredAtEpochMillis,
        String orchestratorSource,
    long orchestrationGeneration,
    String advertisedHost,
    int advertisedPort,
    int openBreachSlots,
    int activeBreaches,
    int queuedPlayers,
    int maxPlayersPerPod
) {
    public ServerHeartbeatEvent(
            String serverId,
            NetworkServerRole role,
            int onlinePlayers,
            int maxPlayers,
            boolean joinable,
            long occurredAtEpochMillis
    ) {
    this(serverId, role, onlinePlayers, maxPlayers, joinable, occurredAtEpochMillis, null, 0L, null, 0, 0, 0, 0, 0);
    }

    public ServerHeartbeatEvent(
            String serverId,
            NetworkServerRole role,
            int onlinePlayers,
            int maxPlayers,
            boolean joinable,
            long occurredAtEpochMillis,
            String orchestratorSource,
            long orchestrationGeneration,
            String advertisedHost,
            int advertisedPort
    ) {
        this(serverId, role, onlinePlayers, maxPlayers, joinable, occurredAtEpochMillis, orchestratorSource,
            orchestrationGeneration, advertisedHost, advertisedPort, 0, 0, 0, 0);
    }
}
