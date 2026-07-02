package network.skypvp.proxy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.shared.PunishmentRecord;
import org.slf4j.Logger;

public final class PunishmentRepository implements AutoCloseable {
   // $VF: renamed from: DDL java.lang.String
   private static final String DDL = "CREATE TABLE IF NOT EXISTS network_punishments (\n    id          BIGSERIAL PRIMARY KEY,\n    player_uuid UUID         NOT NULL,\n    player_name VARCHAR(32)  NOT NULL,\n    type        VARCHAR(8)   NOT NULL,\n    reason      TEXT         NOT NULL,\n    issued_by   VARCHAR(36)  NOT NULL,\n    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),\n    expires_at  TIMESTAMPTZ,\n    pardoned    BOOLEAN      NOT NULL DEFAULT FALSE,\n    pardoned_by VARCHAR(36),\n    pardoned_at TIMESTAMPTZ\n);\nCREATE INDEX IF NOT EXISTS idx_punishments_player\n    ON network_punishments (player_uuid, type, pardoned);\n";
   private final DatabaseManager dataSource;
   private final Logger logger;

   public PunishmentRepository(DatabaseManager dataSource, Logger logger) {
      this.dataSource = dataSource;
      this.logger = logger;
      this.initSchema();
   }

   private void initSchema() {
      for (String stmt : "CREATE TABLE IF NOT EXISTS network_punishments (\n    id          BIGSERIAL PRIMARY KEY,\n    player_uuid UUID         NOT NULL,\n    player_name VARCHAR(32)  NOT NULL,\n    type        VARCHAR(8)   NOT NULL,\n    reason      TEXT         NOT NULL,\n    issued_by   VARCHAR(36)  NOT NULL,\n    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),\n    expires_at  TIMESTAMPTZ,\n    pardoned    BOOLEAN      NOT NULL DEFAULT FALSE,\n    pardoned_by VARCHAR(36),\n    pardoned_at TIMESTAMPTZ\n);\nCREATE INDEX IF NOT EXISTS idx_punishments_player\n    ON network_punishments (player_uuid, type, pardoned);\n"
         .split(";")) {
         String trimmed = stmt.strip();
         if (!trimmed.isEmpty()) {
            try (
               Connection c = this.dataSource.getConnection();
               PreparedStatement ps = c.prepareStatement(trimmed);
            ) {
               ps.execute();
            } catch (SQLException var14) {
               this.logger.error("Failed to init punishment schema: {}", var14.getMessage());
            }
         }
      }
   }

