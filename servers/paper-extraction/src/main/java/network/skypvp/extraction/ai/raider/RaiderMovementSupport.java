package network.skypvp.extraction.ai.raider;

import network.skypvp.paper.ai.navigation.MobNavigationSupport;
import network.skypvp.paper.integration.LivingEntitySprintBridge;
import org.bukkit.Location;

/** Pathfinder navigation + real sprint for gunner cover runs and investigate moves. */
final class RaiderMovementSupport {

    private static final double COVER_NAV_SPEED = 1.35D;
    private static final double INVESTIGATE_NAV_SPEED = 1.15D;
    /** Cadence for investigate replans — spark showed force-refresh bypassing this as the hot A* path. */
    private static final long NAV_REFRESH_TICKS = 12L;

    private RaiderMovementSupport() {
    }

    static void beginCoverRun(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, true);
        }
        navigateTo(ctx, ctx.coverPoint, COVER_NAV_SPEED);
    }

    static void tickCoverRun(RaiderAgentContext ctx) {
        if (tickVerticalAccess(ctx, ctx.coverPoint, COVER_NAV_SPEED)) {
            return;
        }
        tickNavigation(ctx, ctx.coverPoint, COVER_NAV_SPEED);
    }

    static void beginInvestigate(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, true);
        }
        ctx.pursueStallStrikes = 0;
        navigateInvestigate(ctx, INVESTIGATE_NAV_SPEED);
    }

    /**
     * Teleport-nudges a physically wedged raider (spawned into a block/fence lip) onto the
     * nearest standable column. Returns false when the raider already has valid footing —
     * then the problem is an unreachable goal, not the body, and the caller should give up
     * the pursuit instead.
     */
    static boolean tryUnstickTeleport(RaiderAgentContext ctx) {
        Location feet = ctx.entity.getLocation();
        Location freed = network.skypvp.paper.ai.navigation.MobTerrainSupport.snapToStandableNear(feet, 3);
        if (freed == null || freed.getWorld() == null || freed.distanceSquared(feet) < 1.0D) {
            return false;
        }
        freed.setYaw(feet.getYaw());
        freed.setPitch(feet.getPitch());
        ctx.mob.getPathfinder().stopPathfinding();
        MobNavigationSupport.resetProgress(ctx);
        ctx.entity.teleportAsync(freed);
        return true;
    }

    static void tickInvestigate(RaiderAgentContext ctx) {
        org.bukkit.entity.LivingEntity target = ctx.mob.getTarget();
        boolean canSee = target != null
                && target.isValid()
                && !target.isDead()
                && RaiderSightSupport.canSeeTarget(ctx, target);
        Location goal = ctx.lastKnownTargetLocation;
        if (canSee) {
            goal = target.getLocation();
            RaiderSightSupport.observeTarget(ctx, target);
        }
        if (goal == null) {
            return;
        }
        Location feet = ctx.entity.getLocation();
        if (tickVerticalAccess(ctx, goal, INVESTIGATE_NAV_SPEED)) {
            return;
        }
        if (target != null && target.isValid() && !target.isDead()
                && RaiderThreatSupport.shouldContinuePursuit(ctx, target)) {
            ctx.navigation.stalled = false;
            ctx.navigation.lastProgressTick = ctx.aiTick;
        }
        if (MobNavigationSupport.consumeStall(ctx)) {
            long tick = ctx.aiTick;
            if (target != null && target.isValid() && !target.isDead()) {
                if (RaiderSightSupport.isSeparatedVertically(ctx, target)
                        && !RaiderSightSupport.canSeeTarget(ctx, target)) {
                    ctx.navigation.stalled = true;
                }
                forceRefreshInvestigateNavigation(ctx);
                return;
            }
            if (!ctx.underFire(tick)) {
                ctx.lastKnownTargetLocation = null;
            }
            return;
        }
        if (RaiderThreatSupport.intelMoved(ctx)) {
            refreshInvestigateNavigation(ctx);
            return;
        }
        tickInvestigateNavigation(ctx, INVESTIGATE_NAV_SPEED);
    }

    /**
     * Soft refresh: respect {@link #NAV_REFRESH_TICKS} so under-fire intel jitter cannot A* every tick.
     */
    static void refreshInvestigateNavigation(RaiderAgentContext ctx) {
        long tick = ctx.aiTick;
        if (tick - ctx.navigation.lastNavTick < NAV_REFRESH_TICKS) {
            return;
        }
        ctx.navigation.clearRoute();
        navigateInvestigate(ctx, INVESTIGATE_NAV_SPEED);
    }

    /** Stall recovery may force a replan immediately. */
    static void forceRefreshInvestigateNavigation(RaiderAgentContext ctx) {
        ctx.navigation.lastNavTick = 0L;
        ctx.navigation.clearRoute();
        navigateInvestigate(ctx, INVESTIGATE_NAV_SPEED);
    }

    static void beginGroupTactic(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, true);
        }
        navigateTo(ctx, ctx.groupTacticPoint, COVER_NAV_SPEED);
    }

    static void tickGroupTactic(RaiderAgentContext ctx) {
        if (tickVerticalAccess(ctx, ctx.groupTacticPoint, COVER_NAV_SPEED)) {
            return;
        }
        if (MobNavigationSupport.consumeStall(ctx)) {
            ctx.groupTacticPoint = null;
            ctx.groupTacticAssignedTick = 0L;
            return;
        }
        tickNavigation(ctx, ctx.groupTacticPoint, COVER_NAV_SPEED);
    }

    static void beginRetreat(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, true);
        }
        navigateTo(ctx, ctx.retreatPoint, COVER_NAV_SPEED);
    }

    static void tickRetreat(RaiderAgentContext ctx) {
        if (tickVerticalAccess(ctx, ctx.retreatPoint, COVER_NAV_SPEED)) {
            return;
        }
        tickNavigation(ctx, ctx.retreatPoint, COVER_NAV_SPEED);
    }

    static void beginInspect(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, false);
        }
        navigateTo(ctx, ctx.inspectPoint, MobNavigationSupport.PATROL_WALK_SPEED);
    }

    static void tickInspect(RaiderAgentContext ctx) {
        if (MobNavigationSupport.consumeStall(ctx)) {
            ctx.inspectPoint = null;
            return;
        }
        tickNavigation(ctx, ctx.inspectPoint, MobNavigationSupport.PATROL_WALK_SPEED);
    }

    static void endNavigation(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, false);
        }
        network.skypvp.paper.ai.navigation.MobLadderSupport.endClimb(ctx, ctx.entity);
        ctx.mob.getPathfinder().stopPathfinding();
        ctx.navigation.lastNavTick = 0L;
        MobNavigationSupport.resetProgress(ctx);
    }

    static void endCoverRun(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        endNavigation(ctx, sprintBridge);
    }

    static void beginGroupAbandon(RaiderAgentContext ctx, LivingEntitySprintBridge sprintBridge) {
        if (sprintBridge != null) {
            sprintBridge.setSprinting(ctx.entity, false);
        }
        Location origin = ctx.patrolOrigin != null ? ctx.patrolOrigin : ctx.entity.getLocation();
        navigateTo(ctx, origin, MobNavigationSupport.PATROL_WALK_SPEED);
    }

    static void tickGroupAbandon(RaiderAgentContext ctx) {
        if (MobNavigationSupport.consumeStall(ctx)) {
            return;
        }
        long tick = ctx.aiTick;
        if (tick - ctx.navigation.lastNavTick >= NAV_REFRESH_TICKS) {
            Location origin = ctx.patrolOrigin != null ? ctx.patrolOrigin : ctx.entity.getLocation();
            navigateTo(ctx, origin, MobNavigationSupport.PATROL_WALK_SPEED);
        }
    }

    private static void tickNavigation(RaiderAgentContext ctx, Location destination, double speed) {
        long tick = ctx.aiTick;
        if (tick - ctx.navigation.lastNavTick >= NAV_REFRESH_TICKS) {
            navigateTo(ctx, destination, speed);
        }
    }

    /**
     * Shared ladder/stair ascent for any elevated goal. Returns true when vertical movement owns the tick.
     */
    private static boolean tickVerticalAccess(RaiderAgentContext ctx, Location goal, double speed) {
        if (goal == null) {
            network.skypvp.paper.ai.navigation.MobLadderSupport.endClimb(ctx, ctx.entity);
            ctx.navigation.climbingStairs = false;
            return false;
        }
        Location feet = ctx.entity.getLocation();
        boolean needsVerticalAccess = goal.getY() > feet.getY() + 2.0D;
        if (needsVerticalAccess) {
            // A complete walkable route (around and up the stairs) always beats the
            // manual ladder/stair assists.
            if (MobNavigationSupport.tryFullVanillaRoute(ctx, goal, speed)) {
                return true;
            }
            network.skypvp.paper.ai.navigation.MobLadderSupport.tickOverheadClimb(ctx, goal);
            if (!ctx.navigation.climbingLadder) {
                network.skypvp.paper.ai.navigation.MobStairSupport.tickOverheadAscent(ctx, goal);
            }
        } else if (ctx.navigation.climbingLadder || ctx.navigation.climbingStairs) {
            network.skypvp.paper.ai.navigation.MobLadderSupport.endClimb(ctx, ctx.entity);
            ctx.navigation.climbingStairs = false;
        }
        if (ctx.navigation.climbingLadder
                && network.skypvp.paper.ai.navigation.MobLadderSupport.shouldExitForHorizontalGoal(ctx, goal)) {
            network.skypvp.paper.ai.navigation.MobLadderSupport.endClimb(ctx, ctx.entity);
            ctx.mob.getPathfinder().stopPathfinding();
        }
        if (ctx.navigation.climbingStairs
                && network.skypvp.paper.ai.navigation.MobStairSupport.shouldExitForHorizontalGoal(ctx, goal)) {
            ctx.navigation.climbingStairs = false;
            ctx.mob.getPathfinder().stopPathfinding();
        }
        return ctx.navigation.climbingLadder || ctx.navigation.climbingStairs;
    }

    private static void tickInvestigateNavigation(RaiderAgentContext ctx, double speed) {
        long tick = ctx.aiTick;
        if (tick - ctx.navigation.lastNavTick >= NAV_REFRESH_TICKS) {
            navigateInvestigate(ctx, speed);
        }
    }

    private static void navigateInvestigate(RaiderAgentContext ctx, double speed) {
        org.bukkit.entity.LivingEntity target = ctx.mob.getTarget();
        if (target != null
                && target.isValid()
                && !target.isDead()
                && RaiderSightSupport.canSeeTarget(ctx, target)) {
            if (MobNavigationSupport.navigateToEntity(ctx, target, speed)) {
                return;
            }
        }
        navigateTo(ctx, ctx.lastKnownTargetLocation, speed);
    }

    private static void navigateTo(RaiderAgentContext ctx, Location destination, double speed) {
        MobNavigationSupport.navigateTo(ctx, destination, speed);
    }
}
