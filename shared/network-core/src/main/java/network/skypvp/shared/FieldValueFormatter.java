package network.skypvp.shared;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Formatter for field:value pairs with semantic color-coding.
 * 
 * Applies consistent coloring to improve readability:
 * - Field labels: WHITE (main text tone)
 * - Separator (:): DARK_GRAY (structural)
 * - Values: BRAND color or accent (emphasizes data)
 * 
 * Example output: "Set position 2: world (7, -6)"
 * Renders as: (white)Set position 2(dark_gray):(brand)world (7, -6)
 */
@Deprecated(since = "2026-05", forRemoval = false)
public final class FieldValueFormatter {

    private FieldValueFormatter() {
    }

    /**
     * MiniMessage format for field:value pairs.
     * Example: "<white>Position 1</white><dark_gray>:</dark_gray><#60a5fa>world (7, -6)</#60a5fa>"
     * 
     * @param field the field label
     * @param value the data value
     * @return MiniMessage-encoded string with semantic color separation
     */
    public static String fieldValueMiniMessage(String field, String value) {
        return ServerTextUtil.fieldValueMiniMessage(field, value);
    }

    /**
     * Legacy ampersand format for field:value in messages.
     * Example: "&fPosition 1&8:&b world (7, -6)"
     * 
     * @param field the field label
     * @param value the data value
     * @return ampersand-encoded string with semantic color separation
     */
    public static String fieldValueLegacy(String field, String value) {
        return ServerTextUtil.fieldValueLegacy(field, value);
    }

    /**
     * Builds a field:value Component with semantic color separation.
     * 
     * @param field the field label (e.g., "Set position 2")
     * @param value the data value (e.g., "world (7, -6)")
     * @return colored Component with field (white), separator (dark gray), value (brand blue)
     */
    public static Component fieldValue(String field, String value) {
        return ServerTextUtil.fieldValue(field, value);
    }

    /**
     * Formats a field label with colon separator in distinct colors.
     * 
     * @param field the label
     * @return Component with field (white) and separator (dark gray)
     */
    public static Component labelWithSeparator(String field) {
        return ServerTextUtil.labelWithSeparator(field);
    }

    /**
     * Formats a data value in brand accent color.
     * 
     * @param value the data to display
     * @return Component with brand blue color
     */
    public static Component dataValue(String value) {
        return ServerTextUtil.dataValue(value);
    }

    /**
     * Formats a coordinate pair (x, z) with color coding.
     * Example: "world (-42, 156)"
     * 
     * @param world world name (in brand color)
     * @param x x coordinate
     * @param z z coordinate
     * @return colored Component
     */
    public static Component coordinatePair(String world, int x, int z) {
        return ServerTextUtil.coordinatePair(world, x, z);
    }

    /**
     * Formats a 3D coordinate triple (x, y, z).
     * 
     * @param world world name (in brand color)
     * @param x x coordinate
     * @param y y coordinate  
     * @param z z coordinate
     * @return colored Component
     */
    public static Component coordinateTriple(String world, int x, int y, int z) {
        return ServerTextUtil.coordinateTriple(world, x, y, z);
    }

    /**
     * Formats a status or state indicator with field label.
     * Example: "Status: ONLINE" (Status white, ONLINE green)
     * 
     * @param field the field label (e.g., "Status")
     * @param status the status value (e.g., "ONLINE")
     * @param statusColor color for the status value
     * @return colored Component
     */
    public static Component statusField(String field, String status, NamedTextColor statusColor) {
        return ServerTextUtil.statusField(field, status, statusColor);
    }
}
