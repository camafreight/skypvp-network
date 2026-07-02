package network.skypvp.lobby.library;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.LobbyModePlugin;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class HotbarItemsLibrary {
   private static final String TITLE_HEX = ServerTextUtil.ThemeTone.BRAND_400.hex();
   private static final String SECONDARY_HEX = ServerTextUtil.ThemeTone.BRAND_600.hex();
   private static final String LOBBY_POOL_TARGET = "lobby";
   private final LobbyModePlugin plugin;
   private final NamespacedKey actionKey;
   private final NamespacedKey dataKey;

   public HotbarItemsLibrary(LobbyModePlugin plugin) {
      this.plugin = plugin;
      this.actionKey = new NamespacedKey(plugin, "hotbar_action");
      this.dataKey = new NamespacedKey(plugin, "hotbar_action_data");
   }

   public int apply(Player player, boolean clearFirst) {
      if (clearFirst) {
         player.getInventory().clear();
         player.getInventory().setArmorContents(null);
      }

      int applied = 0;

      for (HotbarItemsLibrary.HotbarItemDef def : this.defaults()) {
         player.getInventory().setItem(def.slot(), this.buildItem(player, def));
         applied++;
      }

      return applied;
   }

   public int ensure(Player player) {
      int fixes = 0;

      for (HotbarItemsLibrary.HotbarItemDef def : this.defaults()) {
         ItemStack existing = player.getInventory().getItem(def.slot());
         if (!this.matchesDef(existing, def)) {
            player.getInventory().setItem(def.slot(), this.buildItem(player, def));
            fixes++;
         }
      }

      return fixes;
   }

   public String readAction(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
         return (String)pdc.get(this.actionKey, PersistentDataType.STRING);
      } else {
         return null;
      }
   }

   public String readActionData(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
         return (String)pdc.getOrDefault(this.dataKey, PersistentDataType.STRING, "");
      } else {
         return "";
      }
   }

   private List<HotbarItemsLibrary.HotbarItemDef> defaults() {
      List<HotbarItemsLibrary.HotbarItemDef> defs = new ArrayList<>();
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            0,
            Material.COMPASS,
            title("Game Selector"),
            GuiItems.standardLore(List.of("Choose your next live server or queue.", "Lobby, Survival, and Minigames in one menu."), "Click to open"),
            "OPEN_SELECTOR",
            ""
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            2,
            Material.PLAYER_HEAD,
            title("Lobby Minigames"),
            GuiItems.standardLore(List.of("Quickly jump into Duels,", "Knockback Tag, or Hide and Seek!"), "Click to open"),
            "OPEN_LOBBY_MINIGAMES",
            "",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3OTQ2NzMzMzA2NCwKICAicHJvZmlsZUlkIiA6ICI3ZGEyYWIzYTkzY2E0OGVlODMwNDhhZmMzYjgwZTY4ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJHb2xkYXBmZWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWEyMDg3YTRkNjNhYTM0ZmEyYjJlMjk3MWJjZWNhZjVhNzYwYTkzNGFmYTQ4MjBiOTAyNTM5NTNmOGZkZjMyMiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            4,
            Material.NETHER_STAR,
            title("Network Navigator"),
            GuiItems.standardLore(List.of("Browse menus, destinations, and network tools.", "Start from the full SkyPvP navigator."), "Click to open"),
            "OPEN_MENU",
            ""
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            7,
            Material.BOOK,
            title("Help Center"),
            GuiItems.standardLore(List.of("Review commands, shortcuts, and utility menus."), "Click to browse"),
            "OPEN_HELP",
            ""
         )
      );
      defs.add(
         new HotbarItemsLibrary.HotbarItemDef(
            8,
            Material.PLAYER_HEAD,
            title("Your Profile"),
            GuiItems.standardLore(List.of("View your stats, coins, and progression."), "Click to view"),
            "OPEN_PROFILE",
            ""
         )
      );
      return defs;
   }

   private boolean matchesDef(ItemStack item, HotbarItemsLibrary.HotbarItemDef def) {
      if (item != null && item.getType() == def.material() && item.hasItemMeta()) {
         String action = this.readAction(item);
         return def.action().equalsIgnoreCase(action);
      } else {
         return false;
      }
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
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      pdc.set(this.actionKey, PersistentDataType.STRING, def.action());
      pdc.set(this.dataKey, PersistentDataType.STRING, def.actionData());
      item.setItemMeta(meta);
      return item;
   }

   private static String title(String label) {
      return "<" + TITLE_HEX + "><bold>" + label + "</bold><reset>";
   }

   private static String secondaryTitle(String label) {
      return "<" + SECONDARY_HEX + "><bold>" + label + "</bold><reset>";
   }

   private static record HotbarItemDef(int slot, Material material, String name, List<String> lore, String action, String actionData, String textureValue) {
      public HotbarItemDef(int slot, Material material, String name, List<String> lore, String action, String actionData) {
         this(slot, material, name, lore, action, actionData, null);
      }
   }
}
