package network.skypvp.paper.ai.navigation;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/** Finds stair runs and drives mobs up them when goals are overhead. */
public final class MobStairSupport {

    private static final int SEARCH_RADIUS = 10;
    /** Stair/ladder box scans re-run at most this often per goal block (misses cached too). */
    static final long ELEVATION_SCAN_TTL_TICKS = 40L;
    private static final double ASCENT_VELOCITY = 0.26D;
    private static final double DRIVE_SPEED = 0.36D;
    private static final double ON_STAIR_HORIZONTAL_SQ = 2.5D;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private MobStairSupport() {
    }

    /**
     * Handles overhead goals via stair approach + ascent. Returns true when this system owns movement.
     */
    public static boolean handleVerticalAccess(NavigatingMobContext ctx, Location goal, double speed) {
        if (ctx == null || goal == null) {
            return false;
        }
        LivingEntity entity = ctx.agentEntity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        Location feet = entity.getLocation();
        if (goal.getY() <= feet.getY() + 1.0D) {
            ctx.navigation().climbingStairs = false;
            return false;
        }

        Block groundStair = findGroundStair(feet);
        if (groundStair != null) {
            if (shouldExitForHorizontalGoal(ctx, goal)) {
                ctx.navigation().climbingStairs = false;
                return false;
            }
            // Decorative / dead-end stair: release ownership so flat repath can run.
            if (!MobTerrainSupport.isUsefulElevationStair(groundStair)
                    && nextAscendingUseful(groundStair) == null) {
                ctx.navigation().climbingStairs = false;
                long tick = ctx.navClock();
                MobNavigationSupport.trackExternalProgress(ctx, goal, tick);
                return false;
            }
            Location step = nextStairStand(groundStair, goal);
            if (step != null && driveStairStep(ctx, entity, feet, step, goal)) {
                long tick = ctx.navClock();
                MobNavigationSupport.trackExternalProgress(ctx, step, tick);
                ctx.navigation().lastNavTick = tick;
                return true;
            }
            ctx.navigation().climbingStairs = false;
            return false;
        }

        Location target = resolveStairEntryStand(ctx, feet, goal);
        if (target == null) {
            ctx.navigation().climbingStairs = false;
            return false;
        }

        // Not standing on the stair run yet: always approach the entry through the
        // pathfinder so the mob walks around obstacles instead of being velocity-pushed
        // up the nearest wall face.
        ctx.navigation().climbingStairs = false;
        long tick = ctx.navClock();
        MobNavigationSupport.trackExternalProgress(ctx, target, tick);
        ctx.navigation().lastNavTick = tick;
        ctx.agentMob().getPathfinder().moveTo(target, speed);
        return true;
    }

    public static boolean tickOverheadAscent(NavigatingMobContext ctx, Location goal) {
        if (ctx == null || goal == null) {
            return false;
        }
        if (goal.getY() <= ctx.agentEntity().getLocation().getY() + 1.0D) {
            ctx.navigation().climbingStairs = false;
            return false;
        }
        return handleVerticalAccess(ctx, goal, MobNavigationSupport.PATROL_WALK_SPEED);
    }

    /**
     * Attempts velocity-based movement along stairs toward {@code prepared}. Returns true when driving manually.
     */
    public static boolean tryDrive(NavigatingMobContext ctx, Location prepared, Location goal, double speed) {
        if (ctx == null || prepared == null || goal == null) {
            return false;
        }
        LivingEntity entity = ctx.agentEntity();
        if (entity == null || goal.getY() <= entity.getLocation().getY() + 0.75D) {
            ctx.navigation().climbingStairs = false;
            return false;
        }
        if (!isOnStair(entity.getLocation())) {
            return false;
        }
        Location feet = entity.getLocation();
        Block groundStair = findGroundStair(feet);
        if (groundStair != null
                && !MobTerrainSupport.isUsefulElevationStair(groundStair)
                && nextAscendingUseful(groundStair) == null) {
            ctx.navigation().climbingStairs = false;
            return false;
        }
        Location step = groundStair != null ? nextStairStand(groundStair, prepared) : standPointNear(prepared);
        if (step == null) {
            step = prepared;
        }
        if (!driveStairStep(ctx, entity, feet, step, goal)) {
            return false;
        }
        long tick = ctx.navClock();
        MobNavigationSupport.trackExternalProgress(ctx, step, tick);
        ctx.navigation().lastNavTick = tick;
        return true;
    }

