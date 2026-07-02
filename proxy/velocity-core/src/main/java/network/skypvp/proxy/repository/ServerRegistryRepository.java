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
import network.skypvp.proxy.state.ServerLifecycleState;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.slf4j.Logger;

public final class ServerRegistryRepository implements AutoCloseable {
   private static final String UPSERT_HEARTBEAT = "insert into network_server_registry (\n    server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance,\n    lifecycle_state, lifecycle_updated_at, orchestrator_source, orchestration_generation\n) values (?, ?, now(), ?, ?, ?, ?, ?, now(), ?, ?)\non conflict (server_id) do update\n    set role = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.role\n            else excluded.role\n        end,\n        last_heartbeat_at = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.last_heartbeat_at\n            else excluded.last_heartbeat_at\n        end,\n        online_players = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.online_players\n            else excluded.online_players\n        end,\n        max_players = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.max_players\n            else excluded.max_players\n        end,\n        joinable = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.joinable\n            else excluded.joinable\n        end,\n        maintenance = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.maintenance\n            else excluded.maintenance\n        end,\n        orchestrator_source = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.orchestrator_source\n            else coalesce(excluded.orchestrator_source, network_server_registry.orchestrator_source)\n        end,\n        orchestration_generation = greatest(network_server_registry.orchestration_generation, excluded.orchestration_generation),\n        lifecycle_state = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.lifecycle_state\n            when not excluded.joinable and network_server_registry.lifecycle_state = 'READY'\n                then 'DRAINING'\n            when excluded.joinable then 'READY'\n            else 'BOOTING'\n        end,\n        lifecycle_updated_at = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.lifecycle_updated_at\n            else now()\n        end\n";
   private static final String MARK_OFFLINE = "update network_server_registry\nset last_heartbeat_at = now(),\n    online_players = 0,\n    joinable = false,\n    maintenance = false,\n    lifecycle_state = 'OFFLINE',\n    lifecycle_updated_at = now()\nwhere server_id = ?\n";
   private static final String UPDATE_LIFECYCLE = "update network_server_registry\nset lifecycle_state = ?,\n    desired_lifecycle_state = ?,\n    lifecycle_reason = ?,\n    orchestrator_source = ?,\n    orchestration_generation = ?,\n    lifecycle_updated_at = now()\nwhere server_id = ?\n";
   private static final String INSERT_LIFECYCLE_AUDIT = "insert into network_server_lifecycle_audit (\n    server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\n) values (?, ?, ?, ?, ?, ?, ?, now())\n";
   private static final String SELECT_ALL = "select server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance\n      , lifecycle_state, desired_lifecycle_state, lifecycle_reason, orchestrator_source\n      , orchestration_generation, lifecycle_updated_at\nfrom network_server_registry\norder by server_id asc\n";
   private static final String SELECT_ONE = "select server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance\n      , lifecycle_state, desired_lifecycle_state, lifecycle_reason, orchestrator_source\n      , orchestration_generation, lifecycle_updated_at\nfrom network_server_registry\nwhere server_id = ?\nlimit 1\n";
   private static final String SELECT_AUDIT_RECENT = "select audit_id, server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\nfrom network_server_lifecycle_audit\norder by changed_at desc\nlimit ?\n";
   private static final String SELECT_AUDIT_FOR_SERVER = "select audit_id, server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\nfrom network_server_lifecycle_audit\nwhere server_id = ?\norder by changed_at desc\nlimit ?\n";
   private final DatabaseManager dataSource;
   private final Logger logger;

   public ServerRegistryRepository(DatabaseManager dataSource, Logger logger) {
      this.dataSource = dataSource;
      this.logger = logger;
   }

