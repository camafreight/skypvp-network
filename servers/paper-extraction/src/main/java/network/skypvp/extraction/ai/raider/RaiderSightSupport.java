package network.skypvp.extraction.ai.raider;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Line-of-sight checks and last-known target memory for gunner targeting. */
public final class RaiderSightSupport {

    private static final double EYE_HEIGHT = 1.45D;
    /** Un-sneaked targets this close are heard even outside the vision cone. */
    private static final double PERIPHERAL_AWARENESS_RANGE_SQ = 7.0D * 7.0D;
    /** Omnidirectional awareness window during an active exchange with the same player. */
    private static final long ACTIVE_COMBAT_AWARENESS_TICKS = 60L;

    private RaiderSightSupport() {
    }

    static boolean canSeeTarget(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        // One full sight evaluation (awareness + block raytrace) per target per AI tick;
        // repeat calls within the tick reuse the memo. observeTarget already ran on the
        // first positive result this tick, so skipping it on cache hits changes nothing.
        long tick = ctx.aiTick;
        if (ctx.losCacheTick == tick && target.getUniqueId().equals(ctx.losCacheTargetId)) {
            return ctx.losCacheResult;
        }
        boolean seen = evaluateSight(ctx, target);
        ctx.losCacheTick = tick;
        ctx.losCacheTargetId = target.getUniqueId();
        ctx.losCacheResult = seen;
        return seen;
    }

    private static boolean evaluateSight(RaiderAgentContext ctx, LivingEntity target) {
        if (target instanceof Player player
                && ctx.playerTargetGate != null
                && !ctx.playerTargetGate.allows(player)) {
            return false;
        }
        if (!withinAwareness(ctx, target)) {
            return false;
        }
        if (rayTraceClear(ctx.entity, target)) {
            observeTarget(ctx, target);
            return true;
        }
        return false;
    }

    /**
     * The vision cone applies to live tracking, not just acquisition: a target behind the
     * gunner is only "seen" while the gunner is under fire, mid-exchange with that player,
     * or the target is close enough to hear (sneaking players are silent). This is what
     * lets a player break contact and flank instead of facing 360-degree awareness.
     */
    private static boolean withinAwareness(RaiderAgentContext ctx, LivingEntity target) {
        if (withinAcquisitionFov(ctx, target)) {
            return true;
        }
        long tick = ctx.aiTick;
        if (ctx.underFire(tick)) {
            return true;
        }
        boolean sneaking = target instanceof Player player && player.isSneaking();
        if (!sneaking
                && ctx.entity.getLocation().distanceSquared(target.getLocation())
                        <= PERIPHERAL_AWARENESS_RANGE_SQ) {
            return true;
        }
        return target instanceof Player player
                && ctx.lastCombatPlayerId != null
                && player.getUniqueId().equals(ctx.lastCombatPlayerId)
                && tick - ctx.lastCombatPlayerTick <= ACTIVE_COMBAT_AWARENESS_TICKS;
    }

    /**
     * New-threat acquisition: requires LOS plus forward FOV unless already engaged / under fire.
     * Once a target is locked, {@link #canSeeTarget} keeps the same awareness rules.
     */
    static boolean canAcquireTarget(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (isAlreadyEngagedWith(ctx, target)) {
            return canSeeTarget(ctx, target);
        }
        long tick = ctx.aiTick;
        if (ctx.underFire(tick)) {
            return canSeeTarget(ctx, target);
        }
        if (!withinAcquisitionFov(ctx, target)) {
            return false;
        }
        return canSeeTarget(ctx, target);
    }

    /** Commanded facings stay authoritative for this long before entity yaw is trusted again. */
    private static final long AI_FACING_FRESH_TICKS = 20L;

