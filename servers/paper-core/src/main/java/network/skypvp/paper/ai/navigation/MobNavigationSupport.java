package network.skypvp.paper.ai.navigation;

import com.destroystokyo.paper.entity.Pathfinder;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/** Shared pathfinder helpers with ground snapping and stall detection. */
public final class MobNavigationSupport {

    public static final double PATROL_WALK_SPEED = 1.0D;
    /**
     * Effective ground-speed ceiling (attribute value × path multiplier, mob-attribute scale) ≈ a sprinting
     * player (~5.6 m/s ≈ 0.31). AI must read as players: chase at sprint pace, never outrun one. Reading the
     * LIVE attribute value folds the vanilla +30% sprint-flag modifier and any gear modifiers INTO the budget
     * instead of stacking on top of it.
     */
    private static final double MAX_EFFECTIVE_PLAYER_PACE = 0.31D;
    private static final long NAV_STALL_TICKS = 25L;
    private static final int NAV_Y_SCAN = 12;
    private static final int LOCAL_Y_SCAN = 4;
    /** Sampling horizon for approach/elevation probes; longer routes belong to vanilla A*. */
    private static final double MAX_APPROACH_HORIZON = 24.0D;
    private static final double APPROACH_STEP = 4.0D;
    private static final double HORIZONTAL_FIRST_DISTANCE_SQ = 36.0D;
    private static final double ELEVATION_DELTA_THRESHOLD = 2.0D;
    private static final double MAX_ELEVATION_SCAN = 14.0D;
    private static final double ROUTE_REPLAN_DISTANCE_SQ = 9.0D;
    private static final double WAYPOINT_ARRIVE_SQ = 2.25D;

    private MobNavigationSupport() {
    }

    public static Location prepareDestination(Location destination) {
        return prepareDestination(destination, null);
    }

    /**
     * Snaps a navigation goal to standable ground. When {@code from} is provided, routes toward elevated goals
     * horizontally first and prefers stairs/ladders instead of sending the mob straight up a wall.
     */
    public static Location prepareDestination(Location destination, Location from) {
        if (destination == null || destination.getWorld() == null) {
            return null;
        }
        if (from == null || from.getWorld() == null || !from.getWorld().equals(destination.getWorld())) {
            return snapOrGround(destination);
        }

        double horizontalSq = horizontalDistanceSq(from, destination);
        double dy = destination.getY() - from.getY();

        if (dy > ELEVATION_DELTA_THRESHOLD) {
            Location climb = findElevationRoute(from, destination);
            if (climb != null) {
                return climb;
            }
            if (horizontalSq > HORIZONTAL_FIRST_DISTANCE_SQ) {
                Location horizontal = snapAtHeight(from, destination, from.getY(), LOCAL_Y_SCAN);
                if (horizontal != null) {
                    return horizontal;
                }
            }
        }

        Location constrained = MobTerrainSupport.snapToStandableNear(
                destination,
                NAV_Y_SCAN,
                from.getY(),
                Math.max(ELEVATION_DELTA_THRESHOLD, dy + 1.0D)
        );
        if (constrained != null) {
            return constrained;
        }

        if (dy > ELEVATION_DELTA_THRESHOLD && horizontalSq <= HORIZONTAL_FIRST_DISTANCE_SQ) {
            Location nearGoal = MobTerrainSupport.snapToStandableNear(destination, LOCAL_Y_SCAN);
            if (nearGoal != null && nearGoal.getY() <= destination.getY() + 1.0D) {
                return nearGoal;
            }
        }

        Location approach = findApproachWaypoint(from, destination);
        if (approach != null) {
            return approach;
        }

        return snapOrGround(destination, from);
    }

    /** Clamps a pathfinder speed multiplier so attributeValue × multiplier never exceeds sprinting-player pace. */
    public static double clampToPlayerPace(NavigatingMobContext ctx, double speed) {
        if (ctx == null) {
            return speed;
        }
        org.bukkit.attribute.AttributeInstance attribute =
                ctx.agentMob().getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        double value = attribute != null ? attribute.getValue() : 0.0D;
        if (value <= 0.0D) {
            return speed;
        }
        return Math.min(speed, MAX_EFFECTIVE_PLAYER_PACE / value);
    }

