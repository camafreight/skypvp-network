package network.skypvp.shared;

public record ServerDrainEvent(String serverId, long occurredAtEpochMillis) {
}