    static boolean withinAcquisitionFov(RaiderAgentContext ctx, LivingEntity target) {
        if (ctx == null || target == null) {
            return false;
        }
        Location origin = ctx.entity.getLocation();
        Location targetFeet = target.getLocation();
        double dx = targetFeet.getX() - origin.getX();
        double dz = targetFeet.getZ() - origin.getZ();
        if (dx * dx + dz * dz < 0.04D) {
            return true;
        }
        // NMS body-rotation control can leave a stationary mob's entity yaw stale, so the
        // facing the AI last commanded wins while fresh; entity yaw covers movement facing.
        long tick = ctx.aiTick;
        float yaw = ctx.aiFacingTick > 0L && tick - ctx.aiFacingTick <= AI_FACING_FRESH_TICKS
                ? ctx.aiFacingYaw
                : origin.getYaw();
        double facingX = -Math.sin(Math.toRadians(yaw));
        double facingZ = Math.cos(Math.toRadians(yaw));
        double length = Math.hypot(dx, dz);
        double dot = (facingX * dx + facingZ * dz) / length;
        double halfFov = Math.toRadians(ctx.profile.acquisitionFovDegrees() * 0.5D);
        return dot >= Math.cos(halfFov);
    }

    private static boolean isAlreadyEngagedWith(RaiderAgentContext ctx, LivingEntity target) {
        LivingEntity current = ctx.mob.getTarget();
        if (current != null && current.isValid() && current.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        if (target instanceof Player player
                && ctx.lastCombatPlayerId != null
                && player.getUniqueId().equals(ctx.lastCombatPlayerId)) {
            return true;
        }
        return false;
    }

    /** Gunfire requires a clear eye ray; corner-melee may use {@link #withinStrikeRange} instead. */
    static boolean canEngageWithGun(RaiderAgentContext ctx, LivingEntity target) {
        return canSeeTarget(ctx, target) && withinEngageRange(ctx, target);
    }

    static void observeTarget(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return;
        }
        if (target instanceof Player player
                && ctx.playerTargetGate != null
                && !ctx.playerTargetGate.allows(player)) {
            return;
        }
        observeThreatAt(ctx, target.getLocation(), ctx.aiTick);
    }

    static void observeThreatAt(RaiderAgentContext ctx, Location location, long tick) {
        if (ctx == null || location == null || location.getWorld() == null) {
            return;
        }
        ctx.lastKnownTargetLocation = location.clone();
        ctx.lastSeenTargetTick = tick;
    }

    static void expireIntel(RaiderAgentContext ctx) {
        ctx.lastKnownTargetLocation = null;
        ctx.lastSeenTargetTick = 0L;
    }

    static boolean canInvestigate(RaiderAgentContext ctx, long tick) {
        return hasFreshLastKnown(ctx, tick) && tick >= ctx.investigateCooldownUntilTick;
    }

    static void beginInvestigateCooldown(RaiderAgentContext ctx, long tick) {
        expireIntel(ctx);
        ctx.investigateCooldownUntilTick = tick + RaiderAgentContext.INVESTIGATE_COOLDOWN_TICKS;
    }

    static boolean hasFreshLastKnown(RaiderAgentContext ctx, long tick) {
        return ctx.lastKnownTargetLocation != null
                && tick - ctx.lastSeenTargetTick <= RaiderAgentContext.LAST_KNOWN_TTL_TICKS;
    }

    public static Location eyeLocation(LivingEntity entity) {
        Location location = entity.getLocation().clone();
        location.setY(location.getY() + EYE_HEIGHT);
        return location;
    }

    public static Location eyeLocationAt(Location feet) {
        Location location = feet.clone();
        location.setY(location.getY() + EYE_HEIGHT);
        return location;
    }

    static boolean withinEngageRange(RaiderAgentContext ctx, LivingEntity target) {
        double distanceSq = ctx.entity.getLocation().distanceSquared(target.getLocation());
        return distanceSq <= ctx.profile.engageRangeSq() && distanceSq >= ctx.profile.minEngageRangeSq();
    }

    static boolean withinMeleeRange(RaiderAgentContext ctx, LivingEntity target) {
        return withinReach(ctx, target, ctx.profile.meleeRangeSq())
                || withinHorizontalMeleeReach(ctx, target, ctx.profile.meleeRangeSq());
    }

