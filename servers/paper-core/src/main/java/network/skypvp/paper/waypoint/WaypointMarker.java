package network.skypvp.paper.waypoint;

import org.bukkit.Color;

/**
 * Optional floating destination marker for a {@link Waypoint}: a billboarded, dye-tinted
 * plate (default: the flat octagon {@code skypvp:nav_marker_octagon}) with an optional
 * icon rendered at its center.
 *
 * <p>Everything here is API-driven so callers stay in control of the look:
 * <ul>
 *   <li>{@code itemModel} — resource-pack item model of the plate (dye tint via leather meta)</li>
 *   <li>{@code color} — plate tint; {@code null} falls back to {@link Waypoint#color()}</li>
 *   <li>{@code icon} — MiniMessage string (usually a single unicode glyph, e.g. {@code "⚒"})
 *       floated in front of the plate center; {@code null}/blank renders no icon</li>
 * </ul>
 *
 * @param itemModel item model key of the marker plate, e.g. {@code skypvp:nav_marker_octagon}
 * @param color     plate dye tint; {@code null} → waypoint color
 * @param icon      MiniMessage icon centered on the plate; {@code null}/blank → none
 */
public record WaypointMarker(String itemModel, Color color, String icon) {

    public static final String OCTAGON_MODEL = "skypvp:nav_marker_octagon";

    public WaypointMarker {
        itemModel = itemModel == null || itemModel.isBlank() ? OCTAGON_MODEL : itemModel;
        icon = icon == null || icon.isBlank() ? null : icon;
    }

    /** Flat octagon plate tinted {@code color} with a centered MiniMessage {@code icon}. */
    public static WaypointMarker octagon(Color color, String icon) {
        return new WaypointMarker(OCTAGON_MODEL, color, icon);
    }
}
