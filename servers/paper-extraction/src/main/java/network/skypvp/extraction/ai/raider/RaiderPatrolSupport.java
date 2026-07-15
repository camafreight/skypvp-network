package network.skypvp.extraction.ai.raider;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.ai.navigation.MobNavigationSupport;
import network.skypvp.paper.ai.navigation.MobTerrainSupport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** Calm-state patrol around mob-spawn anchors and coordinated squad formation marches. */
public final class RaiderPatrolSupport {

    private static final double SPAWN_BIND_RADIUS_SQ = 20.0D * 20.0D;
    /** Patrol range around the spawn origin. Far targets are reached via LEGS (below). */
    static final double MAX_PATROL_RADIUS = 200.0D;
    private static final double MIN_PATROL_RADIUS = 24.0D;
    /**
     * Navigation leg length. Vanilla mob pathfinding cannot compute 200-block routes (goal
     * distance is capped by follow range), so patrol advances toward the far target in legs
     * snapped to the LOCAL floor and re-aims after each one.
     */
    private static final double LEG_LENGTH = 20.0D;
    /** Give up on an unreachable patrol target after this many consecutive failed legs. */
    private static final int MAX_LEG_FAILURES = 3;
    private static final double ARRIVE_MAX_Y_DELTA = 2.0D;
    /** Legs snap near the mob's CURRENT floor; targets snap near the origin floor (wider). */
    private static final int LEG_Y_SCAN = 5;
    private static final double LEG_MAX_Y_DELTA = 6.0D;
    private static final int TARGET_Y_SCAN = 12;
    private static final double TARGET_MAX_Y_DELTA = 24.0D;
    private static final double FORMATION_HOLD_SQ = 2.56D;
    private static final double WAYPOINT_ARRIVE_SQ = 2.25D;
    private static final double SPAWN_ARRIVE_SQ = 3.0D;
    private static final double WALK_SPEED = MobNavigationSupport.PATROL_WALK_SPEED;
    private static final long PATROL_REFRESH_TICKS = 20L;
    /** Successful patrol points before the raider walks back to its spawn origin. */
    private static final int PATROL_POINTS_BEFORE_RETURN = 8;
    private static final long CHECKPOINT_LOOK_TICKS = 70L;
    private static final long LOOK_STEP_TICKS = 12L;
    private static final float[] LOOK_SWEEPS = {-80F, -40F, 0F, 40F, 80F, 35F, -35F, 0F};

    private RaiderPatrolSupport() {
    }

