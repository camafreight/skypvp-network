package network.skypvp.paper.item.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Context passed to lore, stat, and behavior hooks for a resolved custom item.
 */
public record LiveItemContext(
        Player player,
        network.skypvp.paper.item.api.EquipmentSlotGroup slot,
        ItemStack stack,
        CustomItemInstance instance,
        CustomItemDefinition definition
) {
}
