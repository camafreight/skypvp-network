package network.skypvp.extraction.ai.raider;

import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Target freshness, nearby threat scans, and passive-state interruption when shot. */
final class RaiderThreatSupport {

    private static final double NEARBY_SCAN_RADIUS_SQ = 48.0D * 48.0D;
    private static final long STALE_UNSEEN_TICKS = 80L;
    private static final long RECENT_COMBAT_TICKS = 60L;

    private RaiderThreatSupport() {
    }

    /**
     * Each tick: drop targets that are still valid breach players but no longer plausible threats,
     * and upgrade to a closer/damager threat when under fire.
     */
    static void refreshTargetPriority(RaiderAgentContext ctx, long tick) {
        if (ctx == null || ctx.mob == null || ctx.playerTargetGate == null) {
            return;
        }
        Player upgrade = preferredThreat(ctx, tick);
        if (upgrade != null) {
            LivingEntity current = ctx.mob.getTarget();
            if (!(current instanceof Player existing) || !existing.getUniqueId().equals(upgrade.getUniqueId())) {
                RaiderCombatTargets.assign(ctx.mob, upgrade, ctx.playerTargetGate);
                RaiderSightSupport.observeTarget(ctx, upgrade);
            }
            return;
        }
        LivingEntity current = ctx.mob.getTarget();
        if (current instanceof Player player && isStaleTarget(ctx, player, tick)) {
            ctx.mob.setTarget(null);
            if (player.getUniqueId().equals(ctx.lastCombatPlayerId)) {
                ctx.lastCombatPlayerId = null;
                ctx.lastCombatPlayerTick = 0L;
            }
            RaiderSightSupport.expireIntel(ctx);
        }
    }

