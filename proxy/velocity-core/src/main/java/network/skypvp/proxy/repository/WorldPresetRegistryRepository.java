package network.skypvp.proxy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.proxy.state.WorldPresetStatus;
import org.slf4j.Logger;

public final class WorldPresetRegistryRepository implements AutoCloseable {
   private static final String UPSERT_PRESET = "insert into network_world_presets (\n    preset_id, version, status, description, checksum_sha256, world_count, created_by, updated_at\n) values (?, ?, ?, ?, ?, ?, ?, now())\non conflict (preset_id) do update\n    set version = excluded.version,\n        status = excluded.status,\n        description = excluded.description,\n        checksum_sha256 = excluded.checksum_sha256,\n        world_count = excluded.world_count,\n        updated_at = now()\n";
   private static final String SELECT_ONE = "select preset_id, version, status, description, checksum_sha256,\n       world_count, created_by, created_at, updated_at\nfrom network_world_presets\nwhere preset_id = ?\nlimit 1\n";
   private static final String SELECT_ALL = "select preset_id, version, status, description, checksum_sha256,\n       world_count, created_by, created_at, updated_at\nfrom network_world_presets\norder by preset_id asc\n";
   private static final String SET_STATUS = "update network_world_presets\nset status = ?,\n    updated_at = now()\nwhere preset_id = ?\n";
   private static final String DEACTIVATE_ACTIVE = "update network_world_presets\nset status = 'DEPRECATED',\n    updated_at = now()\nwhere status = 'ACTIVE'\n  and preset_id <> ?\n";
   private static final String INSERT_PROMOTION_AUDIT = "insert into network_world_preset_promotions (\n    preset_id, from_status, to_status, server_scope, reason, promoted_by, promoted_at\n) values (?, ?, ?, ?, ?, ?, now())\n";
   private static final String SELECT_PROMOTIONS = "select promotion_id, preset_id, from_status, to_status, server_scope, reason, promoted_by, promoted_at\nfrom network_world_preset_promotions\norder by promoted_at desc\nlimit ?\n";
   private final DatabaseManager dataSource;
   private final Logger logger;

   public WorldPresetRegistryRepository(DatabaseManager dataSource, Logger logger) {
      this.dataSource = dataSource;
      this.logger = logger;
   }