    public static boolean shouldExitForHorizontalGoal(NavigatingMobContext ctx, Location goal) {
        if (ctx == null || goal == null) {
            return false;
        }
        Location feet = ctx.agentEntity().getLocation();
        if (goal.getY() > feet.getY() + 1.5D) {
            return false;
        }
        return horizontalDistanceSq(feet, goal) > 1.0D;
    }

    private static void stopPathfinding(NavigatingMobContext ctx) {
        Mob mob = ctx.agentMob();
        if (mob != null) {
            mob.getPathfinder().stopPathfinding();
        }
    }

    /**
     * Velocity-drives one stair step. Only takes ownership (returns true) when the aim
     * point is an adjacent, passable step — anything further or blocked is left to the
     * pathfinder so mobs can never be pushed up a wall face.
     */
    private static boolean driveStairStep(
            NavigatingMobContext ctx,
            LivingEntity entity,
            Location feet,
            Location step,
            Location goal
    ) {
        Location aim = step;
        if (horizontalDistanceSq(feet, step) <= 0.25D) {
            Block ground = findGroundStair(feet);
            Location ahead = ground != null ? nextStairStand(ground, goal) : null;
            if (ahead != null && ahead.getY() >= feet.getY() - 0.1D) {
                aim = ahead;
            }
        }
        if (!isDrivableStep(feet, aim)) {
            ctx.navigation().climbingStairs = false;
            return false;
        }
        // Dead-end: no ascending progress and aim is essentially the same stand.
        boolean ascending = aim.getY() > feet.getY() + 0.05D;
        if (!ascending && horizontalDistanceSq(feet, aim) <= 0.25D) {
            Block ground = findGroundStair(feet);
            if (ground == null || !MobTerrainSupport.isUsefulElevationStair(ground)) {
                ctx.navigation().climbingStairs = false;
                return false;
            }
        }
        ctx.navigation().climbingStairs = true;
        stopPathfinding(ctx);
        faceToward(entity, aim);
        Vector toward = aim.toVector().subtract(feet.toVector());
        if (ascending) {
            toward.setY(Math.max(ASCENT_VELOCITY, toward.getY()));
        }
        if (toward.lengthSquared() > 0.01D) {
            toward.normalize().multiply(DRIVE_SPEED);
        } else if (ascending) {
            toward.setY(ASCENT_VELOCITY);
        } else {
            // No displacement and not ascending — release so we don't freeze on planter lips.
            ctx.navigation().climbingStairs = false;
            return false;
        }
        Vector velocity = entity.getVelocity();
        velocity.setX(toward.getX());
        velocity.setZ(toward.getZ());
        velocity.setY(Math.max(velocity.getY(), toward.getY()));
        entity.setVelocity(velocity);
        return true;
    }

    /** A step is drivable when it is one walkable stair step away with room to stand. */
    private static boolean isDrivableStep(Location feet, Location aim) {
        if (aim == null || aim.getWorld() == null || !aim.getWorld().equals(feet.getWorld())) {
            return false;
        }
        double dy = aim.getY() - feet.getY();
        if (dy > 1.2D || dy < -1.5D) {
            return false;
        }
        if (horizontalDistanceSq(feet, aim) > ON_STAIR_HORIZONTAL_SQ) {
            return false;
        }
        Block atAim = aim.getBlock();
        return atAim.isPassable() && atAim.getRelative(0, 1, 0).isPassable();
    }