    /** True when the target is out of reach and has not been seen recently. */
    static boolean isStaleTarget(RaiderAgentContext ctx, LivingEntity target, long tick) {
        if (target == null || !target.isValid() || target.isDead()) {
            return true;
        }
        if (target instanceof Player player
                && ctx.playerTargetGate != null
                && !ctx.playerTargetGate.allows(player)) {
            return true;
        }
        if (!sameWorld(ctx.entity, target)) {
            return true;
        }
        if (ctx.underFire(tick)) {
            return false;
        }
        if (ctx.lastCombatPlayerId != null
                && target instanceof Player player
                && player.getUniqueId().equals(ctx.lastCombatPlayerId)
                && tick - ctx.lastCombatPlayerTick <= RECENT_COMBAT_TICKS) {
            return false;
        }
        if (ctx.knifeEquipped
                && RaiderSightSupport.withinHorizontalMeleeReach(
                        ctx,
                        target,
                        RaiderCombatResolve.engageDisengageRangeSq(ctx))) {
            return false;
        }
        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
            return false;
        }
        if (!sameWorld(ctx.entity, target)) {
            return true;
        }
        double rangeSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
        if (rangeSq <= RaiderCombatResolve.engageDisengageRangeSq(ctx)
                && tick - ctx.lastSeenTargetTick <= STALE_UNSEEN_TICKS) {
            return false;
        }
        if (shouldContinuePursuit(ctx, target)) {
            return false;
        }
        return tick - ctx.lastSeenTargetTick > STALE_UNSEEN_TICKS;
    }

    /** Resolves a combat transition when patrol/hunt should react to damage or nearby threats. */
    static CombatAgentStateId resolvePassiveThreat(RaiderAgentContext ctx, long tick) {
        Player adjacent = findAdjacentThreat(ctx);
        if (adjacent != null) {
            RaiderCombatTargets.assign(ctx.mob, adjacent, ctx.playerTargetGate);
            CombatAgentStateId combat = RaiderCombatResolve.resolve(ctx, adjacent, tick);
            if (combat != null) {
                return combat;
            }
        }
        Player threat = preferredThreat(ctx, tick);
        if (threat != null) {
            RaiderCombatTargets.assign(ctx.mob, threat, ctx.playerTargetGate);
            CombatAgentStateId combat = RaiderCombatResolve.resolve(ctx, threat, tick);
            if (combat != null) {
                return combat;
            }
            // Respect the pursuit block: after a movement stall gave up on this area,
            // chasing again immediately just re-enters the sprint-in-place loop.
            if (tick >= ctx.pursuitBlockedUntilTick) {
                return RaiderGroupTactics.preferredPursuitState(ctx);
            }
            return null;
        }
        if (ctx.underFire(tick)
                && RaiderSightSupport.canInvestigate(ctx, tick)
                && tick >= ctx.pursuitBlockedUntilTick) {
            return CombatAgentStateId.PURSUE;
        }
        return null;
    }

    /** Player in strike range even without line-of-sight (corner hugging). */
    static Player findAdjacentThreat(RaiderAgentContext ctx) {
        if (ctx.playerTargetGate == null) {
            return null;
        }
        for (Player player : ctx.entity.getWorld().getPlayers()) {
            if (!ctx.playerTargetGate.allows(player)) {
                continue;
            }
            if (RaiderSightSupport.withinStrikeRange(ctx, player)) {
                return player;
            }
        }
        return null;
    }

    /** True when the gunner should keep closing distance instead of ending pursuit. */
    static boolean shouldContinuePursuit(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (!sameWorld(ctx.entity, target)) {
            return false;
        }
        if (RaiderSightSupport.canSeeTarget(ctx, target)) {
            return true;
        }
        long tick = ctx.aiTick;
        if (!RaiderSightSupport.hasFreshLastKnown(ctx, tick)) {
            return false;
        }
        Location lastKnown = ctx.lastKnownTargetLocation;
        if (lastKnown == null || lastKnown.getWorld() == null) {
            return false;
        }
        Location feet = ctx.entity.getLocation();
        if (!lastKnown.getWorld().equals(feet.getWorld())) {
            return false;
        }
        double horizontalSq = horizontalDistanceSq(feet, lastKnown);
        double verticalDelta = Math.abs(lastKnown.getY() - feet.getY());
        return horizontalSq > RaiderAgentContext.PURSUE_ARRIVE_SQ || verticalDelta > 2.0D;
    }

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    static boolean intelMoved(RaiderAgentContext ctx) {
        Location nav = ctx.navigation.lastDestination;
        LivingEntity target = ctx.mob.getTarget();
        if (target != null
                && target.isValid()
                && !target.isDead()
                && RaiderSightSupport.canSeeTarget(ctx, target)) {
            Location live = target.getLocation();
            if (nav == null) {
                return true;
            }
            if (!live.getWorld().equals(nav.getWorld())) {
                return true;
            }
            // Require meaningful movement (not every block-edge jitter / jump) before replanning.
            return horizontalDistanceSq(live, nav) >= 2.25D
                    || Math.abs(live.getY() - nav.getY()) >= 2.0D;
        }
        Location lastKnown = ctx.lastKnownTargetLocation;
        if (lastKnown == null) {
            return nav != null;
        }
        if (nav == null) {
            return true;
        }
        if (!lastKnown.getWorld().equals(nav.getWorld())) {
            return true;
        }
        return horizontalDistanceSq(lastKnown, nav) >= 2.25D
                || Math.abs(lastKnown.getY() - nav.getY()) >= 2.0D;
    }

    private static Player preferredThreat(RaiderAgentContext ctx, long tick) {
        Player adjacent = findAdjacentThreat(ctx);
        if (adjacent != null) {
            return adjacent;
        }
        Player visible = findNearestVisibleThreat(ctx, NEARBY_SCAN_RADIUS_SQ);
        if (visible != null) {
            return visible;
        }
        if (ctx.underFire(tick)) {
            Player recent = recentCombatPlayer(ctx, tick);
            if (recent != null) {
                return recent;
            }
            Player nearest = findNearestVisibleThreat(ctx, NEARBY_SCAN_RADIUS_SQ);
            if (nearest != null) {
                return nearest;
            }
        }
        LivingEntity current = ctx.mob.getTarget();
        if (current instanceof Player player
                && ctx.playerTargetGate.allows(player)
                && !isStaleTarget(ctx, player, tick)) {
            return player;
        }
        return null;
    }

    private static Player recentCombatPlayer(RaiderAgentContext ctx, long tick) {
        if (ctx.lastCombatPlayerId == null || ctx.playerTargetGate == null) {
            return null;
        }
        if (tick - ctx.lastCombatPlayerTick > RECENT_COMBAT_TICKS) {
            return null;
        }
        Player player = ctx.entity.getServer().getPlayer(ctx.lastCombatPlayerId);
        if (player == null || !ctx.playerTargetGate.allows(player)) {
            return null;
        }
        return player;
    }

    private static Player findNearestVisibleThreat(RaiderAgentContext ctx, double maxRadiusSq) {
        if (ctx.playerTargetGate == null) {
            return null;
        }
        Player best = null;
        double bestSq = maxRadiusSq;
        World agentWorld = ctx.entity.getWorld();
        for (Player player : agentWorld.getPlayers()) {
            if (!ctx.playerTargetGate.allows(player)) {
                continue;
            }
            if (!player.getWorld().equals(agentWorld)) {
                continue;
            }
            double sq = ctx.entity.getLocation().distanceSquared(player.getLocation());
            if (sq >= bestSq) {
                continue;
            }
            if (!RaiderSightSupport.canAcquireTarget(ctx, player)) {
                continue;
            }
            best = player;
            bestSq = sq;
        }
        return best;
    }

    private static boolean sameWorld(LivingEntity left, LivingEntity right) {
        if (left == null || right == null) {
            return false;
        }
        World leftWorld = left.getWorld();
        World rightWorld = right.getWorld();
        return leftWorld != null && leftWorld.equals(rightWorld);
    }
}
