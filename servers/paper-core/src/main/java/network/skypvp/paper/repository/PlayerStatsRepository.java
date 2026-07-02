package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.core.database.DatabaseManager;

public final class PlayerStatsRepository {
   // $VF: renamed from: DDL java.lang.String
   private static final String DDL = "CREATE TABLE IF NOT EXISTS network_player_stats (\n    player_uuid  UUID    PRIMARY KEY,\n    player_name  TEXT    NOT NULL,\n    kills        BIGINT  NOT NULL DEFAULT 0,\n    deaths       BIGINT  NOT NULL DEFAULT 0,\n    playtime_min BIGINT  NOT NULL DEFAULT 0,\n    hns_hider_wins    BIGINT NOT NULL DEFAULT 0,\n    hns_seeker_wins   BIGINT NOT NULL DEFAULT 0,\n    hns_players_found BIGINT NOT NULL DEFAULT 0,\n    duel_wins    BIGINT NOT NULL DEFAULT 0,\n    duel_losses  BIGINT NOT NULL DEFAULT 0,\n    last_seen    TIMESTAMPTZ NOT NULL DEFAULT NOW()\n);\n";
   // $VF: renamed from: db network.SkyPvP.paper.persistence.DatabaseManager
   private final DatabaseManager db;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private volatile boolean ready = false;

