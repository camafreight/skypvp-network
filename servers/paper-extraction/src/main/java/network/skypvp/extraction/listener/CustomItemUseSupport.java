package network.skypvp.extraction.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Helpers for custom consumable right-clicks (air, blocks, and entities). */
final class CustomItemUseSupport {

    private CustomItemUseSupport() {
    }

    static boolean isRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    static ItemStack itemInHand(PlayerInteractEvent event) {
        return itemInHand(event.getPlayer(), event.getHand(), event.getItem());
    }

    static ItemStack itemInHand(PlayerInteractEntityEvent event) {
        return itemInHand(event.getPlayer(), event.getHand(), null);
    }

    private static ItemStack itemInHand(Player player, EquipmentSlot hand, ItemStack eventItem) {
        if (eventItem != null && !eventItem.getType().isAir()) {
            return eventItem;
        }
        EquipmentSlot slot = hand == null ? EquipmentSlot.HAND : hand;
        return player.getInventory().getItem(slot);
    }

    /**
     * Paper pre-cancels {@link Action#RIGHT_CLICK_AIR} when vanilla has no item use (e.g. paper).
     * Listeners must use {@code ignoreCancelled = false} and call this to take over the interaction.
     */
    static void suppressVanilla(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }
}
