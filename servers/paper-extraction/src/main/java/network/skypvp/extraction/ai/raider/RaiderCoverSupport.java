package network.skypvp.extraction.ai.raider;

import java.util.concurrent.ThreadLocalRandom;
import network.skypvp.paper.ai.navigation.MobTerrainSupport;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Scans nearby terrain for positions that block line-of-sight from a threat. */
final class RaiderCoverSupport {

    private static final int SCAN_RADIUS = 10;
    private static final int SCAN_Y_OFFSET = 2;
    private static final double MAX_TRAVEL_DISTANCE = 12.0D;

    private RaiderCoverSupport() {
    }

    static Location findBestCover(RaiderAgentContext ctx, LivingEntity threat) {
        return findBestCover(ctx, threat, null);
    }

    static Location findRealCover(RaiderAgentContext ctx, LivingEntity threat) {
        return findRealCover(ctx, threat, null);
    }

    static Location findRealCover(RaiderAgentContext ctx, LivingEntity threat, RaiderGroupRole flankRole) {
        if (ctx == null || threat == null || !threat.isValid() || threat.isDead()) {
            return null;
        }
        Location origin = ctx.entity.getLocation();
        Location threatLocation = threat.getLocation();
        World world = origin.getWorld();
        long tick = ctx.aiTick;

        Location best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        int centerX = origin.getBlockX();
        int centerY = origin.getBlockY();
        int centerZ = origin.getBlockZ();
        int step = ctx.underFire(tick) ? 1 : 2;

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx += step) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz += step) {
                Location stand = resolveStandLocation(world, centerX + dx, centerZ + dz, centerY);
                if (stand == null || !MobTerrainSupport.isStandable(stand)) {
                    continue;
                }
                if (!blocksThreatLineOfSight(threat, stand)) {
                    continue;
                }
                double score = scoreCandidate(origin, threatLocation, stand, ctx, tick, flankRole);
                if (score > bestScore) {
                    bestScore = score;
                    best = stand;
                }
            }
        }
        return best;
    }

    static Location findBestCover(RaiderAgentContext ctx, LivingEntity threat, RaiderGroupRole flankRole) {
        Location real = findRealCover(ctx, threat, flankRole);
        if (real != null) {
            return real;
        }
        Location origin = ctx.entity.getLocation();
        Location retreat = findRetreatWaypoint(origin, threat.getLocation(), false);
        if (retreat != null) {
            return retreat;
        }
        return fallbackCover(origin, threat.getLocation());
    }

    /** Sprint destination away from the threat while searching for real cover. */
    static Location findRetreatWaypoint(Location origin, Location threatLocation, boolean longStep) {
        Vector away = origin.toVector().subtract(threatLocation.toVector());
        if (away.lengthSquared() < 0.01D) {
            away = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5D, 0.0D, 1.0D);
        }
        away.setY(0.0D).normalize();
        Vector lateral = new Vector(-away.getZ(), 0.0D, away.getX())
                .multiply(ThreadLocalRandom.current().nextBoolean() ? 1.0D : -1.0D);
        double[] distances = longStep
                ? new double[] {8.0D, 10.0D, 12.0D, 14.0D}
                : new double[] {3.0D, 4.5D, 6.0D};
        World world = origin.getWorld();
        for (double distance : distances) {
            Vector step = away.clone().multiply(distance).add(lateral.clone().multiply(longStep ? 2.0D : 0.0D));
            Location candidate = origin.clone().add(step);
            Location snapped = snapToStandable(candidate);
            if (snapped != null) {
                return snapped;
            }
            snapped = resolveStandLocation(world, candidate.getBlockX(), candidate.getBlockZ(), origin.getBlockY());
            if (snapped != null) {
                return snapped;
            }
        }
        return null;
    }

    static void updateRetreatProgress(RaiderAgentContext ctx, long tick) {
        if (ctx.retreatPoint == null) {
            return;
        }
        double distanceSq = ctx.entity.getLocation().distanceSquared(ctx.retreatPoint);
        if (distanceSq + 0.36D < ctx.coverBestDistanceSq) {
            ctx.coverBestDistanceSq = distanceSq;
            ctx.coverLastProgressTick = tick;
        }
    }

    static void markRetreatAssignment(RaiderAgentContext ctx, long tick) {
        ctx.coverAssignedTick = tick;
        ctx.coverLastProgressTick = tick;
        ctx.coverBestDistanceSq = ctx.retreatPoint == null
                ? Double.MAX_VALUE
                : ctx.entity.getLocation().distanceSquared(ctx.retreatPoint);
    }

    static void markAssignment(RaiderAgentContext ctx, long tick) {
        ctx.coverAssignedTick = tick;
        ctx.coverLastProgressTick = tick;
        ctx.coverBestDistanceSq = ctx.coverPoint == null
                ? Double.MAX_VALUE
                : ctx.entity.getLocation().distanceSquared(ctx.coverPoint);
    }

    static void updateProgress(RaiderAgentContext ctx, long tick) {
        if (ctx.coverPoint == null) {
            return;
        }
        double distanceSq = ctx.entity.getLocation().distanceSquared(ctx.coverPoint);
        if (distanceSq + 0.36D < ctx.coverBestDistanceSq) {
            ctx.coverBestDistanceSq = distanceSq;
            ctx.coverLastProgressTick = tick;
        }
    }

    static long ticksWithoutCoverProgress(RaiderAgentContext ctx, long tick) {
        return tick - ctx.coverLastProgressTick;
    }

    static boolean stillProvidesCover(LivingEntity threat, Location stand) {
        return stand != null && blocksThreatLineOfSight(threat, stand);
    }

    private static double scoreCandidate(
            Location origin,
            Location threatLocation,
            Location stand,
            RaiderAgentContext ctx,
            long tick,
            RaiderGroupRole flankRole
    ) {
        double travel = origin.distance(stand);
        if (travel > MAX_TRAVEL_DISTANCE) {
            return Double.NEGATIVE_INFINITY;
        }

        double travelScore = 1.0D - (travel / MAX_TRAVEL_DISTANCE);
        double threatSeparation = Math.min(threatLocation.distance(stand), 20.0D) / 20.0D;
        double lateralScore = lateralOffsetScore(origin, threatLocation, stand);

        double urgency = ctx.underFire(tick) ? 2.8D : 1.0D;
        double reloadBias = ctx.needsReload() || ctx.needsHeal() ? 0.6D : 0.0D;

        double flankBias = flankBiasScore(origin, threatLocation, stand, flankRole);

        return urgency * travelScore * 4.0D
                + threatSeparation * 1.5D
                + lateralScore * 1.2D
                + flankBias * 2.5D
                + reloadBias * threatSeparation
                - travel * 0.08D;
    }

    private static double flankBiasScore(
            Location origin,
            Location threatLocation,
            Location stand,
            RaiderGroupRole flankRole
    ) {
        if (flankRole == null || !flankRole.isFlanker()) {
            return 0.0D;
        }
        Vector toThreat = threatLocation.toVector().subtract(origin.toVector()).setY(0.0D);
        Vector toStand = stand.toVector().subtract(origin.toVector()).setY(0.0D);
        if (toThreat.lengthSquared() < 0.01D || toStand.lengthSquared() < 0.01D) {
            return 0.0D;
        }
        toThreat.normalize();
        // Perpendicular right of approach; positive side = world-right of the threat vector.
        Vector right = new Vector(toThreat.getZ(), 0.0D, -toThreat.getX());
        double side = toStand.dot(right);
        return flankRole == RaiderGroupRole.FLANK_LEFT ? -side : side;
    }

    /** Prefers positions off the direct threat vector so cover is not inline with incoming fire. */
    private static double lateralOffsetScore(Location origin, Location threatLocation, Location stand) {
        Vector toThreat = threatLocation.toVector().subtract(origin.toVector()).setY(0.0D);
        Vector toStand = stand.toVector().subtract(origin.toVector()).setY(0.0D);
        if (toThreat.lengthSquared() < 0.01D || toStand.lengthSquared() < 0.01D) {
            return 0.0D;
        }
        toThreat.normalize();
        toStand.normalize();
        double alignment = Math.abs(toThreat.dot(toStand));
        return 1.0D - alignment;
    }

    private static Location resolveStandLocation(World world, int x, int z, int centerY) {
        return MobTerrainSupport.snapToStandableColumn(world, x, z, centerY, SCAN_Y_OFFSET);
    }

    private static boolean blocksThreatLineOfSight(LivingEntity threat, Location stand) {
        Location from = RaiderSightSupport.eyeLocation(threat);
        Location to = RaiderSightSupport.eyeLocationAt(stand);
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= 0.05D) {
            return false;
        }
        direction.normalize();
        RayTraceResult result = stand.getWorld().rayTraceBlocks(
                from,
                direction,
                distance,
                FluidCollisionMode.NEVER,
                true
        );
        if (result == null) {
            return false;
        }
        Block block = result.getHitBlock();
        return block != null && block.getType().isOccluding();
    }

    private static Location fallbackCover(Location origin, Location threatLocation) {
        Vector away = origin.toVector().subtract(threatLocation.toVector());
        if (away.lengthSquared() < 0.01D) {
            away = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5D, 0.0D, 1.0D);
        }
        away.setY(0.0D).normalize();
        Vector lateral = new Vector(-away.getZ(), 0.0D, away.getX())
                .multiply(ThreadLocalRandom.current().nextBoolean() ? 1.0D : -1.0D);
        Vector destination = away.multiply(4.0D).add(lateral.multiply(1.5D));
        Location fallback = origin.clone().add(destination);
        Location snapped = snapToStandable(fallback);
        return snapped != null ? snapped : fallback;
    }

    static Location snapToStandable(Location candidate) {
        if (candidate == null) {
            return null;
        }
        Location resolved = resolveStandLocation(
                candidate.getWorld(),
                candidate.getBlockX(),
                candidate.getBlockZ(),
                candidate.getBlockY()
        );
        return resolved != null ? resolved : (MobTerrainSupport.isStandable(candidate) ? candidate : null);
    }
}
