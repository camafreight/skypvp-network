package network.skypvp.paper.ai.navigation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

/** Standability checks and ground snapping for mob navigation. */
public final class MobTerrainSupport {

    private static final int DEFAULT_Y_SCAN = 12;
    private static final int STAIR_RUN_MAX_STEPS = 10;
    private static final double DEFAULT_MIN_LANDING_GAIN = 1.0D;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private MobTerrainSupport() {
    }

    public static boolean isStandable(Location feet) {
        if (feet == null || feet.getWorld() == null) {
            return false;
        }
        Block ground = feet.getBlock().getRelative(0, -1, 0);
        Block atFeet = feet.getBlock();
        Block head = atFeet.getRelative(0, 1, 0);
        Block aboveHead = head.getRelative(0, 1, 0);
        return isValidGround(ground.getType())
                && atFeet.isPassable()
                && head.isPassable()
                && aboveHead.isPassable();
    }

    public static boolean isElevationAid(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (isClimbableMaterial(type)) {
            return true;
        }
        return Tag.STAIRS.isTagged(type) && isUsefulElevationStair(block);
    }

    /**
     * True when this stair is part of a real ascent: following the run reaches a higher
     * continuing walkable floor. Decorative planter stairs / dead-end stair trim fail this.
     */
    public static boolean isUsefulElevationStair(Block stair) {
        return isUsefulElevationStair(stair, DEFAULT_MIN_LANDING_GAIN);
    }

    public static boolean isUsefulElevationStair(Block stair, double minLandingGain) {
        if (stair == null || !Tag.STAIRS.isTagged(stair.getType())) {
            return false;
        }
        World world = stair.getWorld();
        if (world == null) {
            return false;
        }
        double baseY = stair.getY();
        Block cursor = stair;
        Block last = stair;
        for (int step = 0; step < STAIR_RUN_MAX_STEPS; step++) {
            Block next = nextAscendingStair(cursor);
            if (next == null || next.equals(cursor)) {
                break;
            }
            last = next;
            cursor = next;
        }
        Location landing = findContinuingLanding(last);
        if (landing == null) {
            return false;
        }
        return landing.getY() - baseY >= minLandingGain - 0.01D;
    }

    /**
     * Isolated elevated lips (planter tops, single slab islands) that mobs should not
     * treat as navigation destinations.
     */
    public static boolean isIsolatedElevationLip(Location feet) {
        if (!isStandable(feet)) {
            return false;
        }
        Block ground = feet.getBlock().getRelative(0, -1, 0);
        Material groundType = ground.getType();
        boolean lipSurface = Tag.STAIRS.isTagged(groundType) || Tag.SLABS.isTagged(groundType);
        if (!lipSurface) {
            return false;
        }
        return !hasContinuingSurface(feet);
    }

    public static boolean hasContinuingSurface(Location feet) {
        if (feet == null || feet.getWorld() == null) {
            return false;
        }
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        int continuing = 0;
        for (int[] dir : CARDINAL) {
            Location neighbor = new Location(world, x + dir[0] + 0.5D, y, z + dir[1] + 0.5D);
            if (!isStandable(neighbor)) {
                continue;
            }
            Block neighborGround = neighbor.getBlock().getRelative(0, -1, 0);
            // Prefer real floors over another decorative stair/slab ring.
            if (neighborGround.getType().isSolid()
                    && !Tag.STAIRS.isTagged(neighborGround.getType())
                    && !Tag.SLABS.isTagged(neighborGround.getType())) {
                continuing += 2;
            } else {
                continuing++;
            }
        }
        return continuing >= 2;
    }

    private static Block nextAscendingStair(Block stair) {
        if (stair == null || !(stair.getBlockData() instanceof Stairs data)) {
            return null;
        }
        World world = stair.getWorld();
        BlockFace face = data.getFacing();
        int x = stair.getX();
        int y = stair.getY();
        int z = stair.getZ();
        Block[] candidates = {
                world.getBlockAt(x + face.getModX(), y + 1, z + face.getModZ()),
                world.getBlockAt(x + face.getModX(), y, z + face.getModZ()),
                world.getBlockAt(x, y + 1, z),
        };
        for (Block candidate : candidates) {
            if (Tag.STAIRS.isTagged(candidate.getType()) && candidate.getY() >= y) {
                return candidate;
            }
        }
        return null;
    }

