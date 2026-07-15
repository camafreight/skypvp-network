package network.skypvp.paper.waypoint;

import java.util.Objects;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A navigation target for {@link WaypointNavigatorService}.
 *
 * @param id           stable key (e.g. {@code extract:right-temple}, {@code npc:scrapper})
 * @param worldName    Bukkit world name the target lives in
 * @param x            block/world X
 * @param y            hologram anchor Y (beam still spans world min→max)
 * @param z            block/world Z
 * @param label        MiniMessage-friendly plain label shown on the destination hologram
 * @param color        beam tint (leather dye)
 * @param autoClearBlocks legacy proximity clear ({@code <=0} = never); prefer quest {@code complete()} instead
 * @param hideWithinBlocks horizontal distance at which visuals hide until the player steps back ({@code <=0} = default 2)
 * @param marker       optional floating destination marker plate + icon ({@code null} = classic hologram only)
 */
public record Waypoint(
        String id,
        String worldName,
        double x,
        double y,
        double z,
        String label,
        Color color,
        double autoClearBlocks,
        double hideWithinBlocks,
        WaypointMarker marker
) {
    private static final double DEFAULT_HIDE_WITHIN_BLOCKS = 2.0D;

    public Waypoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldName, "worldName");
        if (id.isBlank()) {
            throw new IllegalArgumentException("waypoint id must not be blank");
        }
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("waypoint world must not be blank");
        }
        label = label == null || label.isBlank() ? id : label;
        color = color == null ? Color.fromRGB(30, 255, 80) : color;
        autoClearBlocks = Math.max(0.0D, autoClearBlocks);
        hideWithinBlocks = hideWithinBlocks <= 0.0D ? DEFAULT_HIDE_WITHIN_BLOCKS : hideWithinBlocks;
    }

    public static Waypoint of(String id, Location location, String label, Color color) {
        return of(id, location, label, color, 0.0D);
    }

    public static Waypoint of(String id, Location location, String label, Color color, double autoClearBlocks) {
        return of(id, location, label, color, autoClearBlocks, DEFAULT_HIDE_WITHIN_BLOCKS);
    }

    public static Waypoint of(
            String id,
            Location location,
            String label,
            Color color,
            double autoClearBlocks,
            double hideWithinBlocks
    ) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        Objects.requireNonNull(world, "location.world");
        return new Waypoint(
                id,
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                label,
                color,
                autoClearBlocks,
                hideWithinBlocks,
                null
        );
    }

    /** Copy of this waypoint carrying a floating destination {@code marker}. */
    public Waypoint withMarker(WaypointMarker newMarker) {
        return new Waypoint(id, worldName, x, y, z, label, color, autoClearBlocks, hideWithinBlocks, newMarker);
    }

    /** Copy with a custom proximity-hide radius (blocks). */
    public Waypoint withHideWithin(double blocks) {
        return new Waypoint(id, worldName, x, y, z, label, color, autoClearBlocks, blocks, marker);
    }

    public Location toLocation(World world) {
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    /** Anchor location resolved against the loaded world named {@link #worldName()}; null if unloaded. */
    public Location location() {
        return toLocation(org.bukkit.Bukkit.getWorld(worldName));
    }
}