   public boolean upsertPreset(
      String presetId, int version, WorldPresetStatus status, String description, String checksumSha256, int worldCount, String createdBy
   ) {
      if (presetId != null && !presetId.isBlank()) {
         try {
            boolean var10;
            try (
               Connection c = this.dataSource.getConnection();
               PreparedStatement s = c.prepareStatement(
                  "insert into network_world_presets (\n    preset_id, version, status, description, checksum_sha256, world_count, created_by, updated_at\n) values (?, ?, ?, ?, ?, ?, ?, now())\non conflict (preset_id) do update\n    set version = excluded.version,\n        status = excluded.status,\n        description = excluded.description,\n        checksum_sha256 = excluded.checksum_sha256,\n        world_count = excluded.world_count,\n        updated_at = now()\n"
               );
            ) {
               s.setString(1, presetId);
               s.setInt(2, Math.max(1, version));
               s.setString(3, (status == null ? WorldPresetStatus.DRAFT : status).name());
               s.setString(4, description == null ? "" : description);
               s.setString(5, checksumSha256);
               s.setInt(6, Math.max(1, worldCount));
               s.setString(7, createdBy != null && !createdBy.isBlank() ? createdBy : "system");
               var10 = s.executeUpdate() > 0;
            }

            return var10;
         } catch (SQLException var16) {
            this.logger.warn("WorldPresetRegistryRepository.upsertPreset: {}", var16.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public List<WorldPresetRegistryRepository.WorldPresetSnapshot> listPresets() {
      List<WorldPresetRegistryRepository.WorldPresetSnapshot> out = new ArrayList<>();

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement s = c.prepareStatement(
            "select preset_id, version, status, description, checksum_sha256,\n       world_count, created_by, created_at, updated_at\nfrom network_world_presets\norder by preset_id asc\n"
         );
         ResultSet rs = s.executeQuery();
      ) {
         while (rs.next()) {
            out.add(this.mapRow(rs));
         }
      } catch (SQLException var13) {
         this.logger.warn("WorldPresetRegistryRepository.listPresets: {}", var13.getMessage());
      }

      return out;
   }

   public Optional<WorldPresetRegistryRepository.WorldPresetSnapshot> findPreset(String presetId) {
      if (presetId != null && !presetId.isBlank()) {
         try (
            Connection c = this.dataSource.getConnection();
            PreparedStatement s = c.prepareStatement(
               "select preset_id, version, status, description, checksum_sha256,\n       world_count, created_by, created_at, updated_at\nfrom network_world_presets\nwhere preset_id = ?\nlimit 1\n"
            );
         ) {
            s.setString(1, presetId);

            try (ResultSet rs = s.executeQuery()) {
               return rs.next() ? Optional.of(this.mapRow(rs)) : Optional.empty();
            }
         } catch (SQLException var13) {
            this.logger.warn("WorldPresetRegistryRepository.findPreset: {}", var13.getMessage());
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   public boolean setStatus(String presetId, WorldPresetStatus toStatus, String reason, String promotedBy) {
      if (presetId != null && !presetId.isBlank() && toStatus != null) {
         Optional<WorldPresetRegistryRepository.WorldPresetSnapshot> existingOpt = this.findPreset(presetId);
         if (existingOpt.isEmpty()) {
            return false;
         } else {
            WorldPresetRegistryRepository.WorldPresetSnapshot existing = existingOpt.get();

            try {
               boolean var12;
               try (Connection c = this.dataSource.getConnection()) {
                  c.setAutoCommit(false);

                  try (
                     PreparedStatement setStatus = c.prepareStatement(
                        "update network_world_presets\nset status = ?,\n    updated_at = now()\nwhere preset_id = ?\n"
                     );
                     PreparedStatement deactivate = c.prepareStatement(
                        "update network_world_presets\nset status = 'DEPRECATED',\n    updated_at = now()\nwhere status = 'ACTIVE'\n  and preset_id <> ?\n"
                     );
                     PreparedStatement audit = c.prepareStatement(
                        "insert into network_world_preset_promotions (\n    preset_id, from_status, to_status, server_scope, reason, promoted_by, promoted_at\n) values (?, ?, ?, ?, ?, ?, now())\n"
                     );
                  ) {
                     if (toStatus == WorldPresetStatus.ACTIVE) {
                        deactivate.setString(1, presetId);
                        deactivate.executeUpdate();
                     }

                     setStatus.setString(1, toStatus.name());
                     setStatus.setString(2, presetId);
                     int updated = setStatus.executeUpdate();
                     if (updated > 0) {
                        audit.setString(1, presetId);
                        audit.setString(2, existing.status().name());
                        audit.setString(3, toStatus.name());
                        audit.setString(4, "global");
                        audit.setString(5, reason);
                        audit.setString(6, promotedBy != null && !promotedBy.isBlank() ? promotedBy : "system");
                        audit.executeUpdate();
                     }

                     c.commit();
                     var12 = updated > 0;
                  } catch (SQLException var32) {
                     c.rollback();
                     throw var32;
                  } finally {
                     c.setAutoCommit(true);
                  }
               }

               return var12;
            } catch (SQLException var35) {
               this.logger.warn("WorldPresetRegistryRepository.setStatus: {}", var35.getMessage());
               return false;
            }
         }
      } else {
         return false;
      }
   }

   public List<WorldPresetRegistryRepository.WorldPresetPromotionAudit> listRecentPromotions(int limit) {
      List<WorldPresetRegistryRepository.WorldPresetPromotionAudit> out = new ArrayList<>();
      int safeLimit = Math.max(1, Math.min(200, limit));

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement s = c.prepareStatement(
            "select promotion_id, preset_id, from_status, to_status, server_scope, reason, promoted_by, promoted_at\nfrom network_world_preset_promotions\norder by promoted_at desc\nlimit ?\n"
         );
      ) {
         s.setInt(1, safeLimit);

         try (ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
               out.add(
                  new WorldPresetRegistryRepository.WorldPresetPromotionAudit(
                     rs.getLong("promotion_id"),
                     rs.getString("preset_id"),
                     rs.getString("from_status"),
                     rs.getString("to_status"),
                     rs.getString("server_scope"),
                     rs.getString("reason"),
                     rs.getString("promoted_by"),
                     rs.getTimestamp("promoted_at").toInstant()
                  )
               );
            }
         }
      } catch (SQLException var15) {
         this.logger.warn("WorldPresetRegistryRepository.listRecentPromotions: {}", var15.getMessage());
      }

      return out;
   }

   private WorldPresetRegistryRepository.WorldPresetSnapshot mapRow(ResultSet rs) throws SQLException {
      return new WorldPresetRegistryRepository.WorldPresetSnapshot(
         rs.getString("preset_id"),
         rs.getInt("version"),
         WorldPresetStatus.fromString(rs.getString("status")),
         rs.getString("description"),
         rs.getString("checksum_sha256"),
         rs.getInt("world_count"),
         rs.getString("created_by"),
         rs.getTimestamp("created_at").toInstant(),
         rs.getTimestamp("updated_at").toInstant()
      );
   }

   @Override
   public void close() {
   }

   public static record WorldPresetPromotionAudit(
      long promotionId, String presetId, String fromStatus, String toStatus, String serverScope, String reason, String promotedBy, Instant promotedAt
   ) {
   }

   public static record WorldPresetSnapshot(
      String presetId,
      int version,
      WorldPresetStatus status,
      String description,
      String checksumSha256,
      int worldCount,
      String createdBy,
      Instant createdAt,
      Instant updatedAt
   ) {
   }
}
