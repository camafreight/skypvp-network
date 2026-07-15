package network.skypvp.paper.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Safe player-inventory mutations after a cancelled GUI click. */
public final class GuiClickInventory {

    private GuiClickInventory() {
    }

    /**
     * Removes {@code amount} from the clicked player-inventory slot after the click event was cancelled.
     * Updates both the backing inventory and the event snapshot so client and server stay aligned.
     *
     * @return amount actually removed
     */
    public static int consumeClickedStack(InventoryClickEvent event, int amount) {
        if (event == null || amount <= 0) {
            return 0;
        }
        Inventory clicked = event.getClickedInventory();
        ItemStack inSlot = clicked != null ? clicked.getItem(event.getSlot()) : event.getCurrentItem();
        if (inSlot == null || inSlot.getType().isAir()) {
            return 0;
        }
        int remove = Math.min(amount, inSlot.getAmount());
        if (remove <= 0) {
            return 0;
        }
        int remaining = inSlot.getAmount() - remove;
        ItemStack updated = remaining <= 0 ? null : inSlot.clone();
        if (updated != null) {
            updated.setAmount(remaining);
        }
        if (clicked != null) {
            clicked.setItem(event.getSlot(), updated);
        }
        event.setCurrentItem(updated);
        if (event.getWhoClicked() instanceof Player player) {
            player.updateInventory();
        }
        return remove;
    }

    /** Removes {@code amount} from a player inventory slot (storage contents index). */
    public static int consumePlayerSlot(Player player, int slot, int amount) {
        if (player == null || amount <= 0 || slot < 0) {
            return 0;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        if (slot >= contents.length) {
            return 0;
        }
        ItemStack inSlot = contents[slot];
        if (inSlot == null || inSlot.getType().isAir()) {
            return 0;
        }
        int remove = Math.min(amount, inSlot.getAmount());
        if (remove <= 0) {
            return 0;
        }
        int remaining = inSlot.getAmount() - remove;
        contents[slot] = remaining <= 0 ? null : inSlot.asQuantity(remaining);
        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        return remove;
    }
}
