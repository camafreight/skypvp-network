package network.skypvp.shared;

/**
 * Immutable snapshot of a rank's display metadata. Shared between Paper backends and the proxy.
 */
public record RankRecord(
        String rankKey,
        String displayName,
        String prefix,
        String chatColor,
        int priority
) {

    public static final RankRecord DEFAULT = new RankRecord("default", "Player", "", "white", 0);
}