    public static void bindPatrolCohort(RaiderAgentContext ctx, BreachMapMeta mapMeta) {
        if (ctx == null || ctx.entity == null) {
            return;
        }
        Location feet = ctx.entity.getLocation();
        if (mapMeta == null || mapMeta.mobSpawns().isEmpty()) {
            // Leave patrolOrigin null so callers can retry once map meta is ready.
            return;
        }
        BreachMapMeta.MobSpawn nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (BreachMapMeta.MobSpawn spawn : mapMeta.mobSpawns()) {
            double dx = feet.getX() - spawn.x();
            double dy = feet.getY() - spawn.y();
            double dz = feet.getZ() - spawn.z();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < bestSq) {
                bestSq = distanceSq;
                nearest = spawn;
            }
        }
        if (nearest != null && bestSq <= SPAWN_BIND_RADIUS_SQ) {
            ctx.patrolOrigin = new Location(feet.getWorld(), nearest.x(), nearest.y(), nearest.z());
            ctx.spawnCohortId = cohortId(nearest.cohortId());
            return;
        }
        // Bound to feet as a soft origin, but keep cohort null so proximity grouping still works
        // and a later retry can attach the real spawn cohort when the mob is closer.
        if (ctx.patrolOrigin == null) {
            ctx.patrolOrigin = feet.clone();
        }
    }

    static void tick(RaiderAgentContext ctx) {
        Location feet = ctx.entity.getLocation();
        Location origin = patrolOrigin(ctx);
        long tick = ctx.aiTick;

        if (ctx.patrolCheckpointUntilTick > 0L) {
            if (tick < ctx.patrolCheckpointUntilTick) {
                ctx.mob.getPathfinder().stopPathfinding();
                tickCheckpointLook(ctx, tick);
                return;
            }
            completeCheckpoint(ctx);
            ctx.patrolCheckpointUntilTick = 0L;
        }

        if (ctx.patrolScoutPoint != null) {
            if (nearPoint(feet, ctx.patrolScoutPoint, WAYPOINT_ARRIVE_SQ)) {
                ctx.patrolScoutPoint = null;
                beginCheckpointLook(ctx, tick, feet);
            } else {
                MobNavigationSupport.navigateTo(ctx, ctx.patrolScoutPoint, WALK_SPEED);
            }
            return;
        }

        evaluateArrival(ctx, feet, origin, tick);
        if (ctx.patrolCheckpointUntilTick > 0L) {
            return;
        }

        if (tick < ctx.nextPatrolRefreshTick) {
            return;
        }
        if (MobNavigationSupport.consumeStall(ctx)) {
            // A stalled LEG is a navigation failure, not a patrol reset. Returning home is
            // NEVER cancelled by a stall (it used to be — stuck mobs abandoned the return
            // and re-patrolled forever); a fresh leg is picked toward the same goal instead.
            ctx.patrolWaypoint = null;
            ctx.patrolLegFailures++;
            if (!ctx.returningToSpawn && ctx.patrolLegFailures >= MAX_LEG_FAILURES) {
                // Target unreachable — skip it WITHOUT counting a success and roam anew.
                ctx.patrolTarget = null;
                ctx.patrolLegFailures = 0;
            } else if (ctx.returningToSpawn && ctx.patrolLegFailures >= MAX_LEG_FAILURES) {
                // Keep heading home, but reset the counter so leg rerolls continue forever;
                // the state watchdog handles a truly wedged mob.
                ctx.patrolLegFailures = 0;
            }
        }
        ctx.nextPatrolRefreshTick = tick + PATROL_REFRESH_TICKS;
        Location destination = resolveDestination(ctx, tick, feet, origin);
        if (destination == null) {
            ctx.mob.getPathfinder().stopPathfinding();
            return;
        }
        MobNavigationSupport.navigateTo(ctx, destination, WALK_SPEED);
    }

    static void stop(RaiderAgentContext ctx) {
        ctx.nextPatrolRefreshTick = 0L;
        ctx.patrolWaypoint = null;
        ctx.patrolTarget = null;
        ctx.patrolLegFailures = 0;
        ctx.patrolCheckpointUntilTick = 0L;
        ctx.patrolLookStep = 0;
        ctx.patrolScoutPoint = null;
        ctx.mob.getPathfinder().stopPathfinding();
        MobNavigationSupport.resetProgress(ctx);
    }

    private static void evaluateArrival(RaiderAgentContext ctx, Location feet, Location origin, long tick) {
        if (ctx.returningToSpawn) {
            if (nearPoint(feet, origin, SPAWN_ARRIVE_SQ)) {
                ctx.returningToSpawn = false;
                ctx.patrolPointsCompleted = 0;
                ctx.patrolWaypoint = null;
                ctx.patrolTarget = null;
                ctx.patrolLegFailures = 0;
                ctx.mob.getPathfinder().stopPathfinding();
            }
            return;
        }
        Location waypoint = ctx.patrolWaypoint;
        if (waypoint == null || !nearPoint(feet, waypoint, WAYPOINT_ARRIVE_SQ)) {
            return;
        }
        // Leg completed. Only arrival at the PATROL TARGET counts as a successful patrol
        // point (with the checkpoint look-around); intermediate legs just advance.
        ctx.patrolLegFailures = 0;
        if (ctx.patrolTarget != null && nearPoint(feet, ctx.patrolTarget, WAYPOINT_ARRIVE_SQ)) {
            beginSquadCheckpoint(ctx, tick, feet);
        } else {
            ctx.patrolWaypoint = null;
        }
    }

    /** Horizontal arrival with a slab/step vertical tolerance so snapped waypoints still count. */
    private static boolean nearPoint(Location feet, Location point, double horizontalRangeSq) {
        double dx = feet.getX() - point.getX();
        double dz = feet.getZ() - point.getZ();
        if (dx * dx + dz * dz > horizontalRangeSq) {
            return false;
        }
        return Math.abs(feet.getY() - point.getY()) <= ARRIVE_MAX_Y_DELTA;
    }

    private static void beginSquadCheckpoint(RaiderAgentContext ctx, long tick, Location feet) {
        if (ctx.inSquad() && ctx.squadPatrolGoal != null && ctx.squadPatrolFacingValid) {
            Location scout = RaiderSquadFormation.scoutSlot(
                    ctx.groupRole,
                    ctx.squadPatrolGoal,
                    ctx.squadPatrolFacingYaw
            );
            Location snapped = scout == null ? null : MobNavigationSupport.prepareDestination(scout);
            if (snapped != null) {
                scout = snapped;
            }
            if (scout != null && !nearPoint(feet, scout, WAYPOINT_ARRIVE_SQ)) {
                ctx.patrolScoutPoint = scout;
                return;
            }
        }
        beginCheckpointLook(ctx, tick, feet);
    }

    private static void beginCheckpointLook(RaiderAgentContext ctx, long tick, Location feet) {
        ctx.mob.getPathfinder().stopPathfinding();
        ctx.patrolCheckpointUntilTick = tick + CHECKPOINT_LOOK_TICKS;
        float bias = ctx.inSquad() ? RaiderSquadFormation.checkpointLookBias(ctx.groupRole) : 0.0F;
        ctx.patrolLookBaseYaw = normalizeYaw(feet.getYaw() + bias);
        ctx.patrolLookStep = 0;
        ctx.patrolLookNextStepTick = tick;
        applyLookStep(ctx);
    }

    private static void tickCheckpointLook(RaiderAgentContext ctx, long tick) {
        if (tick < ctx.patrolLookNextStepTick) {
            return;
        }
        ctx.patrolLookNextStepTick = tick + LOOK_STEP_TICKS;
        ctx.patrolLookStep = (ctx.patrolLookStep + 1) % LOOK_SWEEPS.length;
        applyLookStep(ctx);
    }

    private static void applyLookStep(RaiderAgentContext ctx) {
        float sweep = LOOK_SWEEPS[Math.floorMod(ctx.patrolLookStep, LOOK_SWEEPS.length)];
        float yaw = normalizeYaw(ctx.patrolLookBaseYaw + sweep);
        float pitch = sweep == 0F ? 0F : 4F;
        ctx.entity.setRotation(yaw, pitch);
        ctx.aiFacingYaw = yaw;
        ctx.aiFacingTick = ctx.aiTick;
    }

    private static void completeCheckpoint(RaiderAgentContext ctx) {
        ctx.patrolWaypoint = null;
        ctx.patrolTarget = null;
        ctx.patrolLegFailures = 0;
        ctx.patrolPointsCompleted++;
        if (ctx.patrolPointsCompleted >= PATROL_POINTS_BEFORE_RETURN) {
            ctx.returningToSpawn = true;
            ctx.patrolPointsCompleted = 0;
        }
    }

    private static float normalizeYaw(float yaw) {
        float wrapped = yaw % 360.0F;
        if (wrapped <= -180.0F) {
            wrapped += 360.0F;
        } else if (wrapped > 180.0F) {
            wrapped -= 360.0F;
        }
        return wrapped;
    }

    private static UUID cohortId(String spawnPointId) {
        if (spawnPointId == null || spawnPointId.isBlank()) {
            return null;
        }
        return UUID.nameUUIDFromBytes(("raider-cohort:" + spawnPointId.trim()).getBytes(StandardCharsets.UTF_8));
    }

    private static Location resolveDestination(
            RaiderAgentContext ctx,
            long tick,
            Location feet,
            Location origin
    ) {
        if (ctx.returningToSpawn) {
            // The origin can be up to MAX_PATROL_RADIUS away — walk home in legs too.
            return nextLeg(ctx, feet, origin);
        }
        if (ctx.inSquad() && ctx.groupAnchorId != null && !ctx.entity.getUniqueId().equals(ctx.groupAnchorId)) {
            Location formationCenter = squadFormationCenter(ctx);
            if (formationCenter != null) {
                float facing = resolveFormationFacing(ctx, formationCenter);
                Location slot = RaiderSquadFormation.patrolSlot(ctx.groupRole, formationCenter, facing);
                if (slot != null) {
                    Location snapped = MobNavigationSupport.prepareDestination(slot);
                    if (snapped != null) {
                        slot = snapped;
                    }
                    if (feet.distanceSquared(slot) <= FORMATION_HOLD_SQ) {
                        return null;
                    }
                    if (feet.distanceSquared(slot) > LEG_LENGTH * LEG_LENGTH) {
                        Location leg = nextLeg(ctx, feet, slot);
                        return leg != null ? leg : slot;
                    }
                    return slot;
                }
            }
        }
        if (RaiderSquadPatrolSupport.anchorShouldRegroup(ctx)) {
            ctx.mob.getPathfinder().stopPathfinding();
            return null;
        }
        if (ctx.patrolTarget == null) {
            ctx.patrolTarget = randomPatrolTarget(origin, tick, ctx, feet);
            ctx.patrolWaypoint = null;
            if (ctx.patrolTarget == null) {
                // No standable target this cycle (map edge / vertical terrain): reroll on
                // the next refresh instead of handing the pathfinder a bad goal.
                return null;
            }
        }
        Location waypoint = ctx.patrolWaypoint;
        if (waypoint != null && !nearPoint(feet, waypoint, WAYPOINT_ARRIVE_SQ)) {
            return waypoint;
        }
        ctx.patrolWaypoint = nextLeg(ctx, feet, ctx.patrolTarget);
        if (ctx.patrolWaypoint == null) {
            ctx.patrolLegFailures++;
            if (ctx.patrolLegFailures >= MAX_LEG_FAILURES) {
                ctx.patrolTarget = null;
                ctx.patrolLegFailures = 0;
            }
            return null;
        }
        // Final leg: the snapped floor can differ vertically from the stored target;
        // align the target so arrival (Y-tolerant nearPoint) registers the success.
        double dxT = ctx.patrolWaypoint.getX() - ctx.patrolTarget.getX();
        double dzT = ctx.patrolWaypoint.getZ() - ctx.patrolTarget.getZ();
        if (dxT * dxT + dzT * dzT <= WAYPOINT_ARRIVE_SQ) {
            ctx.patrolTarget = ctx.patrolWaypoint.clone();
        }
        return ctx.patrolWaypoint;
    }

    /**
     * Next navigation leg toward {@code goal}: at most {@link #LEG_LENGTH} blocks along the
     * straight line, snapped to a standable spot near the mob's CURRENT floor (long patrols
     * cross elevation, so origin-floor snapping would reject most legs). Falls back to a
     * half-length leg before reporting failure.
     */
    private static Location nextLeg(RaiderAgentContext ctx, Location feet, Location goal) {
        if (goal == null || goal.getWorld() == null) {
            return null;
        }
        double dx = goal.getX() - feet.getX();
        double dz = goal.getZ() - feet.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= LEG_LENGTH) {
            Location snapped = MobTerrainSupport.snapToStandableNear(
                    goal.clone(), LEG_Y_SCAN, goal.getY(), LEG_MAX_Y_DELTA);
            return snapped != null ? snapped : goal.clone();
        }
        for (double legLength : new double[] {LEG_LENGTH, LEG_LENGTH * 0.5D}) {
            double scale = legLength / horizontal;
            Location candidate = new Location(
                    feet.getWorld(),
                    feet.getX() + dx * scale,
                    feet.getY(),
                    feet.getZ() + dz * scale
            );
            Location snapped = MobTerrainSupport.snapToStandableNear(
                    candidate, LEG_Y_SCAN, feet.getY(), LEG_MAX_Y_DELTA);
            if (snapped != null) {
                return snapped;
            }
        }
        return null;
    }

    private static Location patrolOrigin(RaiderAgentContext ctx) {
        if (ctx.patrolOrigin != null && ctx.patrolOrigin.getWorld() != null) {
            return ctx.patrolOrigin;
        }
        return ctx.entity.getLocation();
    }

    /**
     * Picks the next far patrol destination: 24–200 blocks from the spawn origin, snapped
     * to standable terrain with a generous vertical window (long patrols legitimately cross
     * elevation). A null result skips this cycle and rerolls on the next refresh.
     */
    private static Location randomPatrolTarget(Location origin, long tick, RaiderAgentContext ctx, Location current) {
        UUID entityId = ctx.entity.getUniqueId();
        int seed = (int) (tick ^ entityId.getMostSignificantBits() ^ (ctx.patrolPointsCompleted * 31L));
        double angle = (seed & 0xFF) / 255.0D * Math.PI * 2.0D;
        double maxRadius = RaiderSquadPatrolSupport.squadMaxPatrolRadius(ctx);
        double radius = MIN_PATROL_RADIUS
                + ((seed >>> 8) & 0xFF) / 255.0D * (maxRadius - MIN_PATROL_RADIUS);
        double x = origin.getX() + Math.cos(angle) * radius;
        double z = origin.getZ() + Math.sin(angle) * radius;
        Location candidate = new Location(origin.getWorld(), x, origin.getY(), z);
        if (candidate.distanceSquared(origin) > maxRadius * maxRadius) {
            Vector toward = candidate.toVector().subtract(origin.toVector()).normalize().multiply(maxRadius);
            candidate = origin.clone().add(toward);
            candidate.setY(origin.getY());
        }
        if (current != null && candidate.distanceSquared(current) < 16.0D) {
            candidate.add(Math.cos(angle + Math.PI * 0.5D) * 12.0D, 0.0D, Math.sin(angle + Math.PI * 0.5D) * 12.0D);
        }
        return MobTerrainSupport.snapToStandableNear(
                candidate,
                TARGET_Y_SCAN,
                origin.getY(),
                TARGET_MAX_Y_DELTA
        );
    }

    private static Location squadFormationCenter(RaiderAgentContext ctx) {
        if (ctx.squadPatrolGoal != null && ctx.squadPatrolGoal.getWorld() != null) {
            return ctx.squadPatrolGoal.clone();
        }
        LivingEntity anchor = living(ctx.groupAnchorId);
        if (anchor != null && anchor.isValid() && !anchor.isDead()) {
            return anchor.getLocation();
        }
        return null;
    }

    private static float resolveFormationFacing(RaiderAgentContext ctx, Location formationCenter) {
        if (ctx.squadPatrolFacingValid) {
            return ctx.squadPatrolFacingYaw;
        }
        if (ctx.formationFacingValid) {
            return ctx.formationFacingYaw;
        }
        LivingEntity anchor = living(ctx.groupAnchorId);
        if (anchor != null && anchor.isValid() && !anchor.isDead()) {
            return RaiderSquadPatrolSupport.facingToward(anchor.getLocation(), formationCenter);
        }
        return formationCenter.getYaw();
    }

    private static LivingEntity living(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        return Bukkit.getEntity(entityId) instanceof LivingEntity living ? living : null;
    }
}
