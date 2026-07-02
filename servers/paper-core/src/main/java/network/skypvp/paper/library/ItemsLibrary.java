package network.skypvp.paper.library;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextDecoration.State;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class ItemsLibrary {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage

   private ItemsLibrary() {
   }

   public static ItemsLibrary.Builder builder(Material material) {
      return new ItemsLibrary.Builder(material);
   }

   public static ItemsLibrary.Builder builder(Material material, int amount) {
      return new ItemsLibrary.Builder(material, amount);
   }

   public static ItemsLibrary.Builder builder(ItemStack itemStack) {
      return new ItemsLibrary.Builder(itemStack);
   }

   public static class Builder {
      private final ItemStack item;
      private final ItemMeta meta;

      private Builder(Material material) {
         this(material, 1);
      }

      private Builder(Material material, int amount) {
         this.item = new ItemStack(material, amount);
         this.meta = this.item.getItemMeta();
      }

      private Builder(ItemStack itemStack) {
         this.item = itemStack.clone();
         this.meta = this.item.getItemMeta();
      }

      public ItemsLibrary.Builder amount(int amount) {
         this.item.setAmount(amount);
         return this;
      }

      private Component parseText(String text) {
         if (text == null) {
            return Component.text("");
         } else {
            String parsed = text.replace("&0", "<black>")
               .replace("&1", "<dark_blue>")
               .replace("&2", "<dark_green>")
               .replace("&3", "<dark_aqua>")
               .replace("&4", "<dark_red>")
               .replace("&5", "<dark_purple>")
               .replace("&6", "<gold>")
               .replace("&7", "<gray>")
               .replace("&8", "<dark_gray>")
               .replace("&9", "<blue>")
               .replace("&a", "<green>")
               .replace("&b", "<aqua>")
               .replace("&c", "<red>")
               .replace("&d", "<light_purple>")
               .replace("&e", "<yellow>")
               .replace("&f", "<white>")
               .replace("&k", "<obfuscated>")
               .replace("&l", "<bold>")
               .replace("&m", "<strikethrough>")
               .replace("&n", "<underlined>")
               .replace("&o", "<italic>")
               .replace("&r", "<reset>");
            return ServerTextUtil.miniMessageComponent(parsed).decorationIfAbsent(TextDecoration.ITALIC, State.FALSE);
         }
      }

      public ItemsLibrary.Builder name(String name) {
         if (this.meta != null) {
            this.meta.displayName(this.parseText(name));
         }

         return this;
      }

      public ItemsLibrary.Builder lore(List<String> lore) {
         if (this.meta != null) {
            List<Component> components = new ArrayList<>();

            for (String line : lore) {
               components.add(this.parseText(line));
            }

            this.meta.lore(components);
         }

         return this;
      }

      public ItemsLibrary.Builder addLore(String line) {
         if (this.meta != null) {
            List<Component> lore = this.meta.lore();
            if (lore == null) {
               lore = new ArrayList<>();
            }

            lore.add(this.parseText(line));
            this.meta.lore(lore);
         }

         return this;
      }

      public ItemsLibrary.Builder enchant(Enchantment enchantment, int level) {
         if (this.meta != null) {
            this.meta.addEnchant(enchantment, level, true);
         }

         return this;
      }

      public ItemsLibrary.Builder glow() {
         if (this.meta != null) {
            this.meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            this.meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
         }

         return this;
      }

      public ItemsLibrary.Builder hideEnchants() {
         if (this.meta != null) {
            this.meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
         }

         return this;
      }

      public ItemsLibrary.Builder hideAttributes() {
         if (this.meta != null) {
            this.meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         }

         return this;
      }

      /**
       * Writes a value into the item's {@link PersistentDataContainer} under the
       * namespaced {@code key}, scoped to the given {@code plugin}. Used to tag
       * items with hidden metadata (e.g. voucher ids) that survives serialization.
       */
      public <T, Z> ItemsLibrary.Builder tag(Plugin plugin, String key, PersistentDataType<T, Z> type, Z value) {
         if (this.meta != null) {
            NamespacedKey namespacedKey = new NamespacedKey(plugin, key);
            this.meta.getPersistentDataContainer().set(namespacedKey, type, value);
         }

         return this;
      }

      public ItemsLibrary.Builder applyMeta(Consumer<ItemMeta> metaConsumer) {
         if (this.meta != null) {
            metaConsumer.accept(this.meta);
         }

         return this;
      }

      public ItemStack build() {
         if (this.meta != null) {
            this.item.setItemMeta(this.meta);
         }

         return this.item;
      }
   }
}
