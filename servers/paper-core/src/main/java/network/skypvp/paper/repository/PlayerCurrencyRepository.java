package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.paper.database.AsyncDbExecutor;

/**
 * Per-player network currency balances ({@code gold} = premium store currency,
 * {@code coins} = soft currency traded between players). Lives in the game-owned
 * {@code skypvp_network} database. Balances default to 0; the store/economy is
 * expected to credit {@code gold} later via {@link #addGold(UUID, long)}.
 */
public final class PlayerCurrencyRepository {

   private static final String DDL = "CREATE TABLE IF NOT EXISTS network_player_currency (\n"
      + "    player_uuid UUID PRIMARY KEY,\n"
      + "    gold        BIGINT NOT NULL DEFAULT 0,\n"
      + "    coins       BIGINT NOT NULL DEFAULT 0,\n"
      + "    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()\n"
      + ");\n";

   private final DatabaseManager db;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private volatile boolean ready = false;

   public PlayerCurrencyRepository(DatabaseManager db, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.db = db;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get();
      } catch (Exception ex) {
         this.logger.warning("[Currency] " + label + " failed: " + ex.getMessage());
         throw new RuntimeException(ex);
      }
   }

   public void init() {
      try {
         this.executeAsync("currency.init", connection -> {
            try (Statement st = connection.createStatement()) {
               st.execute(DDL);
               st.execute("ALTER TABLE network_player_currency ADD COLUMN IF NOT EXISTS gold BIGINT NOT NULL DEFAULT 0;");
               st.execute("ALTER TABLE network_player_currency ADD COLUMN IF NOT EXISTS coins BIGINT NOT NULL DEFAULT 0;");
            }
            return null;
         });
         this.ready = true;
      } catch (Exception ex) {
         this.logger.warning("[Currency] Failed to create currency table: " + ex.getMessage());
      }
   }

   public void ensurePlayer(UUID uuid) {
      if (!this.ready) {
         return;
      }
      String sql = "INSERT INTO network_player_currency (player_uuid) VALUES (?) ON CONFLICT (player_uuid) DO NOTHING";
      try {
         this.executeAsync("currency.ensurePlayer", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setObject(1, uuid);
               ps.executeUpdate();
            }
            return null;
         });
      } catch (Exception ex) {
         this.logger.warning("[Currency] ensurePlayer failed: " + ex.getMessage());
      }
   }

   public Optional<Balance> getBalance(UUID uuid) {
      if (!this.ready) {
         return Optional.empty();
      }
      String sql = "SELECT gold, coins FROM network_player_currency WHERE player_uuid = ?";
      try {
         return this.executeAsync("currency.getBalance", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setObject(1, uuid);
               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     return Optional.of(new Balance(rs.getLong("gold"), rs.getLong("coins")));
                  }
               }
            }
            return Optional.empty();
         });
      } catch (Exception ex) {
         this.logger.warning("[Currency] getBalance failed: " + ex.getMessage());
         return Optional.empty();
      }
   }

   public void addGold(UUID uuid, long amount) {
      this.add(uuid, "gold", amount);
   }

   public void addCoins(UUID uuid, long amount) {
      this.add(uuid, "coins", amount);
   }

   public CompletableFuture<Void> addCurrencyAsync(UUID uuid, String currency, long amount) {
      String column = validateColumn(currency);
      if (!this.ready || column == null || uuid == null || amount <= 0L) {
         return CompletableFuture.completedFuture(null);
      }
      return this.asyncDbExecutor.method_244("currency.add." + column, connection -> {
         this.ensurePlayerSync(connection, uuid);
         String sql = "INSERT INTO network_player_currency (player_uuid, " + column + ") VALUES (?, ?) "
            + "ON CONFLICT (player_uuid) DO UPDATE SET " + column + " = network_player_currency." + column
            + " + EXCLUDED." + column + ", updated_at = NOW()";
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, uuid);
            ps.setLong(2, amount);
            ps.executeUpdate();
         }
      });
   }

   public CompletableFuture<Boolean> takeCurrencyAsync(UUID uuid, String currency, long amount) {
      String column = validateColumn(currency);
      if (!this.ready || column == null || uuid == null || amount <= 0L) {
         return CompletableFuture.completedFuture(false);
      }
      String sql = "UPDATE network_player_currency SET " + column + " = " + column + " - ?, updated_at = NOW() "
         + "WHERE player_uuid = ? AND " + column + " >= ?";
      return this.asyncDbExecutor.supply("currency.take." + column, connection -> {
         this.ensurePlayerSync(connection, uuid);
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setObject(2, uuid);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
         }
      });
   }

   public CompletableFuture<Void> setCurrencyAsync(UUID uuid, String currency, long amount) {
      String column = validateColumn(currency);
      if (!this.ready || column == null || uuid == null || amount < 0L) {
         return CompletableFuture.completedFuture(null);
      }
      return this.asyncDbExecutor.method_244("currency.set." + column, connection -> {
         this.ensurePlayerSync(connection, uuid);
         String sql = "INSERT INTO network_player_currency (player_uuid, " + column + ") VALUES (?, ?) "
            + "ON CONFLICT (player_uuid) DO UPDATE SET " + column + " = EXCLUDED." + column + ", updated_at = NOW()";
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, uuid);
            ps.setLong(2, amount);
            ps.executeUpdate();
         }
      });
   }

   public static String validateColumn(String currency) {
      if (currency == null || currency.isBlank()) {
         return null;
      }
      String normalized = currency.toLowerCase(java.util.Locale.ROOT);
      return switch (normalized) {
         case "gold" -> "gold";
         case "coins" -> "coins";
         default -> null;
      };
   }

   private void ensurePlayerSync(java.sql.Connection connection, UUID uuid) throws java.sql.SQLException {
      try (PreparedStatement ps = connection.prepareStatement(
         "INSERT INTO network_player_currency (player_uuid) VALUES (?) ON CONFLICT (player_uuid) DO NOTHING"
      )) {
         ps.setObject(1, uuid);
         ps.executeUpdate();
      }
   }

   /**
    * Atomically deducts {@code amount} coins when the player has sufficient balance.
    * Returns {@code true} when the deduction succeeded.
    */
   public CompletableFuture<Boolean> trySpendCoins(UUID uuid, long amount) {
      if (!this.ready || uuid == null || amount <= 0L) {
         return CompletableFuture.completedFuture(false);
      }
      String sql = "UPDATE network_player_currency SET coins = coins - ?, updated_at = NOW() "
         + "WHERE player_uuid = ? AND coins >= ?";
      return this.asyncDbExecutor.supply("currency.trySpendCoins", connection -> {
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setObject(2, uuid);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
         }
      });
   }

   private void add(UUID uuid, String column, long amount) {
      if (!this.ready || amount == 0L) {
         return;
      }
      // UPSERT so a balance change never silently no-ops on a player without a row yet.
      String sql = "INSERT INTO network_player_currency (player_uuid, " + column + ") VALUES (?, ?) "
         + "ON CONFLICT (player_uuid) DO UPDATE SET " + column + " = network_player_currency." + column
         + " + EXCLUDED." + column + ", updated_at = NOW()";
      try {
         this.executeAsync("currency.add." + column, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setObject(1, uuid);
               ps.setLong(2, amount);
               ps.executeUpdate();
            }
            return null;
         });
      } catch (Exception ex) {
         this.logger.warning("[Currency] add " + column + " failed: " + ex.getMessage());
      }
   }

   public static record Balance(long gold, long coins) {
   }
}
