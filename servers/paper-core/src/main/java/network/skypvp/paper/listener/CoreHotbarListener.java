package network.skypvp.paper.listener;

import java.util.Collection;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HotbarActionExtension;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.paper.service.NetworkMenuService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class CoreHotbarListener implements Listener {
   private final PaperCorePlugin plugin;
   private final CoreHotbarService hotbarService;
   private final NetworkMenuService networkMenuService;

   public CoreHotbarListener(PaperCorePlugin plugin, CoreHotbarService hotbarService, NetworkMenuService networkMenuService) {
      this.plugin = plugin;
      this.hotbarService = hotbarService;
      this.networkMenuService = networkMenuService;
   }

   @EventHandler
   public void onInteract(PlayerInteractEvent event) {
      if (event.getHand() != EquipmentSlot.HAND) {
         return;
      }
      Action action = event.getAction();
      if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
         return;
      }
      ItemStack item = event.getItem();
      String hotbarAction = this.hotbarService.readAction(item);
      if (hotbarAction == null) {
         return;
      }
      event.setCancelled(true);
      for (HotbarActionExtension extension : this.extensions()) {
         if (extension.tryHandle(event.getPlayer(), hotbarAction)) {
            return;
         }
      }
      switch (hotbarAction) {
         case CoreHotbarService.ACTION_OPEN_MENU:
            this.networkMenuService.openRootMenu(event.getPlayer());
            break;
         case CoreHotbarService.ACTION_OPEN_SOCIALS:
            this.networkMenuService.openSocialsMenu(event.getPlayer());
            break;
         default:
            break;
      }
   }

   private Collection<HotbarActionExtension> extensions() {
      return this.plugin.getServer().getServicesManager().getRegistrations(HotbarActionExtension.class).stream()
         .map(registration -> registration.getProvider())
         .toList();
   }
}
