package network.skypvp.lobby.integration;

import java.util.Locale;
import java.util.Optional;
import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.lobby.listener.LobbySelectorListener;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HotbarActionExtension;
import network.skypvp.paper.repository.PlayerStatsRepository;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.entity.Player;

public final class LobbyHotbarActionExtension implements HotbarActionExtension {
   private final PaperCorePlugin core;
   private final LobbySelectorListener selectorListener;
   private final LobbyGameManager gameManager;

   public LobbyHotbarActionExtension(PaperCorePlugin core, LobbySelectorListener selectorListener, LobbyGameManager gameManager) {
      this.core = core;
      this.selectorListener = selectorListener;
      this.gameManager = gameManager;
   }

   @Override
   public boolean tryHandle(Player player, String action) {
      if (action == null || this.gameManager.isInGame(player.getUniqueId())) {
         return false;
      }

      return switch (action.toUpperCase(Locale.ROOT)) {
         case CoreHotbarService.ACTION_OPEN_NAVIGATOR, CoreHotbarService.ACTION_OPEN_SELECTOR -> {
            this.selectorListener.openServerNavigator(player);
            yield true;
         }
         case CoreHotbarService.ACTION_OPEN_LOBBY_MINIGAMES -> {
            player.performCommand("lobbygame menu");
            yield true;
         }
         case CoreHotbarService.ACTION_OPEN_HELP -> {
            this.sendLobbyHelp(player);
            yield true;
         }
         case CoreHotbarService.ACTION_OPEN_PROFILE -> {
            this.openProfile(player);
            yield true;
         }
         default -> false;
      };
   }

   private void sendLobbyHelp(Player player) {
      player.sendMessage(ServerTextUtil.miniMessageComponent("<#55FFFF><bold>Lobby Help</bold><reset>"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gray>• Nether star — network navigator (lobby hubs and Aether Breach)"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gray>• Minigames head — knockback tag and hide & seek"));
      player.sendMessage(ServerTextUtil.miniMessageComponent("<gray>• Commands: <white>/party<gray>, <white>/friend<gray>, <white>/lobbygame leave"));
   }

   private void openProfile(Player player) {
      PlayerStatsRepository statsRepository = this.core.playerStatsRepository();
      if (statsRepository == null) {
         player.sendMessage(ServerTextUtil.miniMessageComponent("<red>Stats are unavailable right now.</red>"));
         return;
      }

      this.core.platformScheduler().runAsync(() -> {
         Optional<PlayerStatsRepository.PlayerStats> stats = statsRepository.getStats(player.getUniqueId());
         this.core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
               return;
            }
            if (stats.isEmpty()) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<gray>No stats recorded yet. Play a round to get started!</gray>"));
               return;
            }
            PlayerStatsRepository.PlayerStats profile = stats.get();
            double kdr = profile.deaths() == 0L ? (double)profile.kills() : (double)profile.kills() / (double)profile.deaths();
            player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<#55FFFF><bold>" + player.getName() + "</bold><reset> <gray>profile"
            ));
            player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<gray>K/D: <white>" + profile.kills() + "<gray>/<white>" + profile.deaths()
                  + " <gray>(<white>" + String.format(Locale.US, "%.2f", kdr) + "<gray>)"
            ));
            player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<gray>Playtime: <white>" + profile.playtimeMinutes() + "m"
                  + " <gray>• Extractions: <white>" + profile.extractions()
            ));
            player.sendMessage(ServerTextUtil.miniMessageComponent(
               "<gray>Duels: <white>" + profile.duelWins() + "W <gray>/<white> " + profile.duelLosses() + "L"
                  + " <gray>• HNS wins: <white>" + (profile.hnsHiderWins() + profile.hnsSeekerWins())
            ));
         });
      });
   }
}
