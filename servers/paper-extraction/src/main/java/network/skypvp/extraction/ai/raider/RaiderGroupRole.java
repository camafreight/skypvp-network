package network.skypvp.extraction.ai.raider;

/** Squad role assigned when gunners form a nearby group. */
public enum RaiderGroupRole {
    SOLO,
    /** Holds the line and keeps pressure on the target. */
    SUPPRESS,
    /** Pushes through cover on the left arc. */
    FLANK_LEFT,
    /** Pushes through cover on the right arc. */
    FLANK_RIGHT,
    /** Closes distance for a melee breach once flanks are moving. */
    BREACH;

    public boolean isFlanker() {
        return this == FLANK_LEFT || this == FLANK_RIGHT;
    }

    public boolean coordinates() {
        return this != SOLO && this != SUPPRESS;
    }
}
