package network.skypvp.paper.gui;

import network.skypvp.shared.ServerTextUtil;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public final class GuiItems {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage

   private GuiItems() {
   }

   public static ItemStack named(Material material, String name, List<String> lore) {
      return named(material, ServerTextUtil.miniMessageComponent(name), lore.stream().<Component>map(ServerTextUtil::miniMessageComponent).toList());
   }

   public static ItemStack named(Material material, Component name, List<Component> lore) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      meta.displayName(name);
      meta.lore(lore);
      item.setItemMeta(meta);
      return item;
   }

   public static ItemStack playerHead(Player player, String name, List<String> lore) {
      ItemStack item = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta meta = (SkullMeta)item.getItemMeta();
      applyUnsignedSkin(meta, player);
      meta.displayName(ServerTextUtil.miniMessageComponent(name));
      meta.lore(lore.stream().map(ServerTextUtil::miniMessageComponent).toList());
      item.setItemMeta(meta);
      return item;
   }

   public static ItemStack playerHead(UUID playerId, String name, List<String> lore) {
      ItemStack item = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta meta = (SkullMeta)item.getItemMeta();
      meta.setPlayerProfile(Bukkit.createProfile(playerId, name));
      meta.displayName(ServerTextUtil.miniMessageComponent(name));
      meta.lore(lore.stream().map(ServerTextUtil::miniMessageComponent).toList());
      item.setItemMeta(meta);
      return item;
   }

   public static void applyUnsignedSkin(SkullMeta meta, Player player) {
      PlayerProfile clean = Bukkit.createProfile(player.getUniqueId(), player.getName());

      for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
         if ("textures".equals(property.getName())) {
            clean.setProperty(new ProfileProperty("textures", property.getValue()));
            break;
         }
      }

      meta.setPlayerProfile(clean);
   }

   public static List<String> standardLore(List<String> bodyLines, String... footerLines) {
      return GuiTextLibrary.standardLore(bodyLines, footerLines);
   }
}
