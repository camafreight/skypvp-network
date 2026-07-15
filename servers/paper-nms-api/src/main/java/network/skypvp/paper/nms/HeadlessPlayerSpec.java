package network.skypvp.paper.nms;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * Immutable description of a headless player to spawn.
 *
 * @param id                player uuid (should match the real player so uuid-keyed systems keep working)
 * @param name              player name (used for the profile / tab identity)
 * @param texturesValue     base64 "textures" property value for the skin (nullable)
 * @param texturesSignature signature for the "textures" property (nullable)
 * @param location          where to place the body
 * @param health            initial health to set (clamped to {@code maxHealth})
 * @param maxHealth         max-health attribute to apply (extraction uses a custom 40-hp pool)
 * @param contents          full inventory contents ({@code PlayerInventory#getContents()} order, incl. armor
 *                          + offhand) so the body wears the same armor (shields) and holds the same loot
 */
public record HeadlessPlayerSpec(
        UUID id,
        String name,
        String texturesValue,
        String texturesSignature,
        Location location,
        double health,
        double maxHealth,
        ItemStack[] contents
) {
}
