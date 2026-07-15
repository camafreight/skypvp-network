package network.skypvp.paper.nms;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * The live state of a headless player captured at removal/reconnect, so it can be transferred into the
 * reconnecting player's restore path (health + depleted-shield armor + inventory + position).
 *
 * @param id       player uuid
 * @param location body location at capture time
 * @param health   body health at capture time
 * @param contents full inventory contents at capture time ({@code PlayerInventory#getContents()} order)
 */
public record HeadlessSnapshot(
        UUID id,
        Location location,
        double health,
        ItemStack[] contents
) {
}
