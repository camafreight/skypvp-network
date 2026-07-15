package network.skypvp.paper.ai.navigation;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/** Finds ladder columns and drives mobs up them when goals are overhead. */
public final class MobLadderSupport {

    private static final int SEARCH_RADIUS = 10;
    private static final double CLIMB_VELOCITY = 0.28D;
    private static final double APPROACH_PULL = 0.32D;
    private static final double CENTER_PULL = 0.42D;
    private static final double LADDER_CENTERED_SQ = 0.16D;
    private static final double LADDER_ADJACENT_SQ = 2.25D;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** One climber per column; a claim not refreshed within this window is considered released. */
    private static final long CLAIM_TTL_MILLIS = 1_500L;
    private static final java.util.Map<ColumnKey, Claim> CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    private record ColumnKey(java.util.UUID worldId, int x, int z) {}

    private record Claim(java.util.UUID owner, long tick) {}

    private MobLadderSupport() {
    }

    /**
     * Handles overhead goals via ladder approach + climb. Returns true when this system owns movement.
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
            endClimb(ctx, entity);
            return false;
        }

        LadderColumn attached = findAttachedLadderColumn(feet);
        if (attached != null) {
            if (shouldExitForHorizontalGoal(ctx, goal)) {
                endClimb(ctx, entity);
                return false;
            }
            if (!claimColumn(attached, entity)) {
                // Someone else owns this column: hold position instead of shoving them off.
                endClimb(ctx, entity);
                stopPathfinding(ctx);
                return true;
            }
            beginClimb(ctx, entity);
            stopPathfinding(ctx);
            driveLadderColumn(entity, attached, feet, goal);
            return true;
        }

        LadderColumn column = resolveLadderColumn(ctx, feet, goal);
        if (column == null) {
            endClimb(ctx, entity);
            return false;
        }

        Location target = column.nextWaypoint(feet, goal);
        if (target == null) {
            return false;
        }

        double horizontalSq = horizontalDistanceSq(feet, target);
        if (column.isAdjacent(feet) || horizontalSq <= LADDER_ADJACENT_SQ) {
            if (!claimColumn(column, entity)) {
                // Queue at the base until the current climber finishes.
                endClimb(ctx, entity);
                stopPathfinding(ctx);
                faceToward(entity, column.center(column.baseY));
                return true;
            }
            beginClimb(ctx, entity);
            stopPathfinding(ctx);
            driveLadderColumn(entity, column, feet, target);
            return true;
        }

        endClimb(ctx, entity);
        long tick = ctx.navClock();
        MobNavigationSupport.trackExternalProgress(ctx, target, tick);
        ctx.navigation().lastNavTick = tick;
        ctx.agentMob().getPathfinder().moveTo(target, speed);
        return true;
    }

    public static boolean tickOverheadClimb(NavigatingMobContext ctx, Location goal) {
        if (ctx == null || goal == null) {
            return false;
        }
        if (goal.getY() <= ctx.agentEntity().getLocation().getY() + 1.0D) {
            endClimb(ctx, ctx.agentEntity());
            return false;
        }
        return handleVerticalAccess(ctx, goal, MobNavigationSupport.PATROL_WALK_SPEED);
    }

    /**
     * Marks the entity as the active climber. Collision stays enabled — toggling
     * setCollidable(false) would also make the mob unpickable by vanilla projectile
     * raytraces (bullet-proof mid-climb); the column claim queue is what prevents shoving.
     */
    private static void beginClimb(NavigatingMobContext ctx, LivingEntity entity) {
        ctx.navigation().climbingLadder = true;
    }

    /** Clears climb state and frees any column claim held by this entity. */
    public static void endClimb(NavigatingMobContext ctx, LivingEntity entity) {
        ctx.navigation().climbingLadder = false;
        releaseClaim(entity);
    }

