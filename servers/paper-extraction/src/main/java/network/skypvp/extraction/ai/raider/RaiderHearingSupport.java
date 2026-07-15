package network.skypvp.extraction.ai.raider;

import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.gameplay.BreachGunfireTracker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Gunshot hearing for gunners that are not looking at the shooter. */
final class RaiderHearingSupport {

    private static final double HEARING_RADIUS_BLOCKS = 45.0D;
    private static final double OCCLUDED_HEARING_RADIUS_BLOCKS = 22.0D;
    private static final long HEARING_WINDOW_MS = 3_500L;

    private RaiderHearingSupport() {
    }

    static void scan(RaiderAgentContext ctx, BreachGunfireTracker tracker) {
        if (ctx == null || tracker == null || ctx.entity == null || !ctx.entity.isValid() || ctx.entity.isDead()) {
            return;
        }
        LivingEntity entity = ctx.entity;
        long tick = ctx.aiTick;
        Optional<BreachGunfireTracker.GunfirePing> heard =
                tracker.nearestRecent(entity.getLocation(), HEARING_RADIUS_BLOCKS, HEARING_WINDOW_MS);
        if (heard.isEmpty()) {
            return;
        }
        BreachGunfireTracker.GunfirePing ping = heard.get();
        if (ping.timestampMillis() <= ctx.lastHeardGunfireMs) {
            return;
        }
        Player shooter = resolveShooter(ping.shooterId());
        if (shooter == null) {
            return;
        }
        if (isEngagedWithShooter(ctx, shooter)) {
            return;
        }
        World world = entity.getWorld();
        Location heardAt = ping.location(world);
        if (heardAt == null) {
            return;
        }
        double distance = entity.getLocation().distance(heardAt);
        boolean clearPath = hearingPathClear(entity.getLocation(), heardAt);
        if (!clearPath && distance > OCCLUDED_HEARING_RADIUS_BLOCKS) {
            return;
        }
        ctx.lastHeardGunfireMs = ping.timestampMillis();
        faceToward(entity, heardAt);
        RaiderSightSupport.observeThreatAt(ctx, heardAt, tick);
        RaiderCombatTargets.assign(ctx.mob, shooter, ctx.playerTargetGate);
    }

    private static boolean hearingPathClear(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= 0.5D) {
            return true;
        }
        direction.normalize();
        RayTraceResult result = from.getWorld().rayTraceBlocks(
                from.clone().add(0.0D, 1.4D, 0.0D),
                direction,
                distance,
                FluidCollisionMode.NEVER,
                true
        );
        return result == null;
    }

    private static void faceToward(LivingEntity entity, Location target) {
        Location origin = entity.getLocation();
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        if (dx * dx + dz * dz < 0.0001D) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.setRotation(yaw, entity.getLocation().getPitch());
    }

    private static boolean isEngagedWithShooter(RaiderAgentContext ctx, Player shooter) {
        LivingEntity target = ctx.mob.getTarget();
        if (!(target instanceof Player current) || !current.getUniqueId().equals(shooter.getUniqueId())) {
            return false;
        }
        return RaiderSightSupport.canSeeTarget(ctx, current)
                && RaiderSightSupport.withinEngageRange(ctx, current);
    }

    private static Player resolveShooter(UUID shooterId) {
        if (shooterId == null) {
            return null;
        }
        Player player = Bukkit.getPlayer(shooterId);
        if (player == null || !player.isValid() || player.isDead()) {
            return null;
        }
        return player;
    }
}
