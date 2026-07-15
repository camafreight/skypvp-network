package network.skypvp.paper.gui;

import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class GuiClickContext {
   private final GuiManager manager;
   private final Player viewer;
   private final InventoryClickEvent event;

   GuiClickContext(GuiManager manager, Player viewer, InventoryClickEvent event) {
      this.manager = manager;
      this.viewer = viewer;
      this.event = event;
   }

   public Player viewer() {
      return this.viewer;
   }

   public int rawSlot() {
      return this.event.getRawSlot();
   }

   public int slot() {
      return this.event.getSlot();
   }

   public boolean isTopInventorySlot() {
      return this.rawSlot() >= 0 && this.rawSlot() < this.event.getView().getTopInventory().getSize();
   }

   public ItemStack currentItem() {
      return this.event.getCurrentItem();
   }

   public InventoryClickEvent event() {
      return this.event;
   }

   public void close() {
      this.viewer.closeInventory();
   }

   public void refresh() {
      this.manager.refresh(this.viewer);
   }

   public void open(GuiMenu menu) {
      NetworkSoundCue.UI_BUTTON_CLICK.play(this.viewer);
      this.manager.openChild(this.viewer, menu);
   }

   public void reopen(GuiMenu menu) {
      NetworkSoundCue.UI_BUTTON_CLICK.play(this.viewer);
      this.manager.open(this.viewer, menu);
   }

   public void openAnvilPrompt(GuiAnvilPrompt prompt) {
      NetworkSoundCue.UI_BUTTON_CLICK.play(this.viewer);
      this.manager.openChildAnvilPrompt(this.viewer, prompt);
   }

   public void reopenAnvilPrompt(GuiAnvilPrompt prompt) {
      NetworkSoundCue.UI_BUTTON_CLICK.play(this.viewer);
      this.manager.openAnvilPrompt(this.viewer, prompt);
   }

   public boolean back() {
      boolean moved = this.manager.back(this.viewer);
      if (moved) {
         NetworkSoundCue.UI_MENU_BACK.play(this.viewer);
      }

      return moved;
   }

   public GuiManager manager() {
      return this.manager;
   }

   /** Schedules a one-time sweep after vanilla shift-double-click deposit bursts. */
   public void trackShiftDepositSweep(Runnable sweep) {
      GuiShiftDepositBurst.track(this.viewer, this.manager, sweep);
   }

   public void playSound(NetworkSoundCue cue) {
      if (cue != null) {
         cue.play(this.viewer);
      }
   }
}
