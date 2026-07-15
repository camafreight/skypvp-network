package network.skypvp.paper.ai.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;

/** Pathfinder progress and stall tracking for mob agents. */
public final class NavigationTracker {

    public long lastNavTick;
    public Location lastDestination;
    public double lastBestDistanceSq = Double.MAX_VALUE;
    public long lastProgressTick;
    public boolean stalled;

    public Location routeGoal;
    public List<Location> routeWaypoints = Collections.emptyList();
    public int routeWaypointIndex;
    public boolean climbingLadder;
    public boolean climbingStairs;
    /** Following a complete vanilla A* route to an elevated goal (stairs walked natively). */
    public boolean vanillaRouteActive;
    /** Goal the active vanilla route was planned for; replan only when it moves. */
    public Location vanillaRouteGoal;
    /** Physical stall tracking: where the body last was and when it last actually moved. */
    public Location lastBodyPosition;
    public long lastBodyMoveTick;

    // --- Elevation-scan caches -------------------------------------------------------------
    // The stair/ladder box scans and the BFS route planner read thousands of blocks; they
    // ran per mob per tick while chasing elevated targets, which showed up as multi-frame
    // MSPT spikes. Results (INCLUDING misses) are cached per goal block with a TTL.
    public long stairScanTick;
    public Location stairScanGoal;
    /** Stand point of the best stair entry; null = scan ran and found nothing. */
    public Location stairEntryStand;
    public long ladderScanTick;
    public Location ladderScanGoal;
    /** A block inside the best ladder column; null = scan ran and found nothing. */
    public Location ladderScanBlock;
    /** When the BFS route planner last ran (guards empty results against re-planning). */
    public long routePlanTick;

    public void reset() {
        lastDestination = null;
        lastBestDistanceSq = Double.MAX_VALUE;
        lastProgressTick = 0L;
        stalled = false;
        climbingLadder = false;
        climbingStairs = false;
        vanillaRouteActive = false;
        vanillaRouteGoal = null;
        lastBodyPosition = null;
        lastBodyMoveTick = 0L;
    }

    public void clearRoute() {
        routeGoal = null;
        routeWaypoints = Collections.emptyList();
        routeWaypointIndex = 0;
    }
}
