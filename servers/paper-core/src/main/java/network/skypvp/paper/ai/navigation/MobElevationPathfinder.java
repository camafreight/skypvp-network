package network.skypvp.paper.ai.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Block-grid BFS that understands stairs and ladders for multi-floor routes. */
public final class MobElevationPathfinder {

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_NODES = 4096;
    private static final int HORIZONTAL_RADIUS = 24;
    private static final int VERTICAL_RADIUS = 16;

    private MobElevationPathfinder() {
    }

    public static List<Location> planRoute(Location from, Location goal) {
        if (from == null || goal == null || from.getWorld() == null || goal.getWorld() == null) {
            return List.of();
        }
        if (!from.getWorld().equals(goal.getWorld())) {
            return List.of();
        }
        World world = from.getWorld();
        Node start = nodeAtFeet(from);
        Node target = nodeAtFeet(goal);
        if (start.equals(target)) {
            return List.of(toLocation(world, target));
        }

        ArrayDeque<Node> queue = new ArrayDeque<>();
        Map<Long, Node> parents = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        queue.add(start);
        visited.add(start.key());

        Node best = null;
        double bestScore = Double.MAX_VALUE;
        int explored = 0;

        while (!queue.isEmpty() && explored < MAX_NODES) {
            Node current = queue.poll();
            explored++;

            double score = scoreNode(current, target);
            if (score < bestScore) {
                bestScore = score;
                best = current;
            }
            if (score <= 6.0D) {
                best = current;
                break;
            }

            for (Node neighbor : neighbors(world, current)) {
                if (!withinBounds(neighbor, start)) {
                    continue;
                }
                long key = neighbor.key();
                if (!visited.add(key)) {
                    continue;
                }
                parents.put(key, current);
                queue.add(neighbor);
            }
        }

        if (best == null) {
            return List.of();
        }
        return simplifyRoute(reconstruct(world, parents, start, best));
    }

    private static List<Node> reconstruct(World world, Map<Long, Node> parents, Node start, Node end) {
        ArrayList<Node> reversed = new ArrayList<>();
        Node cursor = end;
        reversed.add(cursor);
        while (cursor != null && !cursor.equals(start)) {
            cursor = parents.get(cursor.key());
            if (cursor != null) {
                reversed.add(cursor);
            }
        }
        List<Node> route = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            route.add(reversed.get(index));
        }
        return route;
    }

    private static List<Location> simplifyRoute(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<Location> waypoints = new ArrayList<>();
        World world = null;
        Node previous = null;
        for (Node node : nodes) {
            if (world == null) {
                world = node.world;
            }
            if (previous == null
                    || node.y != previous.y
                    || waypoints.size() % 3 == 0
                    || node.equals(nodes.get(nodes.size() - 1))) {
                waypoints.add(toLocation(world, node));
            }
            previous = node;
        }
        if (waypoints.isEmpty() && world != null) {
            waypoints.add(toLocation(world, nodes.get(nodes.size() - 1)));
        }
        return waypoints;
    }

    private static List<Node> neighbors(World world, Node node) {
        List<Node> results = new ArrayList<>(12);
        int x = node.x;
        int y = node.y;
        int z = node.z;

        Block here = world.getBlockAt(x, y, z);
        Block hereGround = world.getBlockAt(x, y - 1, z);
        if (Tag.STAIRS.isTagged(hereGround.getType()) && MobTerrainSupport.isUsefulElevationStair(hereGround)) {
            Location stand = MobTerrainSupport.standOnElevationAid(hereGround);
            if (stand != null) {
                addNode(world, results, stand.getBlockX(), stand.getBlockY(), stand.getBlockZ());
            }
        }
        if (MobTerrainSupport.isClimbableMaterial(here.getType())) {
            addNode(world, results, x, y + 1, z);
            addNode(world, results, x, y - 1, z);
        }

        for (int[] dir : CARDINAL) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            Block aid = world.getBlockAt(nx, y, nz);
            if (MobTerrainSupport.isElevationAid(aid)) {
                if (MobTerrainSupport.isClimbableMaterial(aid.getType())) {
                    addNode(world, results, nx, y, nz);
                    addNode(world, results, nx, y + 1, nz);
                } else {
                    Location stand = MobTerrainSupport.standOnElevationAid(aid);
                    if (stand != null) {
                        addNode(world, results, stand.getBlockX(), stand.getBlockY(), stand.getBlockZ());
                    }
                }
            }
            Block aidAbove = world.getBlockAt(nx, y + 1, nz);
            if (MobTerrainSupport.isElevationAid(aidAbove)) {
                Location stand = MobTerrainSupport.standOnElevationAid(aidAbove);
                if (stand != null) {
                    addNode(world, results, stand.getBlockX(), stand.getBlockY(), stand.getBlockZ());
                }
            }
        }

        for (int[] dir : CARDINAL) {
            int nx = x + dir[0];
            int nz = z + dir[1];
            if (canStand(world, nx, y, nz)) {
                addNode(world, results, nx, y, nz);
            }
            if (canStand(world, nx, y + 1, nz) && canStepUpOnto(world, nx, y, nz)) {
                addNode(world, results, nx, y + 1, nz);
            }
            if (canStand(world, nx, y - 1, nz)) {
                addNode(world, results, nx, y - 1, nz);
            }
        }
        return results;
    }

    private static void addNode(World world, List<Node> results, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (MobTerrainSupport.isClimbableMaterial(block.getType()) || canStand(world, x, y, z)) {
            results.add(new Node(world, x, y, z));
        }
    }

    private static boolean canStand(World world, int x, int y, int z) {
        Location feet = new Location(world, x + 0.5D, y, z + 0.5D);
        return MobTerrainSupport.isStandable(feet) && !MobTerrainSupport.isIsolatedElevationLip(feet);
    }

    private static boolean canStepUpOnto(World world, int x, int y, int z) {
        Block step = world.getBlockAt(x, y, z);
        if (Tag.STAIRS.isTagged(step.getType())) {
            return MobTerrainSupport.isUsefulElevationStair(step);
        }
        if (Tag.SLABS.isTagged(step.getType())) {
            Location feet = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
            return MobTerrainSupport.isStandable(feet) && !MobTerrainSupport.isIsolatedElevationLip(feet);
        }
        return step.getType().isSolid();
    }

    private static boolean withinBounds(Node candidate, Node origin) {
        return Math.abs(candidate.x - origin.x) <= HORIZONTAL_RADIUS
                && Math.abs(candidate.z - origin.z) <= HORIZONTAL_RADIUS
                && Math.abs(candidate.y - origin.y) <= VERTICAL_RADIUS;
    }

    private static double scoreNode(Node node, Node goal) {
        double dx = node.x - goal.x;
        double dy = (node.y - goal.y) * 2.0D;
        double dz = node.z - goal.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static Node nodeAtFeet(Location location) {
        return new Node(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static Location toLocation(World world, Node node) {
        return new Location(world, node.x + 0.5D, node.y, node.z + 0.5D);
    }

    private record Node(World world, int x, int y, int z) {
        private long key() {
            return ((long) x & 0x3FFFFFL) << 42 | ((long) z & 0x3FFFFFL) << 21 | (y & 0x1FFFFFL);
        }
    }
}
