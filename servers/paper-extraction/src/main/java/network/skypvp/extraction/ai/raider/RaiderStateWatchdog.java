package network.skypvp.extraction.ai.raider;

import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import org.bukkit.entity.LivingEntity;

/** Forces passive AI states to exit when they linger without making progress. */
final class RaiderStateWatchdog {

    private static final long RELOAD_MAX_DURATION_TICKS = 80L;
    private static final long SECONDARY_WEAPON_MAX_DURATION_TICKS = 140L;
    private static final long HEAL_EXTRA_GRACE_TICKS = 30L;
    private static final long TAKE_COVER_MAX_DURATION_TICKS = 120L;
    /** Must exceed CombatAgentTiming.PURSUE_TIMEOUT_TICKS so the node's own give-up runs first. */
    private static final long PURSUE_MAX_DURATION_TICKS = 220L;
    private static final long SQUAD_TACTIC_MAX_DURATION_TICKS = 150L;
    private static final long RETREAT_MAX_WATCHDOG_TICKS = 180L;
    private static final long INSPECT_MAX_DURATION_TICKS = 220L;

    private RaiderStateWatchdog() {
    }

    static CombatAgentStateId resolveStuck(
            RaiderAgentContext ctx,
            CombatAgentStateId state,
            LivingEntity target,
            long tick
    ) {
        long maxDuration = maxDurationTicks(state, ctx);
        if (maxDuration <= 0L || tick - ctx.stateEnteredTick < maxDuration) {
            if (ctx.navigation.stalled && isMovementState(state)) {
                if (state == CombatAgentStateId.PURSUE
                        && target != null
                        && RaiderThreatSupport.shouldContinuePursuit(ctx, target)) {
                    // Clearing the stall unconditionally here used to erase the evidence every
                    // time: a raider wedged at spawn (or chasing an unreachable-but-visible
                    // player) sprint-locked in place forever. Grant a few retries, then try a
                    // physical unstick, then give up WITH a pursuit block so the still-visible
                    // target can't re-trigger PURSUE on the very next tick.
                    ctx.navigation.stalled = false;
                    ctx.pursueStallStrikes++;
                    if (ctx.pursueStallStrikes <= RaiderAgentContext.PURSUE_STALL_MAX_STRIKES) {
                        return null;
                    }
                    ctx.pursueStallStrikes = 0;
                    if (RaiderMovementSupport.tryUnstickTeleport(ctx)) {
                        return null;
                    }
                }
                ctx.pursuitBlockedUntilTick = tick + RaiderAgentContext.PURSUIT_BLOCK_TICKS;
                return fallback(ctx, state, target, tick);
            }
            return null;
        }
        if (state == CombatAgentStateId.HEAL && tick < ctx.healCompleteTick) {
            return null;
        }
        return fallback(ctx, state, target, tick);
    }

    private static CombatAgentStateId fallback(
            RaiderAgentContext ctx,
            CombatAgentStateId state,
            LivingEntity target,
            long tick
    ) {
        if (target != null && target.isValid() && !target.isDead()) {
            CombatAgentStateId forced = RaiderCombatResolve.forceReengage(ctx, target, tick);
            if (forced != null
                    && forced != CombatAgentStateId.PURSUE
                    && forced != CombatAgentStateId.SQUAD_TACTIC) {
                if (forced == state) {
                    // The current state is still the right one. The engine ignores
                    // self-transitions, so returning it would make the node early-return
                    // on every tick forever; restart its clock and let it keep acting.
                    return renewLease(ctx, tick);
                }
                return forced;
            }
        }
        if (RaiderSightSupport.canInvestigate(ctx, tick)) {
            CombatAgentStateId pursuit = RaiderGroupTactics.preferredPursuitState(ctx);
            if (pursuit != state) {
                return pursuit;
            }
            // Pursuing again would re-enter the state we just gave up on; cool down
            // the intel so the raider disengages and returns to patrol instead.
            RaiderSightSupport.beginInvestigateCooldown(ctx, tick);
        }
        if (target != null) {
            ctx.mob.setTarget(null);
        }
        RaiderSightSupport.expireIntel(ctx);
        if (RaiderGroupTactics.shouldEnterSquadAbandon(ctx)
                && (state == CombatAgentStateId.RETREAT || state == CombatAgentStateId.PURSUE)) {
            return CombatAgentStateId.SQUAD_LEAVE;
        }
        return CombatAgentStateId.IDLE;
    }

    private static CombatAgentStateId renewLease(RaiderAgentContext ctx, long tick) {
        ctx.stateEnteredTick = tick;
        ctx.navigation.stalled = false;
        return null;
    }

    private static boolean isMovementState(CombatAgentStateId state) {
        return state == CombatAgentStateId.PURSUE
                || state == CombatAgentStateId.SQUAD_TACTIC
                || state == CombatAgentStateId.SQUAD_LEAVE
                || state == CombatAgentStateId.RETREAT
                || state == CombatAgentStateId.INSPECT
                || state == CombatAgentStateId.TAKE_COVER;
    }

    private static long maxDurationTicks(CombatAgentStateId state, RaiderAgentContext ctx) {
        return switch (state) {
            case HEAL -> ctx.profile.healTicks() + HEAL_EXTRA_GRACE_TICKS;
            case RELOAD -> RELOAD_MAX_DURATION_TICKS;
            case PEEK -> ctx.profile.coverBreakTicks() + 50L;
            case SECONDARY_WEAPON -> SECONDARY_WEAPON_MAX_DURATION_TICKS;
            case MELEE -> 160L;
            case ENGAGE -> 120L;
            case IDLE -> RaiderAgentContext.HOLD_REENGAGE_TICKS + 25L;
            case TAKE_COVER -> TAKE_COVER_MAX_DURATION_TICKS;
            case PURSUE -> PURSUE_MAX_DURATION_TICKS;
            case SQUAD_TACTIC -> SQUAD_TACTIC_MAX_DURATION_TICKS;
            case SQUAD_LEAVE -> RaiderAgentContext.GROUP_ABANDON_TIMEOUT_TICKS;
            case RETREAT -> RETREAT_MAX_WATCHDOG_TICKS;
            case INSPECT -> INSPECT_MAX_DURATION_TICKS;
            default -> 0L;
        };
    }
}
