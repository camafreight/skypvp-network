package network.skypvp.extraction.ai.raider;

/**
 * Loadout doctrine for Ruins gunners. Drives solo close-combat vs rifle hold behavior
 * and how aggressively the state tree prefers MELEE over ENGAGE.
 */
public enum RaiderCombatStyle {
    /** Hold mid/long range, peek/cover, knife only when forced. */
    RIFLE,
    /** Push mid-close, swap to knife sooner when solo. */
    CLOSE_ASSAULT,
    /** Room-clear: close distance and knife/shotgun pressure. */
    BREACHER,
    /** No gun — sprint into knife range and eliminate. */
    KNIFE_RUSHER
}
