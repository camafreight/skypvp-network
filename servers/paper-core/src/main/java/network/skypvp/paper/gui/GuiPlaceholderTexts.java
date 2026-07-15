package network.skypvp.paper.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.hud.ScoreboardText;
import network.skypvp.paper.integration.SkyPvPPlaceholderSupport;
import network.skypvp.paper.library.ItemsLibrary;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class GuiPlaceholderTexts {
   private GuiPlaceholderTexts() {
   }

   public static String resolveTemplate(PaperCorePlugin plugin, Player player, String template) {
      if (template == null || template.isBlank()) {
         return "";
      }
      String resolved = SkyPvPPlaceholderSupport.replacePlaceholders(plugin, player, template);
      if (player != null && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         resolved = PlaceholderAPI.setPlaceholders(player, resolved);
      }
      return resolved;
   }

   public static Component component(PaperCorePlugin plugin, Player player, String template, long tickMillis) {
      String resolved = resolveTemplate(plugin, player, template);
      return ScoreboardText.render(resolved, tickMillis);
   }

   public static List<Component> loreComponents(PaperCorePlugin plugin, Player player, List<String> templates, long tickMillis) {
      if (templates == null || templates.isEmpty()) {
         return List.of();
      }
      List<Component> lines = new ArrayList<>(templates.size());
      for (String template : templates) {
         if (template == null || template.isBlank()) {
            continue;
         }
         lines.add(component(plugin, player, template, tickMillis));
      }
      return List.copyOf(lines);
   }

   public static ItemStack item(
      Material material,
      String nameTemplate,
      List<String> loreTemplates,
      PaperCorePlugin plugin,
      Player player,
      long tickMillis,
      boolean glow
   ) {
      Objects.requireNonNull(material, "material");
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.displayName(component(plugin, player, nameTemplate, tickMillis));
         List<Component> lore = loreComponents(plugin, player, loreTemplates, tickMillis);
         if (!lore.isEmpty()) {
            meta.lore(lore);
         }
         if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
         }
         item.setItemMeta(meta);
      }
      return item;
   }

   public static ItemStack glowItem(
      Material material,
      String accentHex,
      String title,
      java.util.function.Consumer<GuiTextLibrary.LoreBuilder> loreConsumer,
      boolean glow
   ) {
      GuiTextLibrary.LoreBuilder loreBuilder = GuiTextLibrary.lore();
      if (loreConsumer != null) {
         loreConsumer.accept(loreBuilder);
      }
      ItemStack item = ItemsLibrary.builder(material)
         .name(GuiTextLibrary.title(accentHex, title))
         .lore(loreBuilder.build())
         .applyMeta(meta -> {
            if (glow) {
               meta.addEnchant(Enchantment.UNBREAKING, 1, true);
               meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
         })
         .build();
      return item;
   }

   public static String tickKey(long tickMillis) {
      return Long.toString(Math.max(0L, tickMillis / 250L));
   }
}
