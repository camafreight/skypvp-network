package network.skypvp.paper.listener;

import network.skypvp.paper.service.RankService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathMessageListener implements Listener {
   private final RankService rankService;

   public DeathMessageListener(RankService rankService) {
      this.rankService = rankService;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPlayerDeath(PlayerDeathEvent event) {
      event.deathMessage(null);
   }
}
