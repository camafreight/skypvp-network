package network.skypvp.paper.listener;

import network.skypvp.paper.library.HolographicLibrary;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class HologramInteractListener implements Listener {
   private final HolographicLibrary holographicLibrary;

   public HologramInteractListener(HolographicLibrary holographicLibrary) {
      this.holographicLibrary = holographicLibrary;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEntityEvent event) {
      if (event.getHand() == EquipmentSlot.HAND) {
         if (this.holographicLibrary.handleInteract(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player player) {
         if (this.holographicLibrary.handleInteract(player, event.getEntity())) {
            event.setCancelled(true);
         }
      }
   }
}
