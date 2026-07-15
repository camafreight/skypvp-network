package network.skypvp.extraction.ai.raider;

import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/** Computes squad roles, shared intel, and coordinated move points. */
final class RaiderGroupTactics {

    private static final double FLANK_RADIUS = 9.0D;
    private static final double BREACH_OFFSET = 6.5D;
    /** Already standing at the tactic point: choosing SQUAD_TACTIC again would just camp it. */
    private static final double TACTIC_ARRIVE_SQ = 4.0D;
    /** Beyond combat grouping range the gunner is considered detached from the fireteam. */
    private static final double SQUAD_ABANDON_DISTANCE_SQ = 48.0D * 48.0D;

    private RaiderGroupTactics() {
    }

    static Location findRealCover(RaiderAgentContext ctx, LivingEntity threat) {
        if (ctx.inSquad() && ctx.groupRole.isFlanker()) {
            Location flankCover = RaiderCoverSupport.findRealCover(ctx, threat, ctx.groupRole);
            if (flankCover != null) {
                return flankCover;
            }
        }
        return RaiderCoverSupport.findRealCover(ctx, threat);
    }

    static Location findRealCoverCached(RaiderAgentContext ctx, LivingEntity threat) {
        long tick = ctx.aiTick;
        if (ctx.cachedRealCover != null
                && tick - ctx.cachedRealCoverTick <= RaiderAgentContext.COVER_CACHE_TTL_TICKS) {
            return ctx.cachedRealCover;
        }
        Location found = findRealCover(ctx, threat);
        ctx.cachedRealCover = found;
        ctx.cachedRealCoverTick = tick;
        return found;
    }

    static Location findCover(RaiderAgentContext ctx, LivingEntity threat) {
        if (ctx.inSquad() && ctx.groupRole.isFlanker()) {
            Location flankCover = RaiderCoverSupport.findBestCover(ctx, threat, ctx.groupRole);
            if (flankCover != null) {
                return flankCover;
            }
        }
        return RaiderCoverSupport.findBestCover(ctx, threat);
    }

    static Location computeTacticPoint(
            RaiderGroupRole role,
            Location memberLocation,
            Location anchorLocation,
            LivingEntity target
    ) {
        Location threatLocation = target != null && target.isValid() && !target.isDead()
                ? target.getLocation()
                : null;
        return computeTacticPoint(role, memberLocation, anchorLocation, threatLocation);
    }

    static Location computeTacticPoint(
            RaiderGroupRole role,
            Location memberLocation,
            Location anchorLocation,
            Location threatLocation
    ) {
        if (role == null || threatLocation == null) {
            return null;
        }
        if (role == RaiderGroupRole.SOLO || role == RaiderGroupRole.SUPPRESS) {
            return null;
        }
        Vector toThreat = horizontal(threatLocation.toVector().subtract(anchorLocation.toVector()));
        if (toThreat.lengthSquared() < 0.01D) {
            toThreat = horizontal(memberLocation.toVector().subtract(anchorLocation.toVector()));
        }
        if (toThreat.lengthSquared() < 0.01D) {
            toThreat = new Vector(0.0D, 0.0D, 1.0D);
        }
        toThreat.normalize();
        Vector left = new Vector(-toThreat.getZ(), 0.0D, toThreat.getX());
        Vector right = left.clone().multiply(-1.0D);

        Location point = switch (role) {
            case FLANK_LEFT -> threatLocation.clone().add(left.multiply(FLANK_RADIUS));
            case FLANK_RIGHT -> threatLocation.clone().add(right.multiply(FLANK_RADIUS));
            case BREACH -> threatLocation.clone().add(toThreat.clone().multiply(BREACH_OFFSET));
            default -> null;
        };
        if (point != null) {
            // Prefer threat elevation so flank waypoints climb stairs/ladders with the player.
            double threatY = threatLocation.getY();
            double memberY = memberLocation.getY();
            point.setY(Math.abs(threatY - memberY) <= 2.5D ? memberY : threatY);
            // No ground-snapping here: squad rebuild runs on Folia's GLOBAL thread where
            // block reads throw. navigateTo snaps the point on the owning region thread.
        }
        return point;
    }

    static void mergeIntel(RaiderAgentContext ctx, Location sharedLastKnown, long sharedLastSeenTick) {
        if (sharedLastKnown == null || sharedLastSeenTick <= 0L) {
            return;
        }
        if (ctx.lastKnownTargetLocation == null || sharedLastSeenTick > ctx.lastSeenTargetTick) {
            ctx.lastKnownTargetLocation = sharedLastKnown.clone();
            ctx.lastSeenTargetTick = sharedLastSeenTick;
        }
    }

