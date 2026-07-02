package network.skypvp.paper.listener;

import network.skypvp.shared.ServerTextUtil;

import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerLocaleService;
import network.skypvp.paper.service.PlayerSessionService;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.paper.service.RankService;
import network.skypvp.paper.service.ScoreboardService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final PlayerSessionService sessionService;
   private final RankService rankService;
   private final ScoreboardService scoreboardService;
   private final PlayerSocialSettingsService socialSettingsService;
   private final PlayerLocaleService playerLocaleService;

   public PlayerQuitListener(
      PaperCorePlugin plugin,
      PlayerSessionService sessionService,
      RankService rankService,
      ScoreboardService scoreboardService,
      PlayerSocialSettingsService socialSettingsService,
      PlayerLocaleService playerLocaleService
   ) {
      this.plugin = plugin;
      this.sessionService = sessionService;
      this.rankService = rankService;
      this.scoreboardService = scoreboardService;
      this.socialSettingsService = socialSettingsService;
      this.playerLocaleService = playerLocaleService;
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      String username = event.getPlayer().getName();
      UUID playerId = event.getPlayer().getUniqueId();
      event.quitMessage(null);
      if (this.scoreboardService != null) {
         this.scoreboardService.teardownPlayer(event.getPlayer());
      }

      if (this.plugin.bossBarService() != null) {
         this.plugin.bossBarService().hideForPlayer(event.getPlayer());
      }

      if (this.plugin.extractionInventoryRepository() != null) {
         this.plugin.extractionInventoryRepository().evictPlayer(playerId);
      }

      this.plugin.platformScheduler().runAsync( () -> {
         if (this.sessionService != null) {
            this.sessionService.onPlayerQuit(playerId);
         }

         if (this.rankService != null) {
            this.rankService.evict(playerId);
         }

         if (this.socialSettingsService != null) {
            this.socialSettingsService.evict(playerId);
         }

         if (this.playerLocaleService != null) {
            this.playerLocaleService.evict(playerId);
         }
      });
   }
}
