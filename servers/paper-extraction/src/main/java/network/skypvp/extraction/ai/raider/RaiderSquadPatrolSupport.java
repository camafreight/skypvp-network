package network.skypvp.extraction.ai.raider;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Squad-level idle patrol: shared goal, spread leash, anchor regroup gate. */
final class RaiderSquadPatrolSupport {

    /** Squad anchors roam closer to spawn so followers can keep formation. */
    static final double SQUAD_MAX_PATROL_RADIUS = 72.0D;
    /** Anchor waits for stragglers before picking the next leg. */
    static final double SQUAD_REGROUP_HORIZONTAL_SQ = 36.0D * 36.0D;

    private RaiderSquadPatrolSupport() {
    }

    record IdlePatrolPlan(
            Location goal,
            float facingYaw,
            boolean facingValid,
            double maxMemberSpreadSq
    ) {
        static IdlePatrolPlan none() {
            return new IdlePatrolPlan(null, 0.0F, false, 0.0D);
        }
    }

    static IdlePatrolPlan planIdlePatrol(
            List<RaiderGroupRegistry.MemberRef> cluster,
            Location anchorLocation,
            Location threatLocation
    ) {
        if (cluster == null || cluster.isEmpty() || threatLocation != null) {
            return IdlePatrolPlan.none();
        }
        RaiderAgentContext anchorCtx = cluster.get(0).context();
        Location goal = resolvePatrolGoal(anchorCtx);
        float facing = 0.0F;
        boolean facingValid = false;
        if (goal != null && anchorLocation != null && anchorLocation.getWorld() != null
                && goal.getWorld() != null && anchorLocation.getWorld().equals(goal.getWorld())) {
            facing = facingToward(anchorLocation, goal);
            facingValid = true;
        }
        return new IdlePatrolPlan(
                goal == null ? null : goal.clone(),
                facing,
                facingValid,
                maxMemberSpreadSq(cluster, anchorLocation)
        );
    }

    static Location resolvePatrolGoal(RaiderAgentContext anchorCtx) {
        if (anchorCtx == null) {
            return null;
        }
        if (anchorCtx.returningToSpawn) {
            return patrolOrigin(anchorCtx);
        }
        if (anchorCtx.patrolTarget != null && anchorCtx.patrolTarget.getWorld() != null) {
            return anchorCtx.patrolTarget;
        }
        if (anchorCtx.patrolWaypoint != null && anchorCtx.patrolWaypoint.getWorld() != null) {
            return anchorCtx.patrolWaypoint;
        }
        return null;
    }

    static boolean anchorShouldRegroup(RaiderAgentContext ctx) {
        return ctx != null
                && ctx.inSquad()
                && ctx.isSuppressAnchor()
                && ctx.squadMaxMemberSpreadSq > SQUAD_REGROUP_HORIZONTAL_SQ;
    }

    static float facingToward(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return 0.0F;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 0.01D) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    static double squadMaxPatrolRadius(RaiderAgentContext ctx) {
        if (ctx != null && ctx.inSquad() && ctx.isSuppressAnchor()) {
            return SQUAD_MAX_PATROL_RADIUS;
        }
        return 200.0D;
    }

    private static double maxMemberSpreadSq(
            List<RaiderGroupRegistry.MemberRef> cluster,
            Location anchorLocation
    ) {
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            return 0.0D;
        }
        double maxSq = 0.0D;
        for (RaiderGroupRegistry.MemberRef member : cluster) {
            if (member == null || member.context() == null || !member.context().cachedLocationValid) {
                continue;
            }
            Location memberLocation = member.context().cachedLocation();
            if (memberLocation.getWorld() == null || !memberLocation.getWorld().equals(anchorLocation.getWorld())) {
                continue;
            }
            double dx = memberLocation.getX() - anchorLocation.getX();
            double dz = memberLocation.getZ() - anchorLocation.getZ();
            maxSq = Math.max(maxSq, dx * dx + dz * dz);
        }
        return maxSq;
    }

    private static Location patrolOrigin(RaiderAgentContext ctx) {
        if (ctx.patrolOrigin != null && ctx.patrolOrigin.getWorld() != null) {
            return ctx.patrolOrigin;
        }
        return ctx.entity.getLocation();
    }
}
