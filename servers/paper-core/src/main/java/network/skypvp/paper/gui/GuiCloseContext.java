package network.skypvp.paper.gui;

import org.bukkit.entity.Player;

public final class GuiCloseContext {
   private final GuiManager manager;
   private final Player viewer;
   private final GuiCloseReason reason;

   GuiCloseContext(GuiManager manager, Player viewer, GuiCloseReason reason) {
      this.manager = manager;
      this.viewer = viewer;
      this.reason = reason;
   }

   public GuiManager manager() {
      return this.manager;
   }

   public Player viewer() {
      return this.viewer;
   }

   public GuiCloseReason reason() {
      return this.reason;
   }
}