   public PlayerStatsRepository(DatabaseManager db, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.db = db;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get();
      } catch (Exception var4) {
         this.logger.warning("[PlayerStats] " + label + " failed: " + var4.getMessage());
         throw new RuntimeException(var4);
      }
   }

   public void init() {
      try {
         this.executeAsync(
            "stats.init",
            connection -> {
               try (Statement st = connection.createStatement()) {
                  st.execute(
                     "CREATE TABLE IF NOT EXISTS network_player_stats (\n    player_uuid  UUID    PRIMARY KEY,\n    player_name  TEXT    NOT NULL,\n    kills        BIGINT  NOT NULL DEFAULT 0,\n    deaths       BIGINT  NOT NULL DEFAULT 0,\n    playtime_min BIGINT  NOT NULL DEFAULT 0,\n    hns_hider_wins    BIGINT NOT NULL DEFAULT 0,\n    hns_seeker_wins   BIGINT NOT NULL DEFAULT 0,\n    hns_players_found BIGINT NOT NULL DEFAULT 0,\n    duel_wins    BIGINT NOT NULL DEFAULT 0,\n    duel_losses  BIGINT NOT NULL DEFAULT 0,\n    last_seen    TIMESTAMPTZ NOT NULL DEFAULT NOW()\n);\n"
                  );
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS hns_hider_wins BIGINT NOT NULL DEFAULT 0;");
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS hns_seeker_wins BIGINT NOT NULL DEFAULT 0;");
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS hns_players_found BIGINT NOT NULL DEFAULT 0;");
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS duel_wins BIGINT NOT NULL DEFAULT 0;");
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS duel_losses BIGINT NOT NULL DEFAULT 0;");
                  st.execute("ALTER TABLE network_player_stats ADD COLUMN IF NOT EXISTS extractions BIGINT NOT NULL DEFAULT 0;");
               }

               return null;
            }
         );
         this.ready = true;
      } catch (Exception var2) {
         this.logger.warning("[PlayerStats] Failed to create stats table: " + var2.getMessage());
      }
   }

   public void ensurePlayer(UUID uuid, String name) {
      if (this.ready) {
         String sql = "INSERT INTO network_player_stats (player_uuid, player_name) VALUES (?,?) ON CONFLICT (player_uuid) DO UPDATE SET player_name = EXCLUDED.player_name, last_seen = NOW()";

         try {
            this.executeAsync("stats.ensurePlayer", connection -> {
               try (PreparedStatement ps = connection.prepareStatement(sql)) {
                  ps.setObject(1, uuid);
                  ps.setString(2, name);
                  ps.executeUpdate();
               }

               return null;
            });
         } catch (Exception var5) {
            this.logger.warning("[PlayerStats] ensurePlayer failed: " + var5.getMessage());
         }
      }
   }

   public void incrementKills(UUID uuid) {
      this.increment(uuid, "kills");
   }

   public void incrementDeaths(UUID uuid) {
      this.increment(uuid, "deaths");
   }

   public void incrementHnsHiderWins(UUID uuid) {
      this.increment(uuid, "hns_hider_wins");
   }

   public void incrementHnsSeekerWins(UUID uuid) {
      this.increment(uuid, "hns_seeker_wins");
   }

   public void incrementHnsPlayersFound(UUID uuid) {
      this.increment(uuid, "hns_players_found");
   }

   public void incrementDuelWins(UUID uuid) {
      this.increment(uuid, "duel_wins");
   }

   public void incrementDuelLosses(UUID uuid) {
      this.increment(uuid, "duel_losses");
   }

   public void incrementExtractions(UUID uuid) {
      this.increment(uuid, "extractions");
   }

   public void addPlaytimeMinutes(UUID uuid, long minutes) {
      if (this.ready && minutes > 0L) {
         String sql = "UPDATE network_player_stats SET playtime_min = playtime_min + ?, last_seen = NOW() WHERE player_uuid = ?";

         try {
            this.executeAsync("stats.addPlaytime", connection -> {
               try (PreparedStatement ps = connection.prepareStatement(sql)) {
                  ps.setLong(1, minutes);
                  ps.setObject(2, uuid);
                  ps.executeUpdate();
               }

               return null;
            });
         } catch (Exception var6) {
            this.logger.warning("[PlayerStats] addPlaytime failed: " + var6.getMessage());
         }
      }
   }

   public Optional<PlayerStatsRepository.PlayerStats> getStats(UUID uuid) {
      if (!this.ready) {
         return Optional.empty();
      } else {
         String sql = "SELECT player_name, kills, deaths, playtime_min, hns_hider_wins, hns_seeker_wins, hns_players_found, duel_wins, duel_losses, extractions FROM network_player_stats WHERE player_uuid = ?";

         try {
            return this.executeAsync(
               "stats.getStats",
               connection -> {
                  try (PreparedStatement ps = connection.prepareStatement(sql)) {
                     ps.setObject(1, uuid);

                     try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                           return Optional.of(
                              new PlayerStatsRepository.PlayerStats(
                                 rs.getString("player_name"),
                                 rs.getLong("kills"),
                                 rs.getLong("deaths"),
                                 rs.getLong("playtime_min"),
                                 rs.getLong("hns_hider_wins"),
                                 rs.getLong("hns_seeker_wins"),
                                 rs.getLong("hns_players_found"),
                                 rs.getLong("duel_wins"),
                                 rs.getLong("duel_losses"),
                                 rs.getLong("extractions")
                              )
                           );
                        }
                     }
                  }

                  return Optional.empty();
               }
            );
         } catch (Exception var4) {
            this.logger.warning("[PlayerStats] getStats failed: " + var4.getMessage());
            return Optional.empty();
         }
      }
   }

   public Optional<PlayerStatsRepository.PlayerStats> getStatsByName(String name) {
      if (!this.ready) {
         return Optional.empty();
      } else {
         String sql = "SELECT player_name, kills, deaths, playtime_min, hns_hider_wins, hns_seeker_wins, hns_players_found, duel_wins, duel_losses, extractions FROM network_player_stats WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1";

         try {
            return this.executeAsync(
               "stats.getStatsByName",
               connection -> {
                  try (PreparedStatement ps = connection.prepareStatement(sql)) {
                     ps.setString(1, name);

                     try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                           return Optional.of(
                              new PlayerStatsRepository.PlayerStats(
                                 rs.getString("player_name"),
                                 rs.getLong("kills"),
                                 rs.getLong("deaths"),
                                 rs.getLong("playtime_min"),
                                 rs.getLong("hns_hider_wins"),
                                 rs.getLong("hns_seeker_wins"),
                                 rs.getLong("hns_players_found"),
                                 rs.getLong("duel_wins"),
                                 rs.getLong("duel_losses"),
                                 rs.getLong("extractions")
                              )
                           );
                        }
                     }
                  }

                  return Optional.empty();
               }
            );
         } catch (Exception var4) {
            this.logger.warning("[PlayerStats] getStatsByName failed: " + var4.getMessage());
            return Optional.empty();
         }
      }
   }

   private void increment(UUID uuid, String column) {
      if (this.ready) {
         String sql = "UPDATE network_player_stats SET " + column + " = " + column + " + 1, last_seen = NOW() WHERE player_uuid = ?";

         try {
            this.executeAsync("stats.increment." + column, connection -> {
               try (PreparedStatement ps = connection.prepareStatement(sql)) {
                  ps.setObject(1, uuid);
                  ps.executeUpdate();
               }

               return null;
            });
         } catch (Exception var5) {
            this.logger.warning("[PlayerStats] increment " + column + " failed: " + var5.getMessage());
         }
      }
   }

   public List<PlayerStatsRepository.PlayerStats> getTop(String column, int limit) {
      if (!this.ready) {
         return Collections.emptyList();
      } else {
         List<String> allowedColumns = List.of(
            "kills", "deaths", "playtime_min", "hns_hider_wins", "hns_seeker_wins", "hns_players_found", "duel_wins", "duel_losses", "extractions"
         );
         String safeColumn = allowedColumns.contains(column.toLowerCase()) ? column.toLowerCase() : "kills";
         String sql = "SELECT player_name, kills, deaths, playtime_min, hns_hider_wins, hns_seeker_wins, hns_players_found, duel_wins, duel_losses, extractions FROM network_player_stats ORDER BY "
            + safeColumn
            + " DESC LIMIT ?";

         try {
            return this.executeAsync(
               "stats.getTop." + safeColumn,
               connection -> {
                  List<PlayerStatsRepository.PlayerStats> results = new ArrayList<>();

                  try (PreparedStatement ps = connection.prepareStatement(sql)) {
                     ps.setInt(1, limit);

                     try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                           results.add(
                              new PlayerStatsRepository.PlayerStats(
                                 rs.getString("player_name"),
                                 rs.getLong("kills"),
                                 rs.getLong("deaths"),
                                 rs.getLong("playtime_min"),
                                 rs.getLong("hns_hider_wins"),
                                 rs.getLong("hns_seeker_wins"),
                                 rs.getLong("hns_players_found"),
                                 rs.getLong("duel_wins"),
                                 rs.getLong("duel_losses"),
                                 rs.getLong("extractions")
                              )
                           );
                        }
                     }
                  }

                  return results;
               }
            );
         } catch (Exception var7) {
            this.logger.warning("[PlayerStats] getTop failed: " + var7.getMessage());
            return Collections.emptyList();
         }
      }
   }

   public static record PlayerStats(
      String name, long kills, long deaths, long playtimeMinutes, long hnsHiderWins, long hnsSeekerWins, long hnsPlayersFound, long duelWins, long duelLosses, long extractions
   ) {
      // $VF: renamed from: kdr () double
      public double method_255() {
         return this.deaths == 0L ? (double)this.kills : (double)this.kills / (double)this.deaths;
      }
   }
}
