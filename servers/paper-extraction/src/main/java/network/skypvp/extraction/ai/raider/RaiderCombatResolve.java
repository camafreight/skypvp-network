package network.skypvp.extraction.ai.raider;

import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import org.bukkit.entity.LivingEntity;

/** Picks the most aggressive valid combat transition for a gunner that has a live target. */
final class RaiderCombatResolve {

    private static final double ENGAGE_DISENGAGE_MULTIPLIER = 1.21D;
    private static final double SUPPRESS_DISENGAGE_MULTIPLIER = 1.45D;

    private RaiderCombatResolve() {
    }

    static double engageDisengageRangeSq(RaiderAgentContext ctx) {
        double multiplier = ctx.isSuppressAnchor() ? SUPPRESS_DISENGAGE_MULTIPLIER : ENGAGE_DISENGAGE_MULTIPLIER;
        return ctx.profile.engageRangeSq() * multiplier;
    }

    /** True while the gunner should keep shooting at a visible target instead of ducking into cover. */
    static boolean shouldMaintainGunfight(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (!ctx.canFightBack(tick)) {
            return false;
        }
        if (!RaiderSightSupport.canSeeTarget(ctx, target)) {
            return false;
        }
        double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
        if (ctx.isSuppressAnchor()) {
            return distanceSq <= engageDisengageRangeSq(ctx);
        }
        if (ctx.profile.isRifleDoctrine() && distanceSq <= ctx.profile.engageRangeSq()) {
            return true;
        }
        return distanceSq <= engageDisengageRangeSq(ctx);
    }

    /** In-place reload/sidearm swap while still facing a visible threat. */
    static CombatAgentStateId reloadDuringGunfight(RaiderAgentContext ctx, long tick) {
        if (ctx.shouldSwapSidearm(tick)) {
            return CombatAgentStateId.SECONDARY_WEAPON;
        }
        if (ctx.canReloadSidearm()) {
            return CombatAgentStateId.RELOAD;
        }
        if (ctx.canReloadPrimary()) {
            return CombatAgentStateId.RELOAD;
        }
        if (!ctx.isPistolPrimary()) {
            return CombatAgentStateId.SECONDARY_WEAPON;
        }
        return null;
    }

    static CombatAgentStateId resolve(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (target == null || !target.isValid() || target.isDead()) {
            return null;
        }
        // Movement toward this area recently failed (stall watchdog) — hold position instead of
        // instantly re-entering PURSUE and sprint-locking against the same obstacle again.
        boolean pursuitBlocked = tick < ctx.pursuitBlockedUntilTick;
        if (ctx.profile.isKnifeRusher()) {
            if (shouldEnterMelee(ctx, target)) {
                return CombatAgentStateId.MELEE;
            }
            return pursuitBlocked ? null : CombatAgentStateId.PURSUE;
        }
        if (shouldEnterMelee(ctx, target)) {
            return CombatAgentStateId.MELEE;
        }
        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
            if (shouldEnterMelee(ctx, target)) {
                return CombatAgentStateId.MELEE;
            }
            if (ctx.canFightBack(tick)) {
                if (RaiderSightSupport.withinEngageRange(ctx, target)) {
                    return resumeEngage(ctx, tick);
                }
            }
            if (RaiderSightSupport.withinEngageRange(ctx, target)) {
                return CombatAgentStateId.ENGAGE;
            }
            if (ctx.profile.prefersCloseCombat() && !ctx.inSquad() && !pursuitBlocked) {
                return CombatAgentStateId.PURSUE;
            }
        }
        if (!pursuitBlocked && RaiderSightSupport.canInvestigate(ctx, tick)) {
            return RaiderGroupTactics.preferredPursuitState(ctx);
        }
        return null;
    }

    static CombatAgentStateId forceReengage(RaiderAgentContext ctx, LivingEntity target, long tick) {
        CombatAgentStateId resolved = resolve(ctx, target, tick);
        if (resolved != null) {
            return resolved;
        }
        if (RaiderSightSupport.canSeeTarget(ctx, target) && ctx.canFightBack(tick)) {
            return resumeEngage(ctx, tick);
        }
        return null;
    }

    /**
     * Melee only with clear LOS and knife reach. Too-close / BREACH / solo doctrine may chase,
     * but never knife through walls.
     */
    static boolean shouldEnterMelee(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (ctx.profile.isKnifeRusher()) {
            // Rushers sprint in until knife range; MELEE only when close enough to strike/chase-in.
            if (RaiderSightSupport.canSeeTarget(ctx, target)
                    && RaiderSightSupport.sameFloorProximity(ctx, target)) {
                double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
                return distanceSq <= ctx.profile.preferredCloseRangeSq();
            }
            return false;
        }
        // Hard gate: no knife without LOS + melee reach.
        if (RaiderSightSupport.canInitiateMelee(ctx, target)) {
            return true;
        }
        // Visible but slightly outside knife reach while BREACH / solo close-combat: still allow
        // melee state so they chase into knife range (strike itself still requires LOS).
        if (!RaiderSightSupport.canSeeTarget(ctx, target)) {
            return false;
        }
        if (!RaiderSightSupport.sameFloorProximity(ctx, target)) {
            return false;
        }
        double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
        if (ctx.groupRole == RaiderGroupRole.BREACH
                && distanceSq <= ctx.profile.preferredCloseRangeSq()
                && RaiderSightSupport.withinStrikeRange(ctx, target)) {
            return true;
        }
        if (!ctx.inSquad() && ctx.profile.prefersCloseCombat()
                && ctx.profile.shouldPushToMeleeWhenSolo(distanceSq)
                && RaiderSightSupport.withinStrikeRange(ctx, target)) {
            return true;
        }
        return false;
    }

    private static CombatAgentStateId resumeEngage(RaiderAgentContext ctx, long tick) {
        LivingEntity target = ctx.mob.getTarget();
        if (shouldEnterMelee(ctx, target)) {
            return CombatAgentStateId.MELEE;
        }
        if (ctx.needsReload()) {
            if (ctx.shouldSwapSidearm(tick)) {
                return CombatAgentStateId.SECONDARY_WEAPON;
            }
            if (ctx.canReloadPrimary() || ctx.canReloadSidearm()) {
                CombatAgentStateId reload = reloadDuringGunfight(ctx, tick);
                if (reload != null) {
                    return reload;
                }
            }
            if (ctx.shouldSeekCover(tick) && !ctx.isSuppressAnchor()) {
                if (RaiderCoverSupport.findRealCover(ctx, target) != null) {
                    return CombatAgentStateId.TAKE_COVER;
                }
            }
            if (!ctx.isPistolPrimary()) {
                return CombatAgentStateId.SECONDARY_WEAPON;
            }
            if (shouldEnterMelee(ctx, target)) {
                return CombatAgentStateId.MELEE;
            }
        }
        return CombatAgentStateId.ENGAGE;
    }
}