   public void issue(UUID playerUuid, String playerName, PunishmentRecord.PunishmentType type, String reason, String issuedBy, Instant expiresAt) {
      String sql = "INSERT INTO network_punishments\n    (player_uuid, player_name, type, reason, issued_by, issued_at, expires_at)\nVALUES (?, ?, ?, ?, ?, NOW(), ?)\n";

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql);
      ) {
         ps.setObject(1, playerUuid);
         ps.setString(2, playerName);
         ps.setString(3, type.name());
         ps.setString(4, reason);
         ps.setString(5, issuedBy);
         ps.setTimestamp(6, expiresAt == null ? null : Timestamp.from(expiresAt));
         ps.executeUpdate();
      } catch (SQLException var16) {
         this.logger.error("Failed to issue punishment ({} {}): {}", type, playerUuid, var16.getMessage());
      }
   }

   public boolean pardon(UUID playerUuid, PunishmentRecord.PunishmentType type, String pardonedBy) {
      String sql = "UPDATE network_punishments\n   SET pardoned    = TRUE,\n       pardoned_by = ?,\n       pardoned_at = NOW()\n WHERE player_uuid = ?\n   AND type        = ?\n   AND pardoned    = FALSE\n   AND (expires_at IS NULL OR expires_at > NOW())\n";

      try {
         boolean var8;
         try (
            Connection c = this.dataSource.getConnection();
            PreparedStatement ps = c.prepareStatement(sql);
         ) {
            ps.setString(1, pardonedBy);
            ps.setObject(2, playerUuid);
            ps.setString(3, type.name());
            int rows = ps.executeUpdate();
            var8 = rows > 0;
         }

         return var8;
      } catch (SQLException var13) {
         this.logger.error("Failed to pardon {} for {}: {}", type, playerUuid, var13.getMessage());
         return false;
      }
   }

   public Optional<PunishmentRecord> findActivePunishment(UUID playerUuid, PunishmentRecord.PunishmentType type) {
      String sql = "SELECT id, player_uuid, player_name, type, reason, issued_by, issued_at,\n       expires_at, pardoned, pardoned_by, pardoned_at\n  FROM network_punishments\n WHERE player_uuid = ?\n   AND type        = ?\n   AND pardoned    = FALSE\n   AND (expires_at IS NULL OR expires_at > NOW())\n ORDER BY issued_at DESC\n LIMIT 1\n";

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql);
      ) {
         ps.setObject(1, playerUuid);
         ps.setString(2, type.name());

         try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(this.mapRow(rs)) : Optional.empty();
         }
      } catch (SQLException var15) {
         this.logger.error("Failed to look up {} for {}: {}", type, playerUuid, var15.getMessage());
         return Optional.empty();
      }
   }

   public int countActiveWarnings(UUID playerUuid, String issuedBy) {
      String sql = "SELECT COUNT(*)\n  FROM network_punishments\n WHERE player_uuid = ?\n   AND type        = 'WARN'\n   AND issued_by   = ?\n   AND pardoned    = FALSE\n   AND (expires_at IS NULL OR expires_at > NOW())\n";

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql);
      ) {
         ps.setObject(1, playerUuid);
         ps.setString(2, issuedBy == null || issuedBy.isBlank() ? "ChatModeration" : issuedBy);

         try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
         }
      } catch (SQLException var15) {
         this.logger.error("Failed to count active warnings for {}: {}", playerUuid, var15.getMessage());
         return 0;
      }
   }

   public List<PunishmentRecord> history(UUID playerUuid) {
      String sql = "SELECT id, player_uuid, player_name, type, reason, issued_by, issued_at,\n       expires_at, pardoned, pardoned_by, pardoned_at\n  FROM network_punishments\n WHERE player_uuid = ?\n ORDER BY issued_at DESC\n LIMIT 20\n";
      List<PunishmentRecord> result = new ArrayList<>();

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql);
      ) {
         ps.setObject(1, playerUuid);

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               result.add(this.mapRow(rs));
            }
         }
      } catch (SQLException var15) {
         this.logger.error("Failed to load punishment history for {}: {}", playerUuid, var15.getMessage());
      }

      return result;
   }

   public Optional<UUID> resolvePlayerUuid(String username) {
      String sql = "SELECT player_uuid FROM network_punishments\n WHERE LOWER(player_name) = LOWER(?)\n ORDER BY issued_at DESC LIMIT 1\n";

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql);
      ) {
         ps.setString(1, username);

         try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
         }
      } catch (SQLException var14) {
         this.logger.error("Failed to resolve UUID for {}: {}", username, var14.getMessage());
         return Optional.empty();
      }
   }

   private PunishmentRecord mapRow(ResultSet rs) throws SQLException {
      Timestamp expiresAt = rs.getTimestamp("expires_at");
      Timestamp pardonedAt = rs.getTimestamp("pardoned_at");
      return new PunishmentRecord(
         rs.getLong("id"),
         rs.getObject("player_uuid", UUID.class),
         rs.getString("player_name"),
         PunishmentRecord.PunishmentType.valueOf(rs.getString("type")),
         rs.getString("reason"),
         rs.getString("issued_by"),
         rs.getTimestamp("issued_at").toInstant(),
         expiresAt == null ? null : expiresAt.toInstant(),
         rs.getBoolean("pardoned"),
         rs.getString("pardoned_by"),
         pardonedAt == null ? null : pardonedAt.toInstant()
      );
   }

   @Override
   public void close() {
   }
}
