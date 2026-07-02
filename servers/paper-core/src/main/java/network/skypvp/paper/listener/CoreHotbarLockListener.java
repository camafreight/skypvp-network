package network.skypvp.paper.listener;

import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public final class CoreHotbarLockListener implements Listener {

   private final CoreHotbarService hotbarService;

   public CoreHotbarLockListener(CoreHotbarService hotbarService) {
      this.hotbarService = hotbarService;
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onDrop(PlayerDropItemEvent event) {
      if (!this.hotbarService.isHotbarLockEnabled()) {
         return;
      }
      if (this.hotbarService.isServerItem(event.getItemDrop().getItemStack())) {
         event.setCancelled(true);
         event.getItemDrop().remove();
         this.hotbarService.repairAfterBlockedDrop(event.getPlayer());
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onSwap(PlayerSwapHandItemsEvent event) {
      if (!this.hotbarService.isHotbarLockEnabled()) {
         return;
      }
      if (this.hotbarService.isServerItem(event.getMainHandItem()) || this.hotbarService.isServerItem(event.getOffHandItem())) {
         event.setCancelled(true);
         this.restoreHotbar(event.getPlayer());
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onInventoryClick(InventoryClickEvent event) {
      if (!this.hotbarService.isHotbarLockEnabled()) {
         return;
      }
      if (!(event.getWhoClicked() instanceof Player player)) {
         return;
      }
      if (this.hotbarService.isServerItem(event.getCurrentItem()) || this.hotbarService.isServerItem(event.getCursor())) {
         event.setCancelled(true);
         this.restoreHotbar(player);
         return;
      }
      if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
         return;
      }
      if (this.affectsLockedHotbarSlot(player, event)) {
         event.setCancelled(true);
         this.restoreHotbar(player);
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onInventoryDrag(InventoryDragEvent event) {
      if (!this.hotbarService.isHotbarLockEnabled()) {
         return;
      }
      if (!(event.getWhoClicked() instanceof Player player)) {
         return;
      }
      InventoryView view = event.getView();
      for (int rawSlot : event.getRawSlots()) {
         int hotbarSlot = this.hotbarSlotForRawSlot(view, rawSlot);
         if (hotbarSlot >= 0 && this.hotbarService.isReservedHotbarSlot(player, hotbarSlot)) {
            event.setCancelled(true);
            this.restoreHotbar(player);
            return;
         }
      }
      for (ItemStack stack : event.getNewItems().values()) {
         if (this.hotbarService.isServerItem(stack)) {
            event.setCancelled(true);
            this.restoreHotbar(player);
            return;
         }
      }
   }

   private boolean affectsLockedHotbarSlot(Player player, InventoryClickEvent event) {
      InventoryView view = event.getView();
      int rawSlot = event.getRawSlot();
      int hotbarSlot = this.hotbarSlotForRawSlot(view, rawSlot);
      if (hotbarSlot >= 0 && this.hotbarService.isReservedHotbarSlot(player, hotbarSlot)) {
         return true;
      }
      if (event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD || event.getAction() == InventoryAction.HOTBAR_SWAP) {
         ClickType click = event.getClick();
         if (click == ClickType.NUMBER_KEY) {
            int hotbarButton = event.getHotbarButton();
            return this.hotbarService.isReservedHotbarSlot((Player) event.getWhoClicked(), hotbarButton);
         }
      }
      if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
         ItemStack current = event.getCurrentItem();
         return current != null && this.hotbarService.isServerItem(current);
      }
      if (hotbarSlot >= 0) {
         return this.hotbarService.isServerItem(view.getItem(rawSlot));
      }
      return false;
   }

   /**
    * Maps a raw view slot to the player's hotbar index (0-8), or -1 if it is not a hotbar slot.
    * Uses the slot type so armor slots (which share raw slots 5-8 with the inventory crafting view)
    * are never mistaken for hotbar slots — that collision was blocking the boots slot.
    */
   private int hotbarSlotForRawSlot(InventoryView view, int rawSlot) {
      if (rawSlot < 0 || rawSlot >= view.countSlots()) {
         return -1;
      }
      if (view.getSlotType(rawSlot) != InventoryType.SlotType.QUICKBAR) {
         return -1;
      }
      int converted = view.convertSlot(rawSlot);
      return converted >= 0 && converted <= 8 ? converted : -1;
   }

   private void restoreHotbar(Player player) {
      if (this.hotbarService.usesExtractionLayout()) {
         if (player.getGameMode() == GameMode.SPECTATOR) {
            this.hotbarService.ensureSpectatorHotbar(player);
         } else {
            this.hotbarService.ensureActiveRaidHotbar(player);
         }
         return;
      }
      this.hotbarService.ensureNetworkItems(player);
   }
}