    private static boolean claimColumn(LadderColumn column, LivingEntity entity) {
        ColumnKey key = new ColumnKey(column.world.getUID(), column.x, column.z);
        // Claims are compared ACROSS different mobs, so they need a shared clock;
        // per-agent tick counters and Folia's region-local world time both diverge.
        long now = System.currentTimeMillis();
        java.util.UUID id = entity.getUniqueId();
        Claim result = CLAIMS.compute(key, (k, existing) -> {
            if (existing == null
                    || existing.owner().equals(id)
                    || now - existing.tick() > CLAIM_TTL_MILLIS) {
                return new Claim(id, now);
            }
            return existing;
        });
        return result.owner().equals(id);
    }

    public static void releaseClaim(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        java.util.UUID id = entity.getUniqueId();
        CLAIMS.entrySet().removeIf(entry -> entry.getValue().owner().equals(id));
    }

    /** True when the mob has reached the goal floor and should pathfind horizontally instead of climbing. */
    public static boolean shouldExitForHorizontalGoal(NavigatingMobContext ctx, Location goal) {
        if (ctx == null || goal == null) {
            return false;
        }
        Location feet = ctx.agentEntity().getLocation();
        if (goal.getY() > feet.getY() + 1.5D) {
            return false;
        }
        LadderColumn attached = findAttachedLadderColumn(feet);
        if (attached == null) {
            return true;
        }
        if (feet.getY() + 0.35D < attached.topY) {
            return false;
        }
        Location center = attached.center(attached.topY);
        double goalHorizontalFromLadder = horizontalDistanceSq(center, goal);
        return goalHorizontalFromLadder > 1.0D;
    }

    private static void stopPathfinding(NavigatingMobContext ctx) {
        Mob mob = ctx.agentMob();
        if (mob != null) {
            mob.getPathfinder().stopPathfinding();
        }
    }

    private static void driveLadderColumn(LivingEntity entity, LadderColumn column, Location feet, Location goal) {
        // At the column top: drive onto a standable landing block instead of hugging the ladder.
        if (feet.getBlockY() >= column.topY) {
            Location landing = findTopLanding(column, goal);
            if (landing != null) {
                faceToward(entity, landing);
                Vector toward = landing.toVector().subtract(feet.toVector());
                toward.setY(Math.max(0.28D, toward.getY()));
                if (toward.lengthSquared() > 0.01D) {
                    toward.normalize().multiply(0.34D);
                }
                applyClimb(entity, toward);
                return;
            }
        }
        int climbY = Math.max(column.baseY, Math.min(column.topY, feet.getBlockY()));
        if (feet.getY() + 0.35D >= climbY + 0.9D && climbY < column.topY) {
            climbY++;
        }
        Location center = column.center(Math.min(column.topY, climbY));
        faceToward(entity, center);
        Block feetBlock = feet.getBlock();
        boolean inClimbable = MobTerrainSupport.isClimbableMaterial(feetBlock.getType())
                || MobTerrainSupport.isClimbableMaterial(feetBlock.getRelative(0, 1, 0).getType());
        if (!inClimbable) {
            // Not on the ladder yet: walk into the column without forced lift so mobs
            // can't ride up the face of an adjacent wall.
            Vector towardCenter = center.toVector().subtract(feet.toVector());
            towardCenter.setY(0.0D);
            if (towardCenter.lengthSquared() > 0.01D) {
                towardCenter.normalize().multiply(CENTER_PULL);
                Vector velocity = entity.getVelocity();
                velocity.setX(towardCenter.getX());
                velocity.setZ(towardCenter.getZ());
                entity.setVelocity(velocity);
            }
            return;
        }
        double centerSq = horizontalDistanceSq(feet, center);
        if (centerSq > LADDER_CENTERED_SQ) {
            Vector towardCenter = center.toVector().subtract(feet.toVector());
            towardCenter.setY(Math.max(CLIMB_VELOCITY, towardCenter.getY()));
            if (towardCenter.lengthSquared() > 0.01D) {
                towardCenter.normalize().multiply(CENTER_PULL);
            }
            applyClimb(entity, towardCenter);
            return;
        }
        Vector toward = goal.toVector().subtract(feet.toVector());
        toward.setY(Math.max(CLIMB_VELOCITY, toward.getY()));
        if (toward.lengthSquared() > 0.01D) {
            toward.normalize().multiply(APPROACH_PULL);
        }
        applyClimb(entity, toward);
    }

