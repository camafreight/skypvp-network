package network.skypvp.paper.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class GuiMenuBuilder {
   private final Component title;
   private final int size;
   private final Map<Integer, GuiMenuBuilder.GuiButton> buttons = new HashMap<>();
   private ItemStack fillItem;
   private GuiAnimation backgroundAnimation;

   private GuiMenuBuilder(Component title, int size) {
      this.title = title;
      this.size = size;
   }

   public static GuiMenuBuilder create(Component title, int size) {
      return new GuiMenuBuilder(title, size);
   }

   public GuiMenuBuilder button(int slot, ItemStack item, Consumer<GuiClickContext> handler) {
      if (slot < 0 || slot >= this.size) {
         throw new IllegalArgumentException("Slot out of range: " + slot);
      } else if (item == null) {
         throw new IllegalArgumentException("Button item cannot be null");
      } else {
         this.buttons.put(slot, new GuiMenuBuilder.GuiButton(item.clone(), handler));
         return this;
      }
   }

   public GuiMenuBuilder fill(ItemStack item) {
      this.fillItem = item;
      return this;
   }

   /**
    * Animated background/chrome layer. Frames only touch the slots they define; {@link #button(int, ItemStack, Consumer)}
    * slots are always layered on top during {@code render}.
    */
   public GuiMenuBuilder backgroundAnimation(GuiAnimation animation) {
      this.backgroundAnimation = animation;
      return this;
   }

   public GuiMenu build() {
      return new GuiMenuBuilder.BuiltGuiMenu(
         this.title,
         this.size,
         Map.copyOf(this.buttons),
         this.fillItem == null ? null : this.fillItem.clone(),
         this.backgroundAnimation
      );
   }

   private static record BuiltGuiMenu(
      Component title,
      int size,
      Map<Integer, GuiMenuBuilder.GuiButton> buttons,
      ItemStack fillItem,
      GuiAnimation backgroundAnimation
   ) implements AnimatedGuiMenu {
      @Override
      public GuiAnimation backgroundAnimation() {
         return this.backgroundAnimation;
      }

      @Override
      public void render(Player viewer, Inventory inventory) {
         this.renderFrame(viewer, inventory, 0);
      }

      private void renderFrame(Player viewer, Inventory inventory, int frameIndex) {
         inventory.clear();
         if (this.backgroundAnimation != null) {
            this.backgroundAnimation.apply(inventory, frameIndex);
         } else if (this.fillItem != null) {
            for (int i = 0; i < this.size; i++) {
               inventory.setItem(i, this.fillItem.clone());
            }
         }

         for (Entry<Integer, GuiMenuBuilder.GuiButton> entry : this.buttons.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().item().clone());
         }
      }

      @Override
      public void renderAnimatedFrame(Player viewer, Inventory inventory, int frameIndex) {
         if (this.backgroundAnimation == null) {
            return;
         }
         this.backgroundAnimation.apply(inventory, frameIndex);
         for (Entry<Integer, GuiMenuBuilder.GuiButton> entry : this.buttons.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().item().clone());
         }
      }

      @Override
      public void onClick(GuiClickContext context) {
         GuiMenuBuilder.GuiButton button = this.buttons.get(context.rawSlot());
         if (button != null && button.handler() != null) {
            button.handler().accept(context);
         }
      }
   }

   private static record GuiButton(ItemStack item, Consumer<GuiClickContext> handler) {
   }
}
