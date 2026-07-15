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

   /** Called after a successful drag into the top inventory (item-interaction menus). */
   default void onPostDrag(Player viewer) {
   }

   /**
    * Opt-in for menus that accept player-placed items (deposit/input slots, sockets). When {@code true} the manager
    * stops blanket-cancelling: the player may freely move items in their own inventory, top-inventory clicks are
    * cancelled and routed to {@link #onClick(GuiClickContext)} (so the menu drives item movement itself), and
    * shift-clicks from the player inventory into the menu are routed to {@link #onShiftInsert(GuiClickContext)}.
    * Default {@code false} preserves the classic static-button behavior.
    */
   default boolean allowsItemInteraction() {
      return false;
   }

   /**
    * When {@code true} for a top-inventory slot, vanilla item movement is allowed (loot containers, corpses).
    * Chrome/control slots should return {@code false} so clicks route to {@link #onClick(GuiClickContext)}.
    */
   default boolean allowsVanillaContentSlot(int rawSlot) {
      return false;
   }

   /** Block hotbar/server items from entering vanilla content slots. */
   default boolean isBlockedPlayerItem(org.bukkit.inventory.ItemStack stack) {
      return false;
   }

   /** Whether drag/drop may place player items into the top inventory. Default {@code false} (loot-only). */
   default boolean allowsDepositToTop() {
      return false;
   }

   /**
    * Called (item-interaction menus only) when the player shift-clicks an item from their own inventory toward the
    * menu. The originating click is already cancelled; implementations move/consume items and re-render themselves.
    */
   default void onShiftInsert(GuiClickContext context) {
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
