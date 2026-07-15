package network.skypvp.paper.ai.statetree;

/** Shared tick budgets for generic combat-agent states. */
public final class CombatAgentTiming {

    public static final long LAST_KNOWN_TTL_TICKS = 200L;
    public static final long PURSUE_COOLDOWN_TICKS = 160L;
    /** Long enough for a full stair/route detour to an elevated last-known position. */
    public static final long PURSUE_TIMEOUT_TICKS = 180L;
    public static final long SQUAD_TACTIC_TIMEOUT_TICKS = 120L;
    public static final long IDLE_REENGAGE_TICKS = 30L;
    public static final long COVER_PROGRESS_STALL_TICKS = 25L;
    public static final long COVER_MAX_DURATION_TICKS = 90L;
    public static final long COVER_REASSIGN_COOLDOWN_TICKS = 20L;
    public static final long COVER_FAKE_TIMEOUT_TICKS = 35L;
    public static final long RETREAT_RESCAN_TICKS = 10L;
    public static final long RETREAT_WAYPOINT_STALL_TICKS = 18L;
    public static final long RETREAT_MAX_DURATION_TICKS = 160L;
    public static final int RETREAT_MAX_REPLANS = 3;
    public static final long SQUAD_LEAVE_TIMEOUT_TICKS = 120L;
    public static final long HEAL_GRACE_TICKS = 60L;
    public static final long UNDER_FIRE_WINDOW_TICKS = 60L;
    public static final long RETURN_FIRE_DELAY_TICKS = 18L;
    public static final long COVER_CACHE_TTL_TICKS = 12L;
    public static final long INSPECT_TICKS = 80L;
    public static final long INSPECT_TIMEOUT_TICKS = 200L;

    private CombatAgentTiming() {
    }
}