    static CombatAgentStateId preferredPursuitState(RaiderAgentContext ctx) {
        if (ctx.inSquad() && ctx.groupRole.coordinates() && ctx.groupTacticPoint != null
                && ctx.entity.getLocation().distanceSquared(ctx.groupTacticPoint) > TACTIC_ARRIVE_SQ) {
            if (squadHasSuppressContact(ctx) || canSelfPin(ctx)) {
                return CombatAgentStateId.SQUAD_TACTIC;
            }
        }
        return CombatAgentStateId.PURSUE;
    }

    /**
     * Flankers/breachers only peel for a tactic point once the fireteam has recent contact
     * (suppressor pin or shared intel), so they don't run empty flanks on stale noise.
     */
    static boolean squadHasSuppressContact(RaiderAgentContext ctx) {
        if (ctx == null || !ctx.inSquad()) {
            return false;
        }
        long tick = ctx.aiTick;
        return ctx.lastSeenTargetTick > 0L && tick - ctx.lastSeenTargetTick <= 60L;
    }

    private static boolean canSelfPin(RaiderAgentContext ctx) {
        LivingEntity target = ctx.mob.getTarget();
        return target != null
                && target.isValid()
                && !target.isDead()
                && RaiderSightSupport.canSeeTarget(ctx, target);
    }

    static boolean isFarFromSquad(RaiderAgentContext ctx) {
        if (!ctx.inSquad()) {
            return false;
        }
        Location self = ctx.cachedLocationValid ? ctx.cachedLocation() : ctx.entity.getLocation();
        Location anchor = resolveSquadAnchorLocation(ctx);
        if (anchor == null || anchor.getWorld() == null || !anchor.getWorld().equals(self.getWorld())) {
            return false;
        }
        return self.distanceSquared(anchor) > SQUAD_ABANDON_DISTANCE_SQ;
    }

    static boolean isSeparatingFromSquad(RaiderAgentContext ctx) {
        if (!ctx.inSquad()) {
            return false;
        }
        Location anchor = resolveSquadAnchorLocation(ctx);
        if (anchor == null) {
            return false;
        }
        Location self = ctx.cachedLocationValid ? ctx.cachedLocation() : ctx.entity.getLocation();
        double selfFromAnchor = self.distanceSquared(anchor);
        Location separationTarget = ctx.retreatPoint;
        if (separationTarget == null) {
            separationTarget = ctx.lastKnownTargetLocation;
        }
        if (separationTarget == null) {
            separationTarget = ctx.patrolOrigin;
        }
        if (separationTarget == null || separationTarget.getWorld() == null) {
            return false;
        }
        if (!separationTarget.getWorld().equals(self.getWorld())) {
            return true;
        }
        return separationTarget.distanceSquared(anchor) > selfFromAnchor + 4.0D;
    }

    static boolean shouldEnterSquadAbandon(RaiderAgentContext ctx) {
        return ctx.inSquad() && isFarFromSquad(ctx) && isSeparatingFromSquad(ctx);
    }

    static CombatAgentStateId maybeSquadAbandon(RaiderAgentContext ctx) {
        return shouldEnterSquadAbandon(ctx) ? CombatAgentStateId.SQUAD_LEAVE : CombatAgentStateId.IDLE;
    }

    static void beginLeavingSquad(RaiderAgentContext ctx) {
        ctx.leavingSquad = true;
    }

    static void finishLeavingSquad(RaiderAgentContext ctx) {
        ctx.leavingSquad = false;
        ctx.groupId = null;
        ctx.groupSize = 1;
        ctx.groupRole = RaiderGroupRole.SOLO;
        ctx.groupAnchorId = null;
        ctx.groupTacticPoint = null;
        ctx.squadAnchorLocation = null;
        ctx.groupTacticAssignedTick = 0L;
        ctx.formationFacingValid = false;
        ctx.formationFacingThreat = null;
        ctx.strafeSide = 0;
    }

    private static Location resolveSquadAnchorLocation(RaiderAgentContext ctx) {
        if (ctx.squadAnchorLocation != null) {
            return ctx.squadAnchorLocation;
        }
        if (ctx.groupAnchorId == null) {
            return null;
        }
        org.bukkit.entity.Entity anchorEntity = org.bukkit.Bukkit.getEntity(ctx.groupAnchorId);
        if (anchorEntity != null && anchorEntity.isValid()) {
            return anchorEntity.getLocation();
        }
        return null;
    }

    private static Vector horizontal(Vector vector) {
        return vector.setY(0.0D);
    }
}
