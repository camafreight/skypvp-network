package network.skypvp.paper.inventory.vault;

import java.util.Objects;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class VaultGuiListener implements Listener {

    private final VaultGuiService vaultGuiService;
    private final CoreHotbarService hotbarService;

    public VaultGuiListener(VaultGuiService vaultGuiService, CoreHotbarService hotbarService) {
        this.vaultGuiService = Objects.requireNonNull(vaultGuiService, "vaultGuiService");
        this.hotbarService = Objects.requireNonNull(hotbarService, "hotbarService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        if (this.isBlockedServerItem(event.getCurrentItem()) || this.isBlockedServerItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < top.getSize()) {
            if (rawSlot == VaultLayout.CLOSE_SLOT) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (rawSlot == VaultLayout.BACK_SLOT) {
                event.setCancelled(true);
                this.vaultGuiService.handleBack(player, holder);
                return;
            }
            if (rawSlot == VaultLayout.SCROLL_UP_SLOT) {
                event.setCancelled(true);
                this.vaultGuiService.changePage(player, holder, -1);
                return;
            }
            if (rawSlot == VaultLayout.SCROLL_DOWN_SLOT) {
                event.setCancelled(true);
                this.vaultGuiService.changePage(player, holder, 1);
                return;
            }
            if (VaultLayout.isContentSlot(rawSlot)) {
                int contentIndex = VaultLayout.contentSlotIndex(rawSlot);
                if (contentIndex >= 0) {
                    int vaultIndex = VaultLayout.vaultIndexForContentSlot(holder.page(), contentIndex);
                    if (VaultLayout.isPurchasableSlotItem(event.getCurrentItem())) {
                        event.setCancelled(true);
                        this.vaultGuiService.promptRowPurchase(player, holder);
                        return;
                    }
                    if (!holder.isDepositableVaultIndex(vaultIndex)
                            || VaultLayout.isDecorativeSlotItem(event.getCurrentItem())) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            if (VaultLayout.isControlSlot(rawSlot) && !VaultLayout.isContentSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }

        // Never let vanilla shift-click route into the vault top inventory — that was how players could
        // replace locked barrier placeholders. All deposits go through our validated deposit path.
        if (event.isShiftClick()
                && event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getBottomInventory())) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (this.isBlockedServerItem(clicked)) {
                return;
            }
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    && clicked != null
                    && !clicked.getType().isAir()) {
                int playerSlot = event.getSlot();
                ItemStack transfer = clicked.clone();
                if (this.vaultGuiService.depositShiftClickedStack(player, holder, transfer)) {
                    this.removeDepositedStack(event.getClickedInventory(), playerSlot, transfer.getAmount());
                    player.updateInventory();
                    this.vaultGuiService.scheduleInventoryResync(player);
                }
            }
            return;
        }

        if (event.isShiftClick() && rawSlot >= 0 && rawSlot < top.getSize() && VaultLayout.isControlSlot(rawSlot)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickMonitor(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        VaultLayout.syncContentFromInventory(top, holder);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDragMonitor(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        VaultLayout.syncContentFromInventory(top, holder);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        for (ItemStack item : event.getNewItems().values()) {
            if (this.isBlockedServerItem(item)) {
                event.setCancelled(true);
                return;
            }
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= top.getSize()) {
                continue;
            }
            if (VaultLayout.isControlSlot(slot) && !VaultLayout.isContentSlot(slot)) {
                event.setCancelled(true);
                return;
            }
            if (VaultLayout.isContentSlot(slot)) {
                int contentIndex = VaultLayout.contentSlotIndex(slot);
                if (contentIndex >= 0) {
                    int vaultIndex = VaultLayout.vaultIndexForContentSlot(holder.page(), contentIndex);
                    if (!holder.isDepositableVaultIndex(vaultIndex)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof VaultHolder) {
            this.vaultGuiService.handleClose(event.getInventory());
        }
    }

    private boolean isBlockedServerItem(ItemStack item) {
        return this.hotbarService.isServerItem(item);
    }

    private void removeDepositedStack(Inventory inventory, int slot, int amount) {
        if (inventory == null || slot < 0 || amount <= 0) {
            return;
        }
        ItemStack inSlot = inventory.getItem(slot);
        if (inSlot == null || inSlot.getType().isAir()) {
            return;
        }
        int remaining = inSlot.getAmount() - amount;
        if (remaining <= 0) {
            inventory.setItem(slot, null);
            return;
        }
        ItemStack updated = inSlot.clone();
        updated.setAmount(remaining);
        inventory.setItem(slot, updated);
    }
}
