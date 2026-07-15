package network.skypvp.lobby.library;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.LobbyModePlugin;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class HotbarItemsLibrary {
   private static final String TITLE_HEX = ServerTextUtil.ThemeTone.BRAND_400.hex();
   private final LobbyModePlugin plugin;
   private final CoreHotbarService hotbarService;
   private final NamespacedKey legacyActionKey;

   public HotbarItemsLibrary(LobbyModePlugin plugin, CoreHotbarService hotbarService) {
      this.plugin = plugin;
      this.hotbarService = hotbarService;
      this.legacyActionKey = new NamespacedKey(plugin, "hotbar_action");
   }

   public int apply(Player player, boolean clearFirst) {
      if (clearFirst) {
         player.getInventory().clear();
         player.getInventory().setArmorContents(null);
      }

      int applied = this.purgeStaleItems(player);

      for (HotbarItemsLibrary.HotbarItemDef def : this.defaults()) {
         player.getInventory().setItem(def.slot(), this.buildItem(player, def));
         applied++;
      }

      return applied;
   }

   public int ensure(Player player) {
      int fixes = this.purgeStaleItems(player);

      for (HotbarItemsLibrary.HotbarItemDef def : this.defaults()) {
         ItemStack existing = player.getInventory().getItem(def.slot());
         if (!this.matchesDef(existing, def)) {
            player.getInventory().setItem(def.slot(), this.buildItem(player, def));
            fixes++;
         }
      }

      return fixes;
   }

   public int purgeStaleItems(Player player) {
      Set<Integer> ownedSlots = new HashSet<>();
      Set<String> ownedActions = new HashSet<>();
      for (HotbarItemsLibrary.HotbarItemDef def : this.defaults()) {
         ownedSlots.add(def.slot());
         ownedActions.add(def.action().toLowerCase(Locale.ROOT));
      }

      int removed = 0;
      PlayerInventory inventory = player.getInventory();

      for (int slot = 0; slot < 9; slot++) {
         if (ownedSlots.contains(slot)) {
            continue;
         }
         if (this.clearSlotIfPresent(inventory, slot)) {
            removed++;
         }
      }

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         ItemStack item = inventory.getItem(slot);
         if (item == null || item.getType().isAir()) {
            continue;
         }

         String action = this.readAction(item);
         boolean tagged = this.isTaggedHotbarItem(item);
         if (!tagged && slot >= 9) {
            continue;
         }

         if (tagged && ownedSlots.contains(slot) && action != null && ownedActions.contains(action.toLowerCase(Locale.ROOT))) {
            continue;
         }

         if (tagged || slot < 9) {
            inventory.setItem(slot, null);
            removed++;
         }
      }

      if (this.isTaggedHotbarItem(inventory.getItemInOffHand())) {
         inventory.setItemInOffHand(null);
         removed++;
      }

      return removed;
   }

   public String readAction(ItemStack item) {
      if (item == null || !item.hasItemMeta()) {
         return null;
      }
      String action = this.hotbarService.readAction(item);
      if (action != null) {
         return action;
      }
      return item.getItemMeta().getPersistentDataContainer().get(this.legacyActionKey, PersistentDataType.STRING);
   }

   private boolean isTaggedHotbarItem(ItemStack item) {
      if (item == null || !item.hasItemMeta()) {
         return false;
      }
      if (this.hotbarService.isServerItem(item)) {
         return true;
      }
      PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
      return pdc.has(this.legacyActionKey, PersistentDataType.STRING);
   }

   private boolean clearSlotIfPresent(PlayerInventory inventory, int slot) {
      ItemStack item = inventory.getItem(slot);
      if (item == null || item.getType().isAir()) {
         return false;
      }
      inventory.setItem(slot, null);
      return true;
   }

   private List<HotbarItemsLibrary.HotbarItemDef> defaults() {
      List<HotbarItemsLibrary.HotbarItemDef> defs = new ArrayList<>();
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            2,
            Material.PLAYER_HEAD,
            title("Lobby Minigames"),
            GuiItems.standardLore(List.of("Quickly jump into Duels,", "Knockback Tag, or Hide and Seek!"), "Click to open"),
            CoreHotbarService.ACTION_OPEN_LOBBY_MINIGAMES,
            "",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3OTQ2NzMzMzA2NCwKICAicHJvZmlsZUlkIiA6ICI3ZGEyYWIzYTkzY2E0OGVlODMwNDhhZmMzYjgwZTY4ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJHb2xkYXBmZWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWEyMDg3YTRkNjNhYTM0ZmEyYjJlMjk3MWJjZWNhZjVhNzYwYTkzNGFmYTQ4MjBiOTAyNTM5NTNmOGZkZjMyMiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            4,
            Material.NETHER_STAR,
            title("Network Navigator"),
            GuiItems.standardLore(List.of("Route to lobby hubs or Aether Breach.", "One menu for all network destinations."), "Click to open"),
            CoreHotbarService.ACTION_OPEN_NAVIGATOR,
            ""
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            7,
            Material.BOOK,
            title("Help Center"),
            GuiItems.standardLore(List.of("Review commands, shortcuts, and utility menus."), "Click to browse"),
            CoreHotbarService.ACTION_OPEN_HELP,
            ""
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            8,
            Material.PLAYER_HEAD,
            title("Your Profile"),
            GuiItems.standardLore(List.of("View your stats, coins, and progression."), "Click to view"),
            CoreHotbarService.ACTION_OPEN_PROFILE,
            ""
         )
      );
      return defs;
   }

   private boolean matchesDef(ItemStack item, HotbarItemsLibrary.HotbarItemDef def) {
      if (item == null || item.getType() != def.material() || !item.hasItemMeta()) {
         return false;
      }
      String action = this.readAction(item);
      return def.action().equalsIgnoreCase(action) && this.hotbarService.isServerItem(item);
   }

   private ItemStack buildItem(Player owner, HotbarItemsLibrary.HotbarItemDef def) {
      ItemStack item = new ItemStack(def.material());
      ItemMeta meta = item.getItemMeta();
      if (meta instanceof SkullMeta skullMeta && def.material() == Material.PLAYER_HEAD) {
         if (def.textureValue() != null && !def.textureValue().isEmpty()) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
            profile.setProperty(new ProfileProperty("textures", def.textureValue()));
            skullMeta.setPlayerProfile(profile);
         } else {
            GuiItems.applyUnsignedSkin(skullMeta, owner);
         }

         meta = skullMeta;
      }

      meta.displayName(ServerTextUtil.miniMessageComponent(def.name()));
      meta.lore(def.lore().stream().map(MiniMessage.miniMessage()::deserialize).toList());
      this.hotbarService.tagServerHotbarItem(meta, def.action());
      item.setItemMeta(meta);
      return item;
   }

   private static String title(String label) {
      return "<" + TITLE_HEX + "><bold>" + label + "</bold><reset>";
   }

   private static record HotbarItemDef(int slot, Material material, String name, List<String> lore, String action, String actionData, String textureValue) {
      public HotbarItemDef(int slot, Material material, String name, List<String> lore, String action, String actionData) {
         this(slot, material, name, lore, action, actionData, null);
      }
   }
}
