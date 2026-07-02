package network.skypvp.paper.listener;

import network.skypvp.paper.library.NpcLibrary;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class NpcInteractListener implements Listener {
   private final NpcLibrary npcLibrary;

   public NpcInteractListener(NpcLibrary npcLibrary) {
      this.npcLibrary = npcLibrary;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEntityEvent event) {
      if (event.getHand() == EquipmentSlot.HAND) {
         if (this.npcLibrary.handleInteract(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
         }
      }
   }
}
