package network.skypvp.proxy.registry;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.shared.PlayerSessionAction;
import network.skypvp.shared.PlayerSessionEvent;
import network.skypvp.shared.PlayerSessionSnapshot;
import network.skypvp.shared.ServerHeartbeatEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

public final class NetworkStateRegistry {
   private final Map<UUID, PlayerSessionSnapshot> activeSessions = new ConcurrentHashMap<>();
   private final Map<String, ServerHeartbeatEvent> heartbeatsByServer = new ConcurrentHashMap<>();
   private final Set<UUID> vanishedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private network.skypvp.proxy.config.ProxyBootstrapConfig config;

   public NetworkStateRegistry() {
   }

   public void setConfig(network.skypvp.proxy.config.ProxyBootstrapConfig config) {
      this.config = config;
   }

   public network.skypvp.proxy.config.ProxyBootstrapConfig config() {
      return this.config;
   }

   public boolean isVanished(UUID playerId) {
      return this.vanishedPlayers.contains(playerId);
   }

   public void setVanished(UUID playerId, boolean vanished) {
      if (vanished) {
         this.vanishedPlayers.add(playerId);
      } else {
         this.vanishedPlayers.remove(playerId);
      }
   }

   public void applyPlayerSession(PlayerSessionEvent event) {
      if (event != null && event.playerId() != null && event.action() != null) {
         if (event.action() == PlayerSessionAction.DISCONNECTED) {
            this.activeSessions.remove(event.playerId());
         } else {
            PlayerSessionSnapshot existing = this.activeSessions.get(event.playerId());
            Instant connectedAt = existing == null ? Instant.ofEpochMilli(event.occurredAtEpochMillis()) : existing.connectedAt();
            String currentServer = event.action() == PlayerSessionAction.SERVER_CONNECTED
               ? event.serverId()
               : (existing == null ? null : existing.currentServerId());
            this.activeSessions.put(event.playerId(), new PlayerSessionSnapshot(event.playerId(), event.username(), currentServer, connectedAt));
         }
      }
   }

   public void applyHeartbeat(ServerHeartbeatEvent event) {
      if (event != null && event.serverId() != null && !event.serverId().isBlank()) {
         this.heartbeatsByServer
            .compute(
               event.serverId(),
               (serverId, existing) -> {
                  if (existing == null) {
                     return event;
                  } else if (event.orchestrationGeneration() < existing.orchestrationGeneration()) {
                     return (ServerHeartbeatEvent)existing;
                  } else {
                     return (ServerHeartbeatEvent)(event.orchestrationGeneration() == existing.orchestrationGeneration()
                           && event.occurredAtEpochMillis() < existing.occurredAtEpochMillis()
                        ? existing
                        : event);
                  }
               }
            );
      }
   }

   public void pruneStaleHeartbeats(long staleCutoffEpochMillis) {
      this.heartbeatsByServer.entrySet().removeIf(entry -> entry.getValue().occurredAtEpochMillis() < staleCutoffEpochMillis);
   }

   public Optional<ServerHeartbeatEvent> heartbeatFor(String serverId) {
      return serverId != null && !serverId.isBlank() ? Optional.ofNullable(this.heartbeatsByServer.get(serverId)) : Optional.empty();
   }

   public List<ServerHeartbeatEvent> knownHeartbeats() {
      return List.copyOf(this.heartbeatsByServer.values());
   }

   public String getPlayerRankKey(UUID playerId) {
      try {
         LuckPerms api = LuckPermsProvider.get();
         User user = api.getUserManager().getUser(playerId);
         if (user != null) {
            String primaryGroup = user.getPrimaryGroup();
            if (primaryGroup != null) {
               return primaryGroup;
            }
         }
      } catch (Exception ignored) {
      }
      return "default";
   }

   public int activeSessionCount() {
      return this.activeSessions.size();
   }

   public int trackedServerCount() {
      return this.heartbeatsByServer.size();
   }

   public int totalHeartbeatOnlinePlayers() {
      return this.heartbeatsByServer.values().stream().mapToInt(ServerHeartbeatEvent::onlinePlayers).sum();
   }

   public List<ServerHeartbeatEvent> topLoadedServers(int maxItems) {
      return this.knownHeartbeats()
         .stream()
         .sorted(Comparator.comparingInt(ServerHeartbeatEvent::onlinePlayers).reversed())
         .limit((long)Math.max(0, maxItems))
         .toList();
   }
}
