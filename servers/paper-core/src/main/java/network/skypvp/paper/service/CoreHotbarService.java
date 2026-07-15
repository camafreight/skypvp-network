package network.skypvp.paper.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.library.ItemsLibrary;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CoreHotbarService {
   public static final int LEAVE_SLOT = 7;
   public static final int MENU_SLOT = 8;
   public static final int SOCIALS_SLOT = 7;
   public static final String ACTION_OPEN_MENU = "OPEN_NETWORK_MENU";
   public static final String ACTION_OPEN_SOCIALS = "OPEN_SOCIALS";
   public static final String ACTION_LEAVE_BREACH = "LEAVE_BREACH";
   public static final String ACTION_OPEN_NAVIGATOR = "OPEN_NAVIGATOR";
   public static final String ACTION_OPEN_SELECTOR = "OPEN_SELECTOR";
   public static final String ACTION_OPEN_LOBBY_MINIGAMES = "OPEN_LOBBY_MINIGAMES";
   public static final String ACTION_OPEN_HELP = "OPEN_HELP";
   public static final String ACTION_OPEN_PROFILE = "OPEN_PROFILE";
   public static final String SERVER_ITEM_KEY = "server_item";

   private final PaperCorePlugin plugin;
   private final NamespacedKey actionKey;
   private final NamespacedKey serverItemKey;

   public CoreHotbarService(PaperCorePlugin plugin) {
      this.plugin = Objects.requireNonNull(plugin, "plugin");
      this.actionKey = new NamespacedKey(plugin, "hotbar_action");
      this.serverItemKey = new NamespacedKey(plugin, SERVER_ITEM_KEY);
   }

   public void applyNetworkItems(Player player, boolean clearFirst) {
      if (clearFirst) {
         player.getInventory().clear();
         player.getInventory().setArmorContents(null);
      }
      if (usesExtractionLayout()) {
         applyExtractionItems(player, false);
         return;
      }
      player.getInventory().setItem(SOCIALS_SLOT, this.socialsItem());
      player.getInventory().setItem(MENU_SLOT, this.menuItem());
   }

   public void ensureNetworkItems(Player player) {
      if (usesExtractionLayout()) {
         ensureExtractionItems(player);
         return;
      }
      this.removeDuplicateServerItems(player);
      if (!this.matches(player.getInventory().getItem(SOCIALS_SLOT), ACTION_OPEN_SOCIALS)) {
         player.getInventory().setItem(SOCIALS_SLOT, this.socialsItem());
      }
      if (!this.matches(player.getInventory().getItem(MENU_SLOT), ACTION_OPEN_MENU)) {
         player.getInventory().setItem(MENU_SLOT, this.menuItem());
      }
   }

   public void applyExtractionItems(Player player, boolean clearFirst) {
      if (clearFirst) {
         player.getInventory().clear();
         player.getInventory().setArmorContents(null);
      }
      this.ensureActiveRaidHotbar(player);
   }

   public void ensureExtractionItems(Player player) {
      this.ensureActiveRaidHotbar(player);
   }

   public void ensureActiveRaidHotbar(Player player) {
      // Only strip the leave-bed server item (left over from spectator mode). The leave slot is free for loot
      // during an active raid, so blindly nulling it would delete whatever the player is carrying there -
      // most visibly when an extracted player returns to the hub still holding loot in that slot.
      if (this.isLeaveBreachItem(player.getInventory().getItem(LEAVE_SLOT))) {
         player.getInventory().setItem(LEAVE_SLOT, null);
      }
      this.removeDuplicateServerItems(player);
      if (!this.matches(player.getInventory().getItem(MENU_SLOT), ACTION_OPEN_MENU)) {
         player.getInventory().setItem(MENU_SLOT, this.menuItem());
      }
   }

   public void ensureSpectatorHotbar(Player player) {
      player.getInventory().setItem(SOCIALS_SLOT, null);
      this.removeDuplicateServerItems(player);
      if (!this.matches(player.getInventory().getItem(LEAVE_SLOT), ACTION_LEAVE_BREACH)) {
         player.getInventory().setItem(LEAVE_SLOT, this.leaveBreachItem());
      }
      if (!this.matches(player.getInventory().getItem(MENU_SLOT), ACTION_OPEN_MENU)) {
         player.getInventory().setItem(MENU_SLOT, this.menuItem());
      }
   }

   public void repairAfterBlockedDrop(Player player) {
      this.plugin.platformScheduler().runOnPlayerLater(player, () -> {
         if (!player.isOnline()) {
            return;
         }
         if (this.usesExtractionLayout()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
               this.ensureSpectatorHotbar(player);
            } else {
               this.ensureActiveRaidHotbar(player);
            }
         } else {
            this.ensureNetworkItems(player);
         }
      }, 1L);
   }

   private void removeDuplicateServerItems(Player player) {
      this.removeExtraServerItems(player, ACTION_OPEN_MENU, MENU_SLOT);
      this.removeExtraServerItems(player, ACTION_OPEN_SOCIALS, SOCIALS_SLOT);
      this.removeExtraServerItems(player, ACTION_LEAVE_BREACH, LEAVE_SLOT);
   }

   private void removeExtraServerItems(Player player, String action, int keepSlot) {
      org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         if (slot == keepSlot) {
            continue;
         }
         ItemStack item = inventory.getItem(slot);
         if (this.matches(item, action)) {
            inventory.setItem(slot, null);
         }
      }
      ItemStack offhand = inventory.getItemInOffHand();
      if (this.matches(offhand, action)) {
         inventory.setItemInOffHand(null);
      }
   }

   public boolean isLeaveBreachItem(ItemStack item) {
      return ACTION_LEAVE_BREACH.equalsIgnoreCase(String.valueOf(this.readAction(item)));
   }

   public Set<Integer> reservedHotbarSlots() {
      if (usesExtractionLayout()) {
         return Set.of(MENU_SLOT);
      }
      return Set.of(SOCIALS_SLOT, MENU_SLOT);
   }

   public boolean isReservedHotbarSlot(Player player, int slot) {
      if (slot == MENU_SLOT && usesExtractionLayout()) {
         return true;
      }
      if (usesExtractionLayout() && slot == LEAVE_SLOT) {
         return this.isLeaveBreachItem(player.getInventory().getItem(LEAVE_SLOT));
      }
      return this.reservedHotbarSlots().contains(slot);
   }

   public boolean usesExtractionLayout() {
      return this.plugin.gameModeBehaviorService().booleanValue("core.hotbar.extraction-layout", false);
   }

   public boolean isHotbarLockEnabled() {
      return this.plugin.gameModeBehaviorService().booleanValue("core.hotbar.lock-enabled", false);
   }

   public boolean isServerItem(ItemStack item) {
      if (item == null || !item.hasItemMeta()) {
         return false;
      }
      return item.getItemMeta().getPersistentDataContainer().has(this.serverItemKey, PersistentDataType.BYTE);
   }

   public String readAction(ItemStack item) {
      if (item == null || !item.hasItemMeta()) {
         return null;
      }
      return item.getItemMeta().getPersistentDataContainer().get(this.actionKey, PersistentDataType.STRING);
   }

   public void tagServerHotbarItem(ItemMeta meta, String action) {
      meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, action);
      meta.getPersistentDataContainer().set(this.serverItemKey, PersistentDataType.BYTE, (byte)1);
   }

   public boolean isCoreNetworkHotbarAction(String action) {
      if (action == null) {
         return false;
      }
      String normalized = action.toLowerCase(Locale.ROOT);
      return ACTION_OPEN_MENU.equalsIgnoreCase(normalized)
         || ACTION_OPEN_SOCIALS.equalsIgnoreCase(normalized)
         || ACTION_LEAVE_BREACH.equalsIgnoreCase(normalized);
   }

   public ItemStack menuItem() {
      return ItemsLibrary.builder(Material.NETHER_STAR)
         .name("<" + ServerTextUtil.ThemeTone.BRAND_400.hex() + ">Menu</" + ServerTextUtil.ThemeTone.BRAND_400.hex() + ">")
         .lore(java.util.List.of("<gray>Open network menu", "<gray>Loadouts, vault, party"))
         .applyMeta(meta -> {
            meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, ACTION_OPEN_MENU);
            meta.getPersistentDataContainer().set(this.serverItemKey, PersistentDataType.BYTE, (byte)1);
         })
         .build();
   }

   public ItemStack socialsItem() {
      return ItemsLibrary.builder(Material.PLAYER_HEAD)
         .name("<" + ServerTextUtil.ThemeTone.BRAND_400.hex() + ">Socials</" + ServerTextUtil.ThemeTone.BRAND_400.hex() + ">")
         .lore(java.util.List.of("<gray>Friends and social graph"))
         .applyMeta(meta -> {
            meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, ACTION_OPEN_SOCIALS);
            meta.getPersistentDataContainer().set(this.serverItemKey, PersistentDataType.BYTE, (byte)1);
         })
         .build();
   }

   public ItemStack leaveBreachItem() {
      return ItemsLibrary.builder(Material.RED_BED)
         .name("<red>Leave Breach</red>")
         .lore(java.util.List.of("<gray>Return to the extraction lobby"))
         .applyMeta(meta -> {
            meta.getPersistentDataContainer().set(this.actionKey, PersistentDataType.STRING, ACTION_LEAVE_BREACH);
            meta.getPersistentDataContainer().set(this.serverItemKey, PersistentDataType.BYTE, (byte)1);
         })
         .build();
   }

   private boolean matches(ItemStack existing, String action) {
      return action.equalsIgnoreCase(String.valueOf(this.readAction(existing)).toLowerCase(Locale.ROOT));
   }
}
