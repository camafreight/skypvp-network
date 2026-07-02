package network.skypvp.paper.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public interface GuiMenu {
   Component title();

   int size();

   void render(Player var1, Inventory var2);

   default void onPreOpen(Player viewer, Inventory inventory) {
   }

   default void onPostOpen(Player viewer, Inventory inventory) {
   }

   default boolean onPreClick(GuiClickContext context) {
      return true;
   }

   default void onClick(GuiClickContext context) {
   }

   default void onPostClick(GuiClickContext context) {
   }

   default long clickDebounceMillis() {
      return 125L;
   }

   default boolean lockSlotDuringClick(GuiClickContext context) {
      return context.isTopInventorySlot();
   }

   default void onPreClose(GuiCloseContext context) {
   }

   default void onClose(Player viewer) {
   }

   default void onPostClose(GuiCloseContext context) {
   }
}
