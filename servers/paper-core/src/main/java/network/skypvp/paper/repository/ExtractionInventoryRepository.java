package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.inventory.vault.VaultSlotAccess;

public final class ExtractionInventoryRepository {
   private final AsyncDbExecutor asyncDbExecutor;
   private final Map<ContainerKey, Map<Integer, String>> containerCache = new ConcurrentHashMap<>();

   public ExtractionInventoryRepository(AsyncDbExecutor asyncDbExecutor) {
      this.asyncDbExecutor = asyncDbExecutor;
   }

   public Optional<Map<Integer, String>> getCachedContainer(UUID playerId, String containerType) {
      if (playerId == null || containerType == null) {
         return Optional.empty();
      }
      Map<Integer, String> cached = this.containerCache.get(new ContainerKey(playerId, containerType));
      return cached == null ? Optional.empty() : Optional.of(cached);
   }

   public void evictPlayer(UUID playerId) {
      if (playerId == null) {
         return;
      }
      this.containerCache.keySet().removeIf(key -> key.playerId().equals(playerId));
   }

   public void evictContainer(UUID playerId, String containerType) {
      if (playerId == null || containerType == null) {
         return;
      }
      this.containerCache.remove(new ContainerKey(playerId, containerType));
   }

   private void putCached(UUID playerId, String containerType, Map<Integer, String> slots) {
      if (playerId == null || containerType == null) {
         return;
      }
      Map<Integer, String> snapshot = slots == null ? Map.of() : Map.copyOf(slots);
      this.containerCache.put(new ContainerKey(playerId, containerType), snapshot);
   }