    private static Location findContinuingLanding(Block stair) {
        if (stair == null || stair.getWorld() == null) {
            return null;
        }
        World world = stair.getWorld();
        int x = stair.getX();
        int y = stair.getY();
        int z = stair.getZ();
        BlockFace face = stair.getBlockData() instanceof Stairs data ? data.getFacing() : BlockFace.NORTH;
        int[][] probes = {
                {face.getModX(), 1, face.getModZ()},
                {face.getModX(), 0, face.getModZ()},
                {0, 1, 0},
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        };
        Location best = null;
        double bestY = Double.NEGATIVE_INFINITY;
        for (int[] probe : probes) {
            Block block = world.getBlockAt(x + probe[0], y + probe[1], z + probe[2]);
            // Landing must be a real floor (or top slab with continuing surface), not another stair.
            if (Tag.STAIRS.isTagged(block.getType())) {
                continue;
            }
            Location stand = null;
            if (block.getType().isSolid() || Tag.SLABS.isTagged(block.getType())) {
                stand = new Location(world, block.getX() + 0.5D, block.getY() + 1.0D, block.getZ() + 0.5D);
                if (!isStandable(stand)) {
                    stand = new Location(world, block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
                }
            }
            if (stand == null || !isStandable(stand) || !hasContinuingSurface(stand)) {
                continue;
            }
            if (stand.getY() > bestY) {
                bestY = stand.getY();
                best = stand;
            }
        }
        return best;
    }

    public static boolean isClimbableMaterial(Material type) {
        return type == Material.LADDER
                || type == Material.VINE
                || type == Material.TWISTING_VINES
                || type == Material.WEEPING_VINES
                || type == Material.SCAFFOLDING
                || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT;
    }

    /** Stand location that routes the pathfinder onto stairs or into a climbable column. */
    public static Location standOnElevationAid(Block block) {
        if (block == null || block.getWorld() == null) {
            return null;
        }
        Material type = block.getType();
        if (isClimbableMaterial(type)) {
            return new Location(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
        }
        if (Tag.STAIRS.isTagged(type)) {
            World world = block.getWorld();
            int x = block.getX();
            int z = block.getZ();
            int y = block.getY();
            for (double feetY : new double[] {y + 1.0D, y + 0.5D}) {
                Location top = new Location(world, x + 0.5D, feetY, z + 0.5D);
                if (isStandable(top)) {
                    return top;
                }
            }
            return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
        }
        return null;
    }

    private static boolean isValidGround(Material type) {
        return type.isSolid() || Tag.STAIRS.isTagged(type) || Tag.SLABS.isTagged(type);
    }

    public static Location snapToStandableNear(Location candidate) {
        return snapToStandableNear(candidate, DEFAULT_Y_SCAN);
    }

    public static Location snapToStandableNear(Location candidate, int yRadius) {
        if (candidate == null || candidate.getWorld() == null) {
            return null;
        }
        int centerY = candidate.getBlockY();
        Location best = null;
        double bestDelta = Double.MAX_VALUE;
        for (int dy = yRadius; dy >= -yRadius; dy--) {
            Location probe = new Location(
                    candidate.getWorld(),
                    candidate.getBlockX() + 0.5D,
                    centerY + dy,
                    candidate.getBlockZ() + 0.5D
            );
            if (!isStandable(probe) || isIsolatedElevationLip(probe)) {
                continue;
            }
            double delta = Math.abs(probe.getY() - candidate.getY());
            if (delta < bestDelta) {
                bestDelta = delta;
                best = probe;
            }
        }
        return best;
    }

    /**
     * Snaps near {@code candidate} but only within a vertical band around {@code preferredY}.
     * Prevents selecting an elevated balcony floor while the mob is still on ground level.
     */
    public static Location snapToStandableNear(Location candidate, int yRadius, double preferredY, double maxYDelta) {
        if (candidate == null || candidate.getWorld() == null) {
            return null;
        }
        int centerY = (int) Math.floor(preferredY);
        Location best = null;
        double bestDelta = Double.MAX_VALUE;
        for (int dy = yRadius; dy >= -yRadius; dy--) {
            double probeY = centerY + dy;
            if (Math.abs(probeY - preferredY) > maxYDelta) {
                continue;
            }
            Location probe = new Location(
                    candidate.getWorld(),
                    candidate.getBlockX() + 0.5D,
                    probeY,
                    candidate.getBlockZ() + 0.5D
            );
            if (!isStandable(probe) || isIsolatedElevationLip(probe)) {
                continue;
            }
            double delta = Math.abs(probe.getY() - preferredY);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = probe;
            }
        }
        return best;
    }

    public static Location snapToStandableColumn(World world, int x, int z, int centerY, int yRadius) {
        if (world == null) {
            return null;
        }
        for (int dy = yRadius; dy >= -yRadius; dy--) {
            Location candidate = new Location(world, x + 0.5D, centerY + dy, z + 0.5D);
            if (isStandable(candidate) && !isIsolatedElevationLip(candidate)) {
                return candidate;
            }
        }
        // Fall back to any standable column if every candidate was a lip.
        for (int dy = yRadius; dy >= -yRadius; dy--) {
            Location candidate = new Location(world, x + 0.5D, centerY + dy, z + 0.5D);
            if (isStandable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
