package network.skypvp.paper.hud;

/** Visual style used while {@link ScoreboardRotation} swaps sidebar pages. */
public enum ScoreboardTransitionType {

    /** Instant swap — transition duration is ignored. */
    NONE,

    /** Lines appear or disappear sequentially from top to bottom. */
    REVEAL,

    /** Lines slide vertically through the sidebar viewport. */
    SCROLL
}
