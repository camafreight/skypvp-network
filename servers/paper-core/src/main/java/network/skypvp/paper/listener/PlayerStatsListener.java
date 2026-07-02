package network.skypvp.paper.listener;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PlayerStatsRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerStatsListener implements Listener {
   private static final int LEADERBOARD_PLACEHOLDER_LIMIT = 100;
   private final PaperCorePlugin plugin;
   private final PlayerStatsRepository stats;
   private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
   private final Map<String, List<PlayerStatsRepository.PlayerStats>> topCache = new ConcurrentHashMap<>();

   public PlayerStatsListener(PaperCorePlugin plugin, PlayerStatsRepository stats) {
      this.plugin = plugin;
      this.stats = stats;
      plugin.platformScheduler().runAsyncTimer( this::refreshLeaderboardCache, 20L, 1200L);
   }

   private void refreshLeaderboardCache() {
      this.topCache.put("hns_hider_wins", this.stats.getTop("hns_hider_wins", LEADERBOARD_PLACEHOLDER_LIMIT));
      this.topCache.put("hns_seeker_wins", this.stats.getTop("hns_seeker_wins", LEADERBOARD_PLACEHOLDER_LIMIT));
      this.topCache.put("hns_players_found", this.stats.getTop("hns_players_found", LEADERBOARD_PLACEHOLDER_LIMIT));
      this.topCache.put("duel_wins", this.stats.getTop("duel_wins", LEADERBOARD_PLACEHOLDER_LIMIT));
      this.topCache.put("duel_losses", this.stats.getTop("duel_losses", LEADERBOARD_PLACEHOLDER_LIMIT));
   }

   public String resolveTopPlaceholder(String statType, int rank, boolean isName) {
      List<PlayerStatsRepository.PlayerStats> list = this.topCache.getOrDefault(statType, Collections.emptyList());
      if (isName) {
         return list.size() >= rank ? list.get(rank - 1).name() : "None";
      } else {
         if (list.size() < rank) {
            return "0";
         } else {
            PlayerStatsRepository.PlayerStats p = list.get(rank - 1);
            return this.statValue(statType, p);
         }
      }
   }

   private String statValue(String statType, PlayerStatsRepository.PlayerStats p) {
      return switch (statType) {
         case "hns_hider_wins" -> String.valueOf(p.hnsHiderWins());
         case "hns_seeker_wins" -> String.valueOf(p.hnsSeekerWins());
         case "hns_players_found" -> String.valueOf(p.hnsPlayersFound());
         case "duel_wins" -> String.valueOf(p.duelWins());
         case "duel_losses" -> String.valueOf(p.duelLosses());
         default -> "0";
      };
   }

   public long currentSessionMinutes(UUID playerId) {
      Long joinTime = this.joinTimes.get(playerId);
      return joinTime == null ? 0L : Math.max(0L, (System.currentTimeMillis() - joinTime) / 60000L);
   }

   public void seedOnlinePlayers() {
      long now = System.currentTimeMillis();

      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.joinTimes.putIfAbsent(player.getUniqueId(), now);
         this.plugin.platformScheduler().runAsync( () -> this.stats.ensurePlayer(player.getUniqueId(), player.getName()));
      }
   }

   public void flushTrackedPlaytime() {
      long now = System.currentTimeMillis();

      for (Entry<UUID, Long> entry : this.joinTimes.entrySet()) {
         UUID playerId = entry.getKey();
         long joinTime = entry.getValue();
         long minutes = Math.max(0L, (now - joinTime) / 60000L);
         if (this.joinTimes.remove(playerId, joinTime) && minutes > 0L) {
            this.stats.addPlaytimeMinutes(playerId, minutes);
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
      this.plugin.platformScheduler().runAsync( () -> this.stats.ensurePlayer(player.getUniqueId(), player.getName()));
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onDeath(PlayerDeathEvent event) {
      Player dead = event.getPlayer();
      this.plugin.platformScheduler().runAsync( () -> this.stats.incrementDeaths(dead.getUniqueId()));
      Player var4 = dead.getKiller();
      if (var4 instanceof Player) {
         this.plugin.platformScheduler().runAsync( () -> this.stats.incrementKills(var4.getUniqueId()));
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      Long joinTime = this.joinTimes.remove(id);
      if (joinTime != null) {
         long minutes = (System.currentTimeMillis() - joinTime) / 60000L;
         if (minutes > 0L) {
            this.plugin.platformScheduler().runAsync( () -> this.stats.addPlaytimeMinutes(id, minutes));
         }
      }
   }
}