   public CompletableFuture<Void> ensureVaultProgressSchema() {
      return this.asyncDbExecutor.method_244(
         "pim.ensureVaultProgressSchema",
         connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
               "ALTER TABLE extraction_player_profiles ADD COLUMN IF NOT EXISTS vault_unlocked_rows INT NOT NULL DEFAULT 5"
            )) {
               ps.executeUpdate();
            }
         }
      );
   }

   public CompletableFuture<Integer> loadVaultUnlockedRows(UUID playerId) {
      if (playerId == null) {
         return CompletableFuture.completedFuture(VaultSlotAccess.defaultUnlockedRows());
      }
      return this.asyncDbExecutor.supply(
         "pim.loadVaultUnlockedRows",
         connection -> {
            try (PreparedStatement ensure = connection.prepareStatement(
               "INSERT INTO extraction_player_profiles (player_uuid, revision, vault_unlocked_rows, updated_at) "
                  + "VALUES (?, 0, ?, NOW()) ON CONFLICT (player_uuid) DO NOTHING"
            )) {
               ensure.setObject(1, playerId);
               ensure.setInt(2, VaultSlotAccess.defaultUnlockedRows());
               ensure.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
               "SELECT vault_unlocked_rows FROM extraction_player_profiles WHERE player_uuid = ?"
            )) {
               ps.setObject(1, playerId);
               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     return VaultSlotAccess.clampUnlockedRows(rs.getInt("vault_unlocked_rows"));
                  }
               }
            }
            return VaultSlotAccess.defaultUnlockedRows();
         }
      );
   }

   public CompletableFuture<Boolean> incrementVaultUnlockedRows(UUID playerId, int expectedRows) {
      if (playerId == null) {
         return CompletableFuture.completedFuture(false);
      }
      return this.asyncDbExecutor.supply(
         "pim.incrementVaultUnlockedRows",
         connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
               "UPDATE extraction_player_profiles SET vault_unlocked_rows = vault_unlocked_rows + 1, updated_at = NOW() "
                  + "WHERE player_uuid = ? AND vault_unlocked_rows = ? AND vault_unlocked_rows < ?"
            )) {
               ps.setObject(1, playerId);
               ps.setInt(2, expectedRows);
               ps.setInt(3, VaultSlotAccess.maxRows());
               return ps.executeUpdate() > 0;
            }
         }
      );
   }

   public CompletableFuture<Void> preloadContainers(UUID playerId, List<String> containerTypes) {
      if (playerId == null || containerTypes == null || containerTypes.isEmpty()) {
         return CompletableFuture.completedFuture(null);
      }
      List<String> types = List.copyOf(containerTypes);
      return this.asyncDbExecutor.supply(
         "pim.preloadContainers",
         connection -> {
            Map<String, Map<Integer, String>> grouped = new HashMap<>();
            for (String containerType : types) {
               grouped.put(containerType, new HashMap<>());
            }
            String placeholders = String.join(", ", types.stream().map(ignored -> "?").toList());
            String sql = "SELECT container_type, slot_index, payload_b64 FROM extraction_inventory_slots"
               + " WHERE player_uuid = ? AND container_type IN (" + placeholders + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setObject(1, playerId);
               for (int index = 0; index < types.size(); index++) {
                  ps.setString(index + 2, types.get(index));
               }
               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     String containerType = rs.getString("container_type");
                     Map<Integer, String> slots = grouped.get(containerType);
                     if (slots != null) {
                        slots.put(rs.getInt("slot_index"), rs.getString("payload_b64"));
                     }
                  }
               }
            }
            Map<String, Map<Integer, String>> frozen = new HashMap<>();
            grouped.forEach((containerType, slots) -> frozen.put(containerType, Map.copyOf(slots)));
            return frozen;
         }
      ).thenAccept(grouped -> {
         for (String containerType : types) {
            this.putCached(playerId, containerType, grouped.getOrDefault(containerType, Map.of()));
         }
      });
   }

   public CompletableFuture<Long> loadRevision(UUID playerId) {
      if (playerId == null) {
         return CompletableFuture.completedFuture(0L);
      }
      return this.asyncDbExecutor.supply(
         "pim.loadRevision",
         connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT revision FROM extraction_player_profiles WHERE player_uuid = ?")) {
               ps.setObject(1, playerId);
               try (ResultSet rs = ps.executeQuery()) {
                  return rs.next() ? rs.getLong("revision") : 0L;
               }
            }
         }
      );
   }

   public CompletableFuture<Map<Integer, String>> loadContainer(UUID playerId, String containerType) {
      if (playerId == null || containerType == null) {
         return CompletableFuture.completedFuture(Map.of());
      }
      Optional<Map<Integer, String>> cached = this.getCachedContainer(playerId, containerType);
      if (cached.isPresent()) {
         return CompletableFuture.completedFuture(cached.get());
      }
      return this.asyncDbExecutor.supply(
         "pim.loadContainer",
         connection -> {
            String sql = "SELECT slot_index, payload_b64 FROM extraction_inventory_slots WHERE player_uuid = ? AND container_type = ?";
            Map<Integer, String> slots = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setObject(1, playerId);
               ps.setString(2, containerType);
               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     slots.put(rs.getInt("slot_index"), rs.getString("payload_b64"));
                  }
               }
            }
            return Map.copyOf(slots);
         }
      ).thenApply(slots -> {
         this.putCached(playerId, containerType, slots);
         return slots;
      });
   }

   public CompletableFuture<Void> saveContainerBulk(UUID playerId, String containerType, Map<Integer, String> slots, long revision, String checksum) {
      if (playerId == null || containerType == null) {
         return CompletableFuture.completedFuture(null);
      }
      Map<Integer, String> snapshot = slots == null ? Map.of() : Map.copyOf(slots);
      this.putCached(playerId, containerType, snapshot);
      return this.asyncDbExecutor.method_244(
         "pim.saveContainerBulk",
         connection -> {
            try (PreparedStatement upsertProfile = connection.prepareStatement(
               "INSERT INTO extraction_player_profiles (player_uuid, revision, updated_at) VALUES (?, ?, NOW()) "
                  + "ON CONFLICT (player_uuid) DO UPDATE SET revision = EXCLUDED.revision, updated_at = NOW()"
            )) {
               upsertProfile.setObject(1, playerId);
               upsertProfile.setLong(2, revision);
               upsertProfile.executeUpdate();
            }

            try (PreparedStatement delete = connection.prepareStatement(
               "DELETE FROM extraction_inventory_slots WHERE player_uuid = ? AND container_type = ?"
            )) {
               delete.setObject(1, playerId);
               delete.setString(2, containerType);
               delete.executeUpdate();
            }

            if (!snapshot.isEmpty()) {
               String insertSql = "INSERT INTO extraction_inventory_slots (player_uuid, container_type, slot_index, payload_b64, updated_at) VALUES (?, ?, ?, ?, NOW())";
               try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                  for (Map.Entry<Integer, String> entry : snapshot.entrySet()) {
                     insert.setObject(1, playerId);
                     insert.setString(2, containerType);
                     insert.setInt(3, entry.getKey());
                     insert.setString(4, entry.getValue());
                     insert.addBatch();
                  }
                  insert.executeBatch();
               }
            }

            try (PreparedStatement revisionInsert = connection.prepareStatement(
               "INSERT INTO extraction_inventory_revisions (player_uuid, revision, container_type, slot_count, checksum, saved_at) VALUES (?, ?, ?, ?, ?, NOW()) "
                  + "ON CONFLICT (player_uuid, revision, container_type) "
                  + "DO UPDATE SET slot_count = EXCLUDED.slot_count, checksum = EXCLUDED.checksum, saved_at = NOW()"
            )) {
               revisionInsert.setObject(1, playerId);
               revisionInsert.setLong(2, revision);
               revisionInsert.setString(3, containerType);
               revisionInsert.setInt(4, snapshot.size());
               revisionInsert.setString(5, checksum == null ? "" : checksum);
               revisionInsert.executeUpdate();
            }
         }
      );
   }

   private record ContainerKey(UUID playerId, String containerType) {
      private ContainerKey {
         Objects.requireNonNull(playerId, "playerId");
         Objects.requireNonNull(containerType, "containerType");
      }
   }
}