    public static void navigateTo(NavigatingMobContext ctx, Location destination, double speed) {
        if (ctx == null || destination == null) {
            return;
        }
        speed = clampToPlayerPace(ctx, speed);
        configurePathfinder(ctx.agentMob());
        Location from = ctx.agentEntity().getLocation();
        NavigationTracker navigation = ctx.navigation();

        if (destination.getY() > from.getY() + ELEVATION_DELTA_THRESHOLD) {
            if (tryFullVanillaRoute(ctx, destination, speed)) {
                return;
            }
            if (MobLadderSupport.handleVerticalAccess(ctx, destination, speed)) {
                return;
            }
            if (MobStairSupport.handleVerticalAccess(ctx, destination, speed)) {
                return;
            }
        } else {
            navigation.vanillaRouteActive = false;
        }

        if (requiresElevationRoute(from, destination)) {
            replanRouteIfNeeded(navigation, from, destination, ctx.navClock());
            Location active = activeRouteWaypoint(navigation, from, destination);
            if (active != null) {
                executeMove(ctx, active, destination, speed);
                advanceRouteWaypoint(navigation, from);
                return;
            }
        } else {
            navigation.clearRoute();
        }

        Location prepared = prepareDestination(destination, from);
        executeMove(ctx, prepared, destination, speed);
    }

