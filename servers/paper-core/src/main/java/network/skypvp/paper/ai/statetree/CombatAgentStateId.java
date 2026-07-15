package network.skypvp.paper.ai.statetree;

/**
 * Generic combat-agent states reusable across gamemodes (gunners, melee mobs, NPCs).
 * Gamemode plugins register {@link StateTreeNode} implementations for the states they need.
 */
public enum CombatAgentStateId {
    /** Calm patrol / idle behavior. */
    IDLE,
    /** Ranged or primary-weapon engagement. */
    ENGAGE,
    /** Close-quarters combat. */
    MELEE,
    /** Moving to hard cover. */
    TAKE_COVER,
    /** Brief exposure from cover to shoot. */
    PEEK,
    /** Weapon reload. */
    RELOAD,
    /** Self-heal. */
    HEAL,
    /** Backup / sidearm weapon. */
    SECONDARY_WEAPON,
    /** Pursuing a last-known contact point. */
    PURSUE,
    /** Coordinated squad maneuver. */
    SQUAD_TACTIC,
    /** Breaking away from the squad to regroup solo. */
    SQUAD_LEAVE,
    /** Disengaging from combat. */
    RETREAT,
    /** Inspecting a downed target. */
    INSPECT
}