    /** Standable block beside the column top nearest to the goal — the spot to dismount onto. */
    private static Location findTopLanding(LadderColumn column, Location goal) {
        Location best = null;
        double bestSq = Double.MAX_VALUE;
        for (int[] dir : CARDINAL) {
            Location stand = new Location(
                    column.world,
                    column.x + dir[0] + 0.5D,
                    column.topY + 1,
                    column.z + dir[1] + 0.5D
            );
            if (!MobTerrainSupport.isStandable(stand)) {
                continue;
            }
            double sq = goal != null ? stand.distanceSquared(goal) : 0.0D;
            if (sq < bestSq) {
                bestSq = sq;
                best = stand;
            }
        }
        return best;
    }

    private static LadderColumn findAttachedLadderColumn(Location feet) {
        World world = feet.getWorld();
        if (world == null) {
            return null;
        }
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        LadderColumn best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy = 0; dy <= 1; dy++) {
            Block block = world.getBlockAt(x, y + dy, z);
            if (MobTerrainSupport.isClimbableMaterial(block.getType())) {
                LadderColumn column = LadderColumn.from(block);
                if (column != null) {
                    double sq = horizontalDistanceSq(feet, column.center(y + dy));
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = column;
                    }
                }
            }
        }
        for (int[] offset : CARDINAL) {
            for (int dy = 0; dy <= 1; dy++) {
                Block block = world.getBlockAt(x + offset[0], y + dy, z + offset[1]);
                if (!MobTerrainSupport.isClimbableMaterial(block.getType())) {
                    continue;
                }
                LadderColumn column = LadderColumn.from(block);
                if (column == null) {
                    continue;
                }
                double sq = horizontalDistanceSq(feet, column.center(y + dy));
                if (sq < bestSq) {
                    bestSq = sq;
                    best = column;
                }
            }
        }
        return best;
    }

    /** Cached (TTL + goal-block keyed, misses included) front-end for the ladder box scan. */
    private static LadderColumn resolveLadderColumn(NavigatingMobContext ctx, Location from, Location goal) {
        NavigationTracker navigation = ctx.navigation();
        long tick = ctx.navClock();
        if (navigation.ladderScanGoal != null
                && sameBlock(navigation.ladderScanGoal, goal)
                && tick - navigation.ladderScanTick <= MobStairSupport.ELEVATION_SCAN_TTL_TICKS) {
            if (navigation.ladderScanBlock == null) {
                return null;
            }
            Block cached = navigation.ladderScanBlock.getBlock();
            if (MobTerrainSupport.isClimbableMaterial(cached.getType())) {
                return LadderColumn.from(cached);
            }
            // Ladder was broken since the scan: fall through and rescan.
        }
        navigation.ladderScanTick = tick;
        navigation.ladderScanGoal = goal.clone();
        LadderColumn column = findBestLadderColumn(from, goal);
        navigation.ladderScanBlock = column == null ? null : column.center(column.baseY);
        return column;
    }

    private static boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null
                && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private static LadderColumn findBestLadderColumn(Location from, Location goal) {
        World world = from.getWorld();
        if (world == null || !world.equals(goal.getWorld())) {
            return null;
        }
        LadderColumn best = null;
        double bestScore = Double.MAX_VALUE;
        int goalX = goal.getBlockX();
        int goalZ = goal.getBlockZ();
        // Box centered on the MOB only — spanning mob→goal made this scan explode.
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
                    if (!MobTerrainSupport.isClimbableMaterial(block.getType())) {
                        continue;
                    }
                    LadderColumn column = LadderColumn.from(block);
                    if (column == null || column.topY < from.getBlockY()) {
                        continue;
                    }
                    double score = column.score(from, goal, goalX, goalZ);
                    if (score < bestScore) {
                        bestScore = score;
                        best = column;
                    }
                }
            }
        }
        return best;
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

    private static void applyClimb(LivingEntity entity, Vector lateral) {
        Vector velocity = entity.getVelocity();
        velocity.setY(Math.max(velocity.getY(), CLIMB_VELOCITY));
        if (lateral != null) {
            velocity.setX(lateral.getX());
            velocity.setZ(lateral.getZ());
            if (lateral.getY() > 0.0D) {
                velocity.setY(Math.max(velocity.getY(), lateral.getY()));
            }
        }
        entity.setVelocity(velocity);
    }

    private static double horizontalDistanceSq(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static final class LadderColumn {
        private final World world;
        private final int x;
        private final int z;
        private final int baseY;
        private final int topY;

        private LadderColumn(World world, int x, int z, int baseY, int topY) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.baseY = baseY;
            this.topY = topY;
        }

        private static LadderColumn from(Block ladderBlock) {
            if (ladderBlock == null || ladderBlock.getWorld() == null) {
                return null;
            }
            int x = ladderBlock.getX();
            int z = ladderBlock.getZ();
            int y = ladderBlock.getY();
            while (y > ladderBlock.getWorld().getMinHeight()) {
                Block below = ladderBlock.getWorld().getBlockAt(x, y - 1, z);
                if (!MobTerrainSupport.isClimbableMaterial(below.getType())) {
                    break;
                }
                y--;
            }
            int top = y;
            while (top < ladderBlock.getWorld().getMaxHeight() - 1) {
                Block above = ladderBlock.getWorld().getBlockAt(x, top + 1, z);
                if (!MobTerrainSupport.isClimbableMaterial(above.getType())) {
                    break;
                }
                top++;
            }
            return new LadderColumn(ladderBlock.getWorld(), x, z, y, top);
        }

        private boolean isAdjacent(Location feet) {
            if (feet.getWorld() == null || !feet.getWorld().equals(world)) {
                return false;
            }
            if (feet.getBlockY() < baseY - 1 || feet.getBlockY() > topY + 1) {
                return false;
            }
            return horizontalDistanceSq(feet, center(Math.max(baseY, Math.min(topY, feet.getBlockY())))) <= LADDER_ADJACENT_SQ;
        }

        private double score(Location from, Location goal, int goalX, int goalZ) {
            double horizontalFrom = horizontalDistanceSq(from, center(baseY));
            double horizontalGoal = (goalX - x) * (double) (goalX - x) + (goalZ - z) * (double) (goalZ - z);
            double verticalMiss = Math.max(0.0D, goal.getY() - topY);
            return horizontalFrom + horizontalGoal * 0.35D + verticalMiss * 4.0D;
        }

        private Location nextWaypoint(Location feet, Location goal) {
            if (feet.getY() + 0.5D < baseY) {
                return center(baseY);
            }
            if (isAdjacent(feet) || horizontalDistanceSq(feet, center(feet.getBlockY())) <= LADDER_ADJACENT_SQ) {
                int climbY = Math.max(baseY, Math.min(topY, feet.getBlockY()));
                if (feet.getY() + 0.35D >= climbY + 0.9D && climbY < topY) {
                    climbY++;
                }
                return center(Math.min(topY, Math.max(climbY, baseY)));
            }
            if (horizontalDistanceSq(feet, center(baseY)) > 6.0D) {
                return center(baseY);
            }
            return center(Math.max(baseY, Math.min(topY, feet.getBlockY())));
        }

        private Location center(int y) {
            return new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
        }
    }
}