    public static boolean navigateToEntity(NavigatingMobContext ctx, LivingEntity target, double speed) {
        if (ctx == null || target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        speed = clampToPlayerPace(ctx, speed);
        configurePathfinder(ctx.agentMob());
        if (target.getLocation().getY() > ctx.agentEntity().getLocation().getY() + ELEVATION_DELTA_THRESHOLD) {
            if (tryFullVanillaRoute(ctx, target.getLocation(), speed)) {
                return true;
            }
            if (MobLadderSupport.handleVerticalAccess(ctx, target.getLocation(), speed)) {
                return true;
            }
            if (MobStairSupport.handleVerticalAccess(ctx, target.getLocation(), speed)) {
                return true;
            }
        }
        Location targetLocation = target.getLocation();
        if (targetLocation.getY() > ctx.agentEntity().getLocation().getY() + ELEVATION_DELTA_THRESHOLD) {
            navigateTo(ctx, targetLocation, speed);
            return true;
        }
        ctx.navigation().vanillaRouteActive = false;
        MobClimbSupport.assistClimb(ctx, targetLocation);
        long tick = ctx.navClock();
        trackProgress(ctx, targetLocation, tick);
        ctx.navigation().lastNavTick = tick;
        ctx.navigation().clearRoute();
        return ctx.agentMob().getPathfinder().moveTo(target, speed);
    }

    /**
     * Attempts a complete vanilla A* route to an elevated goal. Vanilla pathing walks
     * stairs and slopes natively, so when a full route exists the mob takes the long way
     * around (like a player would) instead of engaging the manual climb assists.
     * Returns true while this route owns movement.
     */
    public static boolean tryFullVanillaRoute(NavigatingMobContext ctx, Location goal, double speed) {
        if (ctx == null || goal == null || goal.getWorld() == null) {
            return false;
        }
        Mob mob = ctx.agentMob();
        LivingEntity entity = ctx.agentEntity();
        if (mob == null || entity == null || !entity.isValid() || entity.isDead()
                || !entity.getWorld().equals(goal.getWorld())) {
            return false;
        }
        NavigationTracker navigation = ctx.navigation();
        long tick = ctx.navClock();
        // hasPath() is the navigator's own completion signal (arrived or internally
        // abandoned) — keep following the current route until it flips or the goal moves.
        if (navigation.vanillaRouteActive
                && mob.getPathfinder().hasPath()
                && navigation.vanillaRouteGoal != null
                && navigation.vanillaRouteGoal.getWorld() != null
                && navigation.vanillaRouteGoal.getWorld().equals(goal.getWorld())
                && navigation.vanillaRouteGoal.distanceSquared(goal) <= ROUTE_REPLAN_DISTANCE_SQ) {
            trackProgress(ctx, goal, tick);
            if (!navigation.vanillaRouteActive) {
                // Body stall aborted the route (wedged on a lip the planner thought
                // walkable): stop and yield to the climb assists / state stall handling.
                mob.getPathfinder().stopPathfinding();
                return false;
            }
            navigation.lastNavTick = tick;
            return true;
        }
        if (navigation.stalled) {
            // Don't replan the same wedged route until the owning state consumes the stall.
            return false;
        }
        configurePathfinder(mob);
        Pathfinder.PathResult path = mob.getPathfinder().findPath(goal);
        if (path == null || path.getFinalPoint() == null || !path.canReachFinalPoint()) {
            navigation.vanillaRouteActive = false;
            navigation.vanillaRouteGoal = null;
            return false;
        }
        navigation.vanillaRouteActive = true;
        navigation.vanillaRouteGoal = goal.clone();
        MobLadderSupport.endClimb(ctx, entity);
        navigation.climbingStairs = false;
        navigation.clearRoute();
        trackProgress(ctx, goal, tick);
        navigation.lastNavTick = tick;
        mob.getPathfinder().moveTo(path, speed);
        return true;
    }

    public static void resetProgress(NavigatingMobContext ctx) {
        ctx.navigation().reset();
    }

    public static boolean consumeStall(NavigatingMobContext ctx) {
        NavigationTracker navigation = ctx.navigation();
        if (!navigation.stalled) {
            return false;
        }
        navigation.stalled = false;
        navigation.clearRoute();
        resetProgress(ctx);
        return true;
    }

    public static void trackExternalProgress(NavigatingMobContext ctx, Location destination, long tick) {
        trackProgress(ctx, destination, tick);
    }

    private static void executeMove(
            NavigatingMobContext ctx,
            Location prepared,
            Location goal,
            double speed
    ) {
        if (prepared == null) {
            return;
        }
        Location feet = ctx.agentEntity().getLocation();
        if (goal.getY() > feet.getY() + 0.75D && MobStairSupport.tryDrive(ctx, prepared, goal, speed)) {
            return;
        }
        MobClimbSupport.assistClimb(ctx, goal);
        long tick = ctx.navClock();
        trackProgress(ctx, prepared, tick);
        ctx.navigation().lastNavTick = tick;
        ctx.agentMob().getPathfinder().moveTo(prepared, speed);
    }

    private static void configurePathfinder(Mob mob) {
        if (mob == null) {
            return;
        }
        Pathfinder pathfinder = mob.getPathfinder();
        pathfinder.setCanPassDoors(true);
        pathfinder.setCanOpenDoors(true);
        pathfinder.setCanFloat(true);
    }

    private static boolean requiresElevationRoute(Location from, Location goal) {
        return goal.getY() - from.getY() > ELEVATION_DELTA_THRESHOLD;
    }

    /** Empty BFS results are held this long before replanning the same goal (spike guard). */
    private static final long EMPTY_ROUTE_TTL_TICKS = 30L;

    private static void replanRouteIfNeeded(NavigationTracker navigation, Location from, Location goal, long tick) {
        if (navigation.routeGoal != null
                && navigation.routeGoal.getWorld().equals(goal.getWorld())
                && navigation.routeGoal.distanceSquared(goal) <= ROUTE_REPLAN_DISTANCE_SQ) {
            if (!navigation.routeWaypoints.isEmpty()) {
                return;
            }
            // The BFS found nothing for this goal: don't burn another full search
            // every navigation call — the world rarely changes within the TTL.
            if (tick - navigation.routePlanTick <= EMPTY_ROUTE_TTL_TICKS) {
                return;
            }
        }
        navigation.routePlanTick = tick;
        List<Location> planned = MobElevationPathfinder.planRoute(from, goal);
        if (planned.isEmpty()) {
            navigation.clearRoute();
            navigation.routeGoal = goal.clone();
            return;
        }
        navigation.routeWaypoints = new ArrayList<>(planned);
        navigation.routeWaypointIndex = 0;
        navigation.routeGoal = goal.clone();
    }

    private static Location activeRouteWaypoint(NavigationTracker navigation, Location from, Location goal) {
        if (navigation.routeWaypoints.isEmpty()) {
            return null;
        }
        while (navigation.routeWaypointIndex < navigation.routeWaypoints.size() - 1) {
            Location waypoint = navigation.routeWaypoints.get(navigation.routeWaypointIndex);
            if (from.distanceSquared(waypoint) > WAYPOINT_ARRIVE_SQ) {
                return waypoint;
            }
            navigation.routeWaypointIndex++;
        }
        Location last = navigation.routeWaypoints.get(navigation.routeWaypoints.size() - 1);
        if (from.distanceSquared(last) <= WAYPOINT_ARRIVE_SQ && from.getY() + 1.5D >= goal.getY()) {
            return prepareDestination(goal, from);
        }
        return last;
    }

    private static void advanceRouteWaypoint(NavigationTracker navigation, Location from) {
        if (navigation.routeWaypoints.isEmpty()) {
            return;
        }
        int index = Math.min(navigation.routeWaypointIndex, navigation.routeWaypoints.size() - 1);
        Location waypoint = navigation.routeWaypoints.get(index);
        if (from.distanceSquared(waypoint) <= WAYPOINT_ARRIVE_SQ && index < navigation.routeWaypoints.size() - 1) {
            navigation.routeWaypointIndex = index + 1;
        }
    }

    private static Location snapOrGround(Location destination) {
        return snapOrGround(destination, null);
    }

    private static Location snapOrGround(Location destination, Location from) {
        Location snapped = MobTerrainSupport.snapToStandableNear(destination, NAV_Y_SCAN);
        if (snapped != null) {
            if (from == null || snapped.getY() - from.getY() <= ELEVATION_DELTA_THRESHOLD + 1.0D) {
                return snapped;
            }
        }
        if (from != null) {
            Location horizontal = snapAtHeight(from, destination, from.getY(), LOCAL_Y_SCAN);
            if (horizontal != null) {
                return horizontal;
            }
        }
        int groundY = destination.getWorld().getHighestBlockYAt(destination);
        Location ground = MobTerrainSupport.snapToStandableNear(
                new Location(destination.getWorld(), destination.getX(), groundY, destination.getZ()),
                LOCAL_Y_SCAN
        );
        return ground != null ? ground : destination.clone();
    }

    private static Location snapAtHeight(Location from, Location goal, double targetY, int yRadius) {
        Location probe = new Location(goal.getWorld(), goal.getX(), targetY, goal.getZ());
        Location snapped = MobTerrainSupport.snapToStandableNear(
                probe,
                yRadius,
                targetY,
                yRadius + 0.5D
        );
        if (snapped == null) {
            return null;
        }
        if (snapped.getY() > from.getY() + MAX_ELEVATION_SCAN) {
            return null;
        }
        return snapped;
    }

    private static Location findElevationRoute(Location from, Location goal) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }
        double dx = goal.getX() - from.getX();
        double dz = goal.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.01D) {
            dx = 0.0D;
            dz = 1.0D;
            horizontal = 1.0D;
        }
        int steps = Math.max(1, (int) Math.ceil(Math.min(horizontal, MAX_APPROACH_HORIZON) / APPROACH_STEP));
        int minY = from.getBlockY() - 1;
        int maxY = Math.min(goal.getBlockY() + 1, from.getBlockY() + (int) MAX_ELEVATION_SCAN);

        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int step = 1; step <= steps; step++) {
            double progress = Math.min(1.0D, (step * APPROACH_STEP) / horizontal);
            int sampleX = (int) Math.floor(from.getX() + dx * progress);
            int sampleZ = (int) Math.floor(from.getZ() + dz * progress);
            for (int x = sampleX - 2; x <= sampleX + 2; x++) {
                for (int z = sampleZ - 2; z <= sampleZ + 2; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (!MobTerrainSupport.isElevationAid(block)) {
                            continue;
                        }
                        Location stand = MobTerrainSupport.standOnElevationAid(block);
                        if (stand == null && MobTerrainSupport.isClimbableMaterial(block.getType())) {
                            stand = new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
                        }
                        if (stand == null) {
                            continue;
                        }
                        double score = scoreElevationCandidate(from, goal, stand);
                        if (score > bestScore) {
                            bestScore = score;
                            best = stand;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static double scoreElevationCandidate(Location from, Location goal, Location stand) {
        double horizontalFromSq = horizontalDistanceSq(from, stand);
        double yGain = stand.getY() - from.getY();
        if (yGain < -1.0D) {
            return Double.NEGATIVE_INFINITY;
        }
        return yGain * 12.0D - horizontalFromSq * 0.35D - stand.distanceSquared(goal) * 0.05D;
    }

    private static void trackProgress(NavigatingMobContext ctx, Location destination, long tick) {
        NavigationTracker navigation = ctx.navigation();
        // Detour modes (full vanilla routes, ladder/stair drives, BFS elevation routes) may
        // legitimately move away from the goal, so distance-to-goal stall tracking is replaced
        // by a physical one: the body must actually move, or the mode is aborted (this is what
        // frees mobs wedged on slab lips the path planner thought were traversable).
        boolean detourMode = navigation.climbingLadder
                || navigation.climbingStairs
                || navigation.vanillaRouteActive
                || (!navigation.routeWaypoints.isEmpty()
                        && navigation.routeGoal != null
                        && navigation.routeGoal.getY() > ctx.agentEntity().getLocation().getY() + 1.0D);
        if (detourMode) {
            if (bodyMakingProgress(ctx, navigation, tick)) {
                navigation.lastProgressTick = tick;
                navigation.stalled = false;
            } else {
                navigation.stalled = true;
                MobLadderSupport.endClimb(ctx, ctx.agentEntity());
                navigation.climbingStairs = false;
                navigation.vanillaRouteActive = false;
                navigation.vanillaRouteGoal = null;
                navigation.clearRoute();
            }
            return;
        }
        double distanceSq = ctx.agentEntity().getLocation().distanceSquared(destination);
        if (navigation.lastDestination != null
                && sameDestination(navigation.lastDestination, destination)
                && distanceSq >= navigation.lastBestDistanceSq - 0.36D) {
            if (tick - navigation.lastProgressTick >= NAV_STALL_TICKS) {
                navigation.stalled = true;
            }
            return;
        }
        navigation.lastDestination = destination.clone();
        navigation.lastBestDistanceSq = distanceSq;
        navigation.lastProgressTick = tick;
        navigation.stalled = false;
    }

    private static Location findApproachWaypoint(Location from, Location goal) {
        if (from == null || goal == null || from.getWorld() == null || !from.getWorld().equals(goal.getWorld())) {
            return null;
        }
        Vector delta = goal.toVector().subtract(from.toVector());
        delta.setY(0.0D);
        double horizontal = delta.length();
        if (horizontal < 0.5D) {
            return null;
        }
        delta.normalize();
        int steps = Math.max(1, (int) Math.ceil(Math.min(horizontal, MAX_APPROACH_HORIZON) / APPROACH_STEP));
        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int step = 1; step <= steps; step++) {
            Location sample = from.clone().add(delta.clone().multiply(step * APPROACH_STEP));
            sample.setY(from.getY());
            Location stand = MobTerrainSupport.snapToStandableNear(
                    sample,
                    LOCAL_Y_SCAN,
                    from.getY(),
                    LOCAL_Y_SCAN + 0.5D
            );
            if (stand == null) {
                continue;
            }
            double score = -stand.distanceSquared(goal);
            if (goal.getY() > from.getY() + ELEVATION_DELTA_THRESHOLD) {
                score += Math.min(stand.getY() - from.getY(), goal.getY() - from.getY()) * 3.0D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = stand;
            }
        }
        return best;
    }

    /** True while the body has physically moved within the stall window (~0.5 blocks / 25 ticks). */
    private static boolean bodyMakingProgress(NavigatingMobContext ctx, NavigationTracker navigation, long tick) {
        Location feet = ctx.agentEntity().getLocation();
        if (navigation.lastBodyPosition == null
                || navigation.lastBodyPosition.getWorld() == null
                || !navigation.lastBodyPosition.getWorld().equals(feet.getWorld())
                || navigation.lastBodyPosition.distanceSquared(feet) > 0.25D) {
            navigation.lastBodyPosition = feet.clone();
            navigation.lastBodyMoveTick = tick;
            return true;
        }
        return tick - navigation.lastBodyMoveTick < NAV_STALL_TICKS;
    }

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean sameDestination(Location left, Location right) {
        return left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ()
                && left.getWorld().equals(right.getWorld());
    }
}
