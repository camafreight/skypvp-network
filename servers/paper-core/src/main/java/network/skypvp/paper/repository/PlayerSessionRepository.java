package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.core.database.DatabaseManager;

public final class PlayerSessionRepository {
   private static final String UPSERT_PLAYER = "INSERT INTO network_players (player_id, last_username, first_seen_at, last_seen_at)\nVALUES (?, ?, now(), now())\nON CONFLICT (player_id) DO UPDATE\n    SET last_username = EXCLUDED.last_username,\n        last_seen_at = now()\n";
   private static final String UPDATE_LAST_SEEN = "UPDATE network_players SET last_seen_at = now() WHERE player_id = ?";
   private static final String INSERT_SESSION = "INSERT INTO network_player_sessions (player_id, server_id, joined_at) VALUES (?, ?, now()) RETURNING session_id";
   private static final String CLOSE_SESSION = "UPDATE network_player_sessions SET left_at = now() WHERE session_id = ? AND left_at IS NULL";
   private static final String SELECT_FIRST_SEEN = "SELECT first_seen_at FROM network_players WHERE player_id = ?";
   private final DatabaseManager databaseManager;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;

   public PlayerSessionRepository(DatabaseManager databaseManager, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.databaseManager = databaseManager;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get();
      } catch (Exception var4) {
         this.logger.warning("[PlayerSession] " + label + " failed: " + var4.getMessage());
         throw new RuntimeException(var4);
      }
   }

   public void upsertPlayer(UUID playerId, String username) {
      try {
         this.executeAsync(
            "session.upsertPlayer",
            connection -> {
               try (PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO network_players (player_id, last_username, first_seen_at, last_seen_at)\nVALUES (?, ?, now(), now())\nON CONFLICT (player_id) DO UPDATE\n    SET last_username = EXCLUDED.last_username,\n        last_seen_at = now()\n"
                  )) {
                  statement.setObject(1, playerId);
                  statement.setString(2, username);
                  statement.executeUpdate();
               }

               return null;
            }
         );
      } catch (Exception var4) {
         this.logger.warning("Failed to upsert player " + playerId + ": " + var4.getMessage());
      }
   }

   public void updateLastSeen(UUID playerId) {
      try {
         this.executeAsync("session.updateLastSeen", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE network_players SET last_seen_at = now() WHERE player_id = ?")) {
               statement.setObject(1, playerId);
               statement.executeUpdate();
            }

            return null;
         });
      } catch (Exception var3) {
         this.logger.warning("Failed to update last_seen for " + playerId + ": " + var3.getMessage());
      }
   }

   public long recordSessionStart(UUID playerId, String serverId) {
      try {
         return this.<Long>executeAsync(
            "session.recordSessionStart",
            connection -> {
               try (PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO network_player_sessions (player_id, server_id, joined_at) VALUES (?, ?, now()) RETURNING session_id"
                  )) {
                  statement.setObject(1, playerId);
                  statement.setString(2, serverId);
                  ResultSet resultSet = statement.executeQuery();
                  if (resultSet.next()) {
                     return resultSet.getLong(1);
                  }
               }

               return -1L;
            }
         );
      } catch (Exception var4) {
         this.logger.warning("Failed to record session start for " + playerId + ": " + var4.getMessage());
         return -1L;
      }
   }

   public void recordSessionEnd(long sessionId) {
      if (sessionId >= 0L) {
         try {
            this.executeAsync(
               "session.recordSessionEnd",
               connection -> {
                  try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE network_player_sessions SET left_at = now() WHERE session_id = ? AND left_at IS NULL"
                     )) {
                     statement.setLong(1, sessionId);
                     statement.executeUpdate();
                  }

                  return null;
               }
            );
         } catch (Exception var4) {
            this.logger.warning("Failed to record session end for session " + sessionId + ": " + var4.getMessage());
         }
      }
   }

   public Optional<Instant> findFirstSeen(UUID playerId) {
      try {
         return this.executeAsync("session.findFirstSeen", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT first_seen_at FROM network_players WHERE player_id = ?")) {
               statement.setObject(1, playerId);

               try (ResultSet resultSet = statement.executeQuery()) {
                  if (resultSet.next()) {
                     Timestamp firstSeen = resultSet.getTimestamp("first_seen_at");
                     return firstSeen == null ? Optional.empty() : Optional.of(firstSeen.toInstant());
                  }
               }
            }

            return Optional.empty();
         });
      } catch (Exception var3) {
         this.logger.warning("Failed to load first_seen for " + playerId + ": " + var3.getMessage());
         return Optional.empty();
      }
   }
}
