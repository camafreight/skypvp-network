package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.Bukkit;

public final class NetworkServerDirectoryRepository {
   private static final long STALE_HEARTBEAT_MILLIS = 45000L;
   private static final long CACHE_REFRESH_MILLIS = 5000L;
   private static final long ASYNC_WAIT_TIMEOUT_MILLIS = 2500L;
   private static final String SELECT_BY_ROLE = "select server_id, role, online_players, max_players, joinable, maintenance, last_heartbeat_at\nfrom network_server_registry\nwhere upper(role) = ?\norder by joinable desc, online_players asc, server_id asc\n";
   private final DatabaseManager databaseManager;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private final Map<NetworkServerRole, CachedRoleServers> cache = new ConcurrentHashMap<>();
   private final Map<NetworkServerRole, CompletableFuture<List<NetworkServerDirectoryRepository.NetworkServerSnapshot>>> refreshes = new ConcurrentHashMap<>();

   public NetworkServerDirectoryRepository(DatabaseManager databaseManager, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.databaseManager = databaseManager;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get(ASYNC_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      } catch (Exception var4) {
         this.logger.warning("[ServerDirectory] " + label + " failed: " + var4.getMessage());
         return null;
      }
   }

   public List<NetworkServerDirectoryRepository.NetworkServerSnapshot> listJoinableByRole(NetworkServerRole role) {
      return this.listByRole(role).stream().filter(NetworkServerDirectoryRepository.NetworkServerSnapshot::isRoutable).toList();
   }

   public List<NetworkServerDirectoryRepository.NetworkServerSnapshot> listByRole(NetworkServerRole role) {
      if (this.databaseManager == null || role == null) {
         return List.of();
      }

      if (Bukkit.isPrimaryThread()) {
         this.refreshRole(role);
         CachedRoleServers cached = this.cache.get(role);
         return cached == null ? List.of() : cached.snapshots();
      }

      List<NetworkServerDirectoryRepository.NetworkServerSnapshot> rows = this.executeAsync("serverdir.listByRole", connection -> this.queryByRole(connection, role));
      if (rows == null) {
         CachedRoleServers cached = this.cache.get(role);
         return cached == null ? List.of() : cached.snapshots();
      }
      this.cache.put(role, new CachedRoleServers(rows, System.currentTimeMillis()));
      return rows;
   }

   private void refreshRole(NetworkServerRole role) {
      CachedRoleServers cached = this.cache.get(role);
      long now = System.currentTimeMillis();
      if (cached != null && now - cached.refreshedAtMillis() < CACHE_REFRESH_MILLIS) {
         return;
      }

      this.refreshes.computeIfAbsent(role, currentRole -> {
         CompletableFuture<List<NetworkServerDirectoryRepository.NetworkServerSnapshot>> future = this.asyncDbExecutor.supply(
            "serverdir.refresh." + currentRole.name(),
            connection -> this.queryByRole(connection, currentRole)
         );
         future.whenComplete((rows, throwable) -> {
            this.refreshes.remove(currentRole);
            if (throwable != null) {
               this.logger.warning("[ServerDirectory] refresh for " + currentRole.name() + " failed: " + throwable.getMessage());
               return;
            }
            this.cache.put(currentRole, new CachedRoleServers(rows == null ? List.of() : List.copyOf(rows), System.currentTimeMillis()));
         });
         return future;
      });
   }

   private List<NetworkServerDirectoryRepository.NetworkServerSnapshot> queryByRole(java.sql.Connection connection, NetworkServerRole role) throws SQLException {
      List<NetworkServerDirectoryRepository.NetworkServerSnapshot> rows = new ArrayList<>();

      try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_ROLE)) {
         statement.setString(1, role.name());

         try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
               rows.add(this.mapRow(rs));
            }
         }
      }

      return rows;
   }

   public NetworkServerDirectoryRepository.RoleSummary summarizeRole(NetworkServerRole role) {
      List<NetworkServerDirectoryRepository.NetworkServerSnapshot> snapshots = this.listJoinableByRole(role);
      int liveServers = snapshots.size();
      int totalPlayers = snapshots.stream().mapToInt(NetworkServerDirectoryRepository.NetworkServerSnapshot::onlinePlayers).sum();
      int totalCapacity = snapshots.stream().mapToInt(NetworkServerDirectoryRepository.NetworkServerSnapshot::maxPlayers).sum();
      return new NetworkServerDirectoryRepository.RoleSummary(liveServers, totalPlayers, totalCapacity);
   }

   private NetworkServerDirectoryRepository.NetworkServerSnapshot mapRow(ResultSet rs) throws SQLException {
      Timestamp heartbeatAt = rs.getTimestamp("last_heartbeat_at");
      Instant lastHeartbeatAt = heartbeatAt == null ? null : heartbeatAt.toInstant();
      return new NetworkServerDirectoryRepository.NetworkServerSnapshot(
         rs.getString("server_id"),
         rs.getString("role"),
         rs.getInt("online_players"),
         rs.getInt("max_players"),
         rs.getBoolean("joinable"),
         rs.getBoolean("maintenance"),
         lastHeartbeatAt
      );
   }

   public static record NetworkServerSnapshot(
      String serverId, String role, int onlinePlayers, int maxPlayers, boolean joinable, boolean maintenance, Instant lastHeartbeatAt
   ) {
      public boolean isRoutable() {
         return this.joinable && !this.maintenance && this.lastHeartbeatAt != null ? this.lastHeartbeatAt.isAfter(Instant.now().minusMillis(45000L)) : false;
      }
   }

   public static record RoleSummary(int liveServers, int totalPlayers, int totalCapacity) {
      public static NetworkServerDirectoryRepository.RoleSummary empty() {
         return new NetworkServerDirectoryRepository.RoleSummary(0, 0, 0);
      }
   }

   private static record CachedRoleServers(List<NetworkServerDirectoryRepository.NetworkServerSnapshot> snapshots, long refreshedAtMillis) {
   }
}
