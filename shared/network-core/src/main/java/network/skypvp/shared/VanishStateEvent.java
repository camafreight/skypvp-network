package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#STAFF_VANISH} when a staff member's vanish state changes.
 * The proxy uses this to suppress network-wide join/quit broadcasts for vanished players.
 *
 * @param playerUuid  UUID as string
 * @param username    player username
 * @param vanished    {@code true} = now vanished, {@code false} = now visible
 */
public record VanishStateEvent(String playerUuid, String username, boolean vanished) {}