   public boolean upsertHeartbeat(ServerHeartbeatEvent event) {
      if (event != null && event.serverId() != null && !event.serverId().isBlank()) {
         try {
            boolean var4;
            try (
               Connection c = this.dataSource.getConnection();
               PreparedStatement s = c.prepareStatement(
                  "insert into network_server_registry (\n    server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance,\n    lifecycle_state, lifecycle_updated_at, orchestrator_source, orchestration_generation\n) values (?, ?, now(), ?, ?, ?, ?, ?, now(), ?, ?)\non conflict (server_id) do update\n    set role = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.role\n            else excluded.role\n        end,\n        last_heartbeat_at = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.last_heartbeat_at\n            else excluded.last_heartbeat_at\n        end,\n        online_players = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.online_players\n            else excluded.online_players\n        end,\n        max_players = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.max_players\n            else excluded.max_players\n        end,\n        joinable = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.joinable\n            else excluded.joinable\n        end,\n        maintenance = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.maintenance\n            else excluded.maintenance\n        end,\n        orchestrator_source = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.orchestrator_source\n            else coalesce(excluded.orchestrator_source, network_server_registry.orchestrator_source)\n        end,\n        orchestration_generation = greatest(network_server_registry.orchestration_generation, excluded.orchestration_generation),\n        lifecycle_state = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.lifecycle_state\n            when not excluded.joinable and network_server_registry.lifecycle_state = 'READY'\n                then 'DRAINING'\n            when excluded.joinable then 'READY'\n            else 'BOOTING'\n        end,\n        lifecycle_updated_at = case\n            when excluded.orchestration_generation < network_server_registry.orchestration_generation\n                then network_server_registry.lifecycle_updated_at\n            else now()\n        end\n"
               );
            ) {
               s.setString(1, event.serverId());
               s.setString(2, event.role() == null ? "UNKNOWN" : event.role().name());
               s.setInt(3, event.onlinePlayers());
               s.setInt(4, event.maxPlayers());
               s.setBoolean(5, event.joinable());
               s.setBoolean(6, false);
               s.setString(7, event.joinable() ? ServerLifecycleState.READY.name() : ServerLifecycleState.BOOTING.name());
               s.setString(8, event.orchestratorSource());
               s.setLong(9, Math.max(0L, event.orchestrationGeneration()));
               var4 = s.executeUpdate() > 0;
            }

            return var4;
         } catch (SQLException var10) {
            this.logger.warn("ServerRegistryRepository.upsertHeartbeat: {}", var10.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public boolean markOffline(String serverId) {
      if (serverId != null && !serverId.isBlank()) {
         try {
            boolean var4;
            try (
               Connection c = this.dataSource.getConnection();
               PreparedStatement s = c.prepareStatement(
                  "update network_server_registry\nset last_heartbeat_at = now(),\n    online_players = 0,\n    joinable = false,\n    maintenance = false,\n    lifecycle_state = 'OFFLINE',\n    lifecycle_updated_at = now()\nwhere server_id = ?\n"
               );
            ) {
               s.setString(1, serverId);
               var4 = s.executeUpdate() > 0;
            }

            return var4;
         } catch (SQLException var10) {
            this.logger.warn("ServerRegistryRepository.markOffline: {}", var10.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public boolean deleteServer(String serverId) {
      if (serverId != null && !serverId.isBlank()) {
         try (Connection c = this.dataSource.getConnection();
              PreparedStatement s = c.prepareStatement("delete from network_server_registry where server_id = ?")) {
            s.setString(1, serverId);
            return s.executeUpdate() > 0;
         } catch (SQLException var10) {
            this.logger.warn("ServerRegistryRepository.deleteServer: {}", var10.getMessage());
            return false;
         }
      }
      return false;
   }

   public List<ServerRegistryRepository.ServerRegistrySnapshot> snapshotAll() {
      List<ServerRegistryRepository.ServerRegistrySnapshot> rows = new ArrayList<>();

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement s = c.prepareStatement(
            "select server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance\n      , lifecycle_state, desired_lifecycle_state, lifecycle_reason, orchestrator_source\n      , orchestration_generation, lifecycle_updated_at\nfrom network_server_registry\norder by server_id asc\n"
         );
         ResultSet rs = s.executeQuery();
      ) {
         while (rs.next()) {
            rows.add(this.mapRow(rs));
         }
      } catch (SQLException var13) {
         this.logger.warn("ServerRegistryRepository.snapshotAll: {}", var13.getMessage());
      }

      return rows;
   }

   public boolean updateLifecycle(
      String serverId, ServerLifecycleState newState, ServerLifecycleState desiredState, String reason, String source, long generation
   ) {
      if (serverId != null && !serverId.isBlank() && newState != null) {
         ServerLifecycleState previous = this.snapshotFor(serverId)
            .map(ServerRegistryRepository.ServerRegistrySnapshot::lifecycleState)
            .orElse(ServerLifecycleState.OFFLINE);

         try {
            boolean var13;
            try (Connection c = this.dataSource.getConnection()) {
               c.setAutoCommit(false);

               try (
                  PreparedStatement update = c.prepareStatement(
                     "update network_server_registry\nset lifecycle_state = ?,\n    desired_lifecycle_state = ?,\n    lifecycle_reason = ?,\n    orchestrator_source = ?,\n    orchestration_generation = ?,\n    lifecycle_updated_at = now()\nwhere server_id = ?\n"
                  );
                  PreparedStatement audit = c.prepareStatement(
                     "insert into network_server_lifecycle_audit (\n    server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\n) values (?, ?, ?, ?, ?, ?, ?, now())\n"
                  );
               ) {
                  update.setString(1, newState.name());
                  update.setString(2, desiredState == null ? null : desiredState.name());
                  update.setString(3, reason);
                  update.setString(4, source);
                  update.setLong(5, Math.max(0L, generation));
                  update.setString(6, serverId);
                  int updated = update.executeUpdate();
                  if (updated > 0) {
                     audit.setString(1, serverId);
                     audit.setString(2, previous.name());
                     audit.setString(3, newState.name());
                     audit.setString(4, desiredState == null ? null : desiredState.name());
                     audit.setString(5, reason);
                     audit.setString(6, source);
                     audit.setLong(7, Math.max(0L, generation));
                     audit.executeUpdate();
                  }

                  c.commit();
                  var13 = updated > 0;
               } catch (SQLException var29) {
                  c.rollback();
                  throw var29;
               } finally {
                  c.setAutoCommit(true);
               }
            }

            return var13;
         } catch (SQLException var32) {
            this.logger.warn("ServerRegistryRepository.updateLifecycle: {}", var32.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public Optional<ServerRegistryRepository.ServerRegistrySnapshot> snapshotFor(String serverId) {
      if (serverId != null && !serverId.isBlank()) {
         try (
            Connection c = this.dataSource.getConnection();
            PreparedStatement s = c.prepareStatement(
               "select server_id, role, last_heartbeat_at, online_players, max_players, joinable, maintenance\n      , lifecycle_state, desired_lifecycle_state, lifecycle_reason, orchestrator_source\n      , orchestration_generation, lifecycle_updated_at\nfrom network_server_registry\nwhere server_id = ?\nlimit 1\n"
            );
         ) {
            s.setString(1, serverId);

            try (ResultSet rs = s.executeQuery()) {
               return rs.next() ? Optional.of(this.mapRow(rs)) : Optional.empty();
            }
         } catch (SQLException var13) {
            this.logger.warn("ServerRegistryRepository.snapshotFor: {}", var13.getMessage());
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   public List<ServerRegistryRepository.ServerLifecycleAuditEntry> recentLifecycleAudit(int limit) {
      List<ServerRegistryRepository.ServerLifecycleAuditEntry> out = new ArrayList<>();
      int safeLimit = Math.max(1, Math.min(200, limit));

      try (
         Connection c = this.dataSource.getConnection();
         PreparedStatement s = c.prepareStatement(
            "select audit_id, server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\nfrom network_server_lifecycle_audit\norder by changed_at desc\nlimit ?\n"
         );
      ) {
         s.setInt(1, safeLimit);

         try (ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
               out.add(this.mapAuditRow(rs));
            }
         }
      } catch (SQLException var15) {
         this.logger.warn("ServerRegistryRepository.recentLifecycleAudit: {}", var15.getMessage());
      }

      return out;
   }

   public List<ServerRegistryRepository.ServerLifecycleAuditEntry> recentLifecycleAuditForServer(String serverId, int limit) {
      if (serverId != null && !serverId.isBlank()) {
         List<ServerRegistryRepository.ServerLifecycleAuditEntry> out = new ArrayList<>();
         int safeLimit = Math.max(1, Math.min(200, limit));

         try (
            Connection c = this.dataSource.getConnection();
            PreparedStatement s = c.prepareStatement(
               "select audit_id, server_id, previous_state, new_state, desired_state, reason, source, generation, changed_at\nfrom network_server_lifecycle_audit\nwhere server_id = ?\norder by changed_at desc\nlimit ?\n"
            );
         ) {
            s.setString(1, serverId);
            s.setInt(2, safeLimit);

            try (ResultSet rs = s.executeQuery()) {
               while (rs.next()) {
                  out.add(this.mapAuditRow(rs));
               }
            }
         } catch (SQLException var16) {
            this.logger.warn("ServerRegistryRepository.recentLifecycleAuditForServer: {}", var16.getMessage());
         }

         return out;
      } else {
         return List.of();
      }
   }

   private ServerRegistryRepository.ServerRegistrySnapshot mapRow(ResultSet rs) throws SQLException {
      return new ServerRegistryRepository.ServerRegistrySnapshot(
         rs.getString("server_id"),
         rs.getString("role"),
         rs.getTimestamp("last_heartbeat_at").toInstant(),
         rs.getInt("online_players"),
         rs.getInt("max_players"),
         rs.getBoolean("joinable"),
         rs.getBoolean("maintenance"),
         ServerLifecycleState.fromString(rs.getString("lifecycle_state")),
         ServerLifecycleState.fromString(rs.getString("desired_lifecycle_state")),
         rs.getString("lifecycle_reason"),
         rs.getString("orchestrator_source"),
         rs.getLong("orchestration_generation"),
         rs.getTimestamp("lifecycle_updated_at").toInstant()
      );
   }

   private ServerRegistryRepository.ServerLifecycleAuditEntry mapAuditRow(ResultSet rs) throws SQLException {
      return new ServerRegistryRepository.ServerLifecycleAuditEntry(
         rs.getLong("audit_id"),
         rs.getString("server_id"),
         rs.getString("previous_state"),
         rs.getString("new_state"),
         rs.getString("desired_state"),
         rs.getString("reason"),
         rs.getString("source"),
         rs.getLong("generation"),
         rs.getTimestamp("changed_at").toInstant()
      );
   }

   @Override
   public void close() {
   }

   public static record ServerLifecycleAuditEntry(
      long auditId,
      String serverId,
      String previousState,
      String newState,
      String desiredState,
      String reason,
      String source,
      long generation,
      Instant changedAt
   ) {
   }

   public static record ServerRegistrySnapshot(
      String serverId,
      String role,
      Instant lastHeartbeatAt,
      int onlinePlayers,
      int maxPlayers,
      boolean joinable,
      boolean maintenance,
      ServerLifecycleState lifecycleState,
      ServerLifecycleState desiredLifecycleState,
      String lifecycleReason,
      String orchestratorSource,
      long orchestrationGeneration,
      Instant lifecycleUpdatedAt
   ) {
   }
}
