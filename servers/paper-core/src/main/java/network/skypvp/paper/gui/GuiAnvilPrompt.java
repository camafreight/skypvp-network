package network.skypvp.paper.gui;

import network.skypvp.shared.ServerTextUtil;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class GuiAnvilPrompt {
   private final Component title;
   private final String initialText;
   private final ItemStack inputItem;
   private final Function<String, ItemStack> resultItemFactory;
   private final BiConsumer<Player, String> submitHandler;

   private GuiAnvilPrompt(GuiAnvilPrompt.Builder builder) {
      this.title = Objects.requireNonNull(builder.title, "title");
      this.initialText = builder.initialText == null ? "" : builder.initialText;
      this.inputItem = builder.inputItem == null ? new ItemStack(Material.PAPER) : builder.inputItem.clone();
      this.resultItemFactory = Objects.requireNonNull(builder.resultItemFactory, "resultItemFactory");
      this.submitHandler = Objects.requireNonNull(builder.submitHandler, "submitHandler");
   }

   public static GuiAnvilPrompt.Builder builder(Component title) {
      return new GuiAnvilPrompt.Builder(title);
   }

   Component title() {
      return this.title;
   }

   ItemStack buildInputItem() {
      ItemStack item = this.inputItem.clone();
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         String seed = this.initialText.isBlank() ? " " : this.initialText;
         meta.displayName(ServerTextUtil.component(seed).decoration(TextDecoration.ITALIC, false));
         item.setItemMeta(meta);
      }

      item.setAmount(1);
      return item;
   }

   ItemStack buildResultItem(String text) {
      ItemStack result = this.resultItemFactory.apply(text == null ? "" : text);
      return result == null ? defaultResultItem(text) : result.clone();
   }

   void submit(Player viewer, String text) {
      this.submitHandler.accept(viewer, text == null ? "" : text);
   }

   private static ItemStack defaultResultItem(String text) {
      String safe = GuiTextLibrary.safeText(text).trim();
      return GuiItems.named(
         Material.PAPER,
         GuiTextLibrary.title("#FFD700", "Submit"),
         GuiTextLibrary.lore().fact("Input", safe.isBlank() ? "Empty" : safe).footerStrong("<yellow>", "Take result to submit").build()
      );
   }

   public static final class Builder {
      private final Component title;
      private String initialText = "";
      private ItemStack inputItem;
      private Function<String, ItemStack> resultItemFactory = GuiAnvilPrompt::defaultResultItem;
      private BiConsumer<Player, String> submitHandler = (viewer, text) -> {
      };

      private Builder(Component title) {
         this.title = title;
      }

      public GuiAnvilPrompt.Builder initialText(String initialText) {
         this.initialText = initialText == null ? "" : initialText;
         return this;
      }

      public GuiAnvilPrompt.Builder inputItem(ItemStack inputItem) {
         this.inputItem = inputItem == null ? null : inputItem.clone();
         return this;
      }

      public GuiAnvilPrompt.Builder resultItem(Function<String, ItemStack> resultItemFactory) {
         this.resultItemFactory = resultItemFactory;
         return this;
      }

      public GuiAnvilPrompt.Builder onSubmit(BiConsumer<Player, String> submitHandler) {
         this.submitHandler = submitHandler;
         return this;
      }

      public GuiAnvilPrompt build() {
         return new GuiAnvilPrompt(this);
      }
   }
}