    /** Knife only with clear LOS (or a short melee ray) and real reach — never through walls. */
    static boolean canInitiateMelee(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (!withinMeleeRange(ctx, target)) {
            return false;
        }
        return hasMeleeLos(ctx, target);
    }

    /** True when a knife strike would not pass through solid blocks. */
    static boolean hasMeleeLos(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead() || ctx == null || ctx.entity == null) {
            return false;
        }
        return rayTraceClear(ctx.entity, target);
    }

    /** Strike gate used by melee combat — range + LOS. */
    static boolean canStrikeTarget(RaiderAgentContext ctx, LivingEntity target) {
        return withinStrikeRange(ctx, target) && hasMeleeLos(ctx, target);
    }

    /** True when the target is directly above or below with no valid melee path. */
    static boolean isSeparatedVertically(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        double dy = target.getLocation().getY() - ctx.entity.getLocation().getY();
        return Math.abs(dy) > 2.25D;
    }

    /** Wider 3D reach so pathfinder stop distance still lands a strike. */
    static boolean withinStrikeRange(RaiderAgentContext ctx, LivingEntity target) {
        double reach = Math.sqrt(ctx.profile.meleeRangeSq()) + 1.25D;
        double reachSq = reach * reach;
        return withinReach(ctx, target, reachSq)
                || withinHorizontalMeleeReach(ctx, target, reachSq);
    }

    /** Corner-melee only when roughly on the same floor. */
    static boolean withinHorizontalMeleeReach(RaiderAgentContext ctx, LivingEntity target, double horizontalRangeSq) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        Location origin = ctx.entity.getLocation();
        Location targetFeet = target.getLocation();
        double dx = origin.getX() - targetFeet.getX();
        double dz = origin.getZ() - targetFeet.getZ();
        if (dx * dx + dz * dz > horizontalRangeSq) {
            return false;
        }
        return sameFloorProximity(ctx, target);
    }

    static boolean sameFloorProximity(RaiderAgentContext ctx, LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        Location origin = ctx.entity.getLocation();
        Location targetFeet = target.getLocation();
        return Math.abs(origin.getY() - targetFeet.getY()) <= 2.25D;
    }

    private static boolean withinReach(RaiderAgentContext ctx, LivingEntity target, double rangeSq) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        return ctx.entity.getLocation().distanceSquared(target.getLocation()) <= rangeSq;
    }

    private static boolean withinHorizontalRange(RaiderAgentContext ctx, LivingEntity target, double horizontalRangeSq) {
        Location origin = ctx.entity.getLocation();
        Location targetFeet = target.getLocation();
        double dx = origin.getX() - targetFeet.getX();
        double dz = origin.getZ() - targetFeet.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq > horizontalRangeSq) {
            return false;
        }
        return Math.abs(origin.getY() - targetFeet.getY()) <= 2.5D;
    }

    static boolean tooCloseForGun(RaiderAgentContext ctx, LivingEntity target) {
        return ctx.entity.getLocation().distanceSquared(target.getLocation()) < ctx.profile.minEngageRangeSq();
    }

    private static boolean rayTraceClear(LivingEntity observer, LivingEntity target) {
        Location origin = eyeLocation(observer);
        Location eyeTarget = eyeLocation(target);
        if (rayTraceClear(origin, eyeTarget)) {
            return true;
        }
        Location bodyTarget = target.getLocation().clone();
        bodyTarget.setY(bodyTarget.getY() + target.getHeight() * 0.55D);
        return rayTraceClear(origin, bodyTarget);
    }

    private static boolean rayTraceClear(Location origin, Location destination) {
        Vector direction = destination.toVector().subtract(origin.toVector());
        double distance = direction.length();
        if (distance <= 0.05D) {
            return true;
        }
        direction.normalize();
        World world = origin.getWorld();
        if (world == null) {
            return false;
        }
        RayTraceResult result = world.rayTraceBlocks(
                origin,
                direction,
                distance,
                FluidCollisionMode.NEVER,
                true
        );
        if (result == null) {
            return true;
        }
        double hitDistance = result.getHitPosition().distance(origin.toVector());
        return hitDistance >= distance - 0.35D;
    }

}