    private static Block findGroundStair(Location feet) {
        World world = feet.getWorld();
        if (world == null) {
            return null;
        }
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        // Own column only: standing beside a staircase (wall or railing between) must not
        // count as being on it, or the velocity drive pushes the mob up the wall face.
        for (int dy = 0; dy >= -1; dy--) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (Tag.STAIRS.isTagged(block.getType())) {
                return block;
            }
        }
        return null;
    }

    /** Cached (TTL + goal-block keyed, misses included) front-end for the stair-entry box scan. */
    private static Location resolveStairEntryStand(NavigatingMobContext ctx, Location from, Location goal) {
        NavigationTracker navigation = ctx.navigation();
        long tick = ctx.navClock();
        if (navigation.stairScanGoal != null
                && sameBlock(navigation.stairScanGoal, goal)
                && tick - navigation.stairScanTick <= ELEVATION_SCAN_TTL_TICKS) {
            return navigation.stairEntryStand == null ? null : navigation.stairEntryStand.clone();
        }
        navigation.stairScanTick = tick;
        navigation.stairScanGoal = goal.clone();
        Block entry = findBestStairEntry(from, goal);
        Location stand = entry == null ? null : standPoint(entry);
        navigation.stairEntryStand = stand == null ? null : stand.clone();
        return stand;
    }

    private static boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null
                && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private static Block findBestStairEntry(Location from, Location goal) {
        World world = from.getWorld();
        if (world == null || !world.equals(goal.getWorld())) {
            return null;
        }
        Block best = null;
        double bestScore = Double.MAX_VALUE;
        // Box centered on the MOB only: entries it can use are near it, and spanning
        // mob→goal made the scan explode to tens of thousands of block reads.
        int minX = from.getBlockX() - SEARCH_RADIUS;
        int maxX = from.getBlockX() + SEARCH_RADIUS;
        int minZ = from.getBlockZ() - SEARCH_RADIUS;
        int maxZ = from.getBlockZ() + SEARCH_RADIUS;
        int minY = Math.max(world.getMinHeight(), from.getBlockY() - 2);
        int maxY = Math.min(world.getMaxHeight() - 1,
                Math.min(goal.getBlockY() + 4, from.getBlockY() + 16));

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!Tag.STAIRS.isTagged(block.getType())) {
                        continue;
                    }
                    if (!MobTerrainSupport.isUsefulElevationStair(block)) {
                        continue;
                    }
                    Location stand = standPoint(block);
                    if (stand == null || stand.getY() < from.getY() - 1.5D) {
                        continue;
                    }
                    // Approach targets must be steppable from the mob's floor (the bottom of
                    // the run); mid-staircase blocks overhead would point the mob at a wall.
                    if (stand.getY() > from.getY() + 1.75D) {
                        continue;
                    }
                    // Decorative stairs (roof trim, benches) don't ascend anywhere — require
                    // the run to actually gain elevation before committing to it.
                    if (stairRunRise(block) < 1) {
                        continue;
                    }
                    double score = horizontalDistanceSq(from, stand)
                            + stand.distanceSquared(goal) * 0.25D
                            + Math.max(0.0D, from.getY() - stand.getY()) * 6.0D
                            + Math.max(0.0D, goal.getY() - stand.getY() - 8.0D) * 2.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    private static Block nextAscendingUseful(Block stair) {
        if (stair == null || !(stair.getBlockData() instanceof Stairs data)) {
            return null;
        }
        World world = stair.getWorld();
        BlockFace face = data.getFacing();
        Block candidate = world.getBlockAt(
                stair.getX() + face.getModX(),
                stair.getY() + 1,
                stair.getZ() + face.getModZ()
        );
        if (Tag.STAIRS.isTagged(candidate.getType()) && MobTerrainSupport.isUsefulElevationStair(candidate)) {
            return candidate;
        }
        candidate = world.getBlockAt(
                stair.getX() + face.getModX(),
                stair.getY(),
                stair.getZ() + face.getModZ()
        );
        if (Tag.STAIRS.isTagged(candidate.getType()) && MobTerrainSupport.isUsefulElevationStair(candidate)) {
            return candidate;
        }
        return null;
    }

    /**
     * Total elevation a stair run gains within a few steps of this block. Functional
     * staircases chain upward toward their facing; decorative stairs score 0.
     */
    private static int stairRunRise(Block stair) {
        int rise = 0;
        Block current = stair;
        for (int step = 0; step < 6 && current != null; step++) {
            Block next = ascendingNeighbor(current);
            if (next == null) {
                break;
            }
            rise += next.getY() - current.getY();
            current = next;
        }
        return rise;
    }

    /** Next stair block continuing the run in the ascending (facing) direction. */
    private static Block ascendingNeighbor(Block stair) {
        if (!(stair.getBlockData() instanceof Stairs data)
                || data.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
            return null;
        }
        BlockFace face = data.getFacing();
        Block up = stair.getWorld().getBlockAt(
                stair.getX() + face.getModX(), stair.getY() + 1, stair.getZ() + face.getModZ());
        if (Tag.STAIRS.isTagged(up.getType())) {
            return up;
        }
        Block flat = stair.getWorld().getBlockAt(
                stair.getX() + face.getModX(), stair.getY(), stair.getZ() + face.getModZ());
        if (Tag.STAIRS.isTagged(flat.getType())) {
            return flat;
        }
        return null;
    }

    private static Location nextStairStand(Block stair, Location goal) {
        if (stair == null || stair.getWorld() == null) {
            return null;
        }
        World world = stair.getWorld();
        int x = stair.getX();
        int y = stair.getY();
        int z = stair.getZ();

        Location best = null;
        double bestScore = Double.MAX_VALUE;

        if (stair.getBlockData() instanceof Stairs data) {
            BlockFace face = data.getFacing();
            int fx = face.getModX();
            int fz = face.getModZ();
            Block[] candidates = {
                    world.getBlockAt(x + fx, y + 1, z + fz),
                    world.getBlockAt(x + fx, y, z + fz),
                    world.getBlockAt(x, y + 1, z),
                    world.getBlockAt(x - fx, y, z - fz),
                    world.getBlockAt(x, y, z),
            };
            for (Block candidate : candidates) {
                if (!Tag.STAIRS.isTagged(candidate.getType())) {
                    continue;
                }
                // Prefer useful runs; allow one adjacent decorative step only if already mid-climb.
                if (!MobTerrainSupport.isUsefulElevationStair(candidate)
                        && !MobTerrainSupport.isUsefulElevationStair(stair)) {
                    continue;
                }
                Location stand = standPoint(candidate);
                if (stand == null) {
                    continue;
                }
                double score = stand.distanceSquared(goal) + horizontalDistanceSq(stair.getLocation(), stand) * 0.15D;
                if (stand.getY() >= y - 0.1D) {
                    score -= (stand.getY() - y) * 4.0D;
                }
                if (!MobTerrainSupport.isUsefulElevationStair(candidate)) {
                    score += 40.0D;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = stand;
                }
            }
        }

        if (best != null) {
            return best;
        }
        return standPoint(stair);
    }

    private static Location standPoint(Block stair) {
        return MobTerrainSupport.standOnElevationAid(stair);
    }

    private static Location standPointNear(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
        if (Tag.STAIRS.isTagged(block.getType())) {
            return standPoint(block);
        }
        block = world.getBlockAt(location);
        if (Tag.STAIRS.isTagged(block.getType())) {
            return standPoint(block);
        }
        return null;
    }

    private static boolean isOnStair(Location feet) {
        return findGroundStair(feet) != null;
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

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }
}
