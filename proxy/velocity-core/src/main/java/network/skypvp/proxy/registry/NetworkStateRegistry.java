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
import network.skypvp.shared.BreachDisconnectedPresenceEvent;
import network.skypvp.shared.BreachInstanceSnapshot;
import network.skypvp.shared.BreachSpectatorPresenceEvent;
import network.skypvp.shared.PlayerSessionAction;
import network.skypvp.shared.PlayerSessionEvent;
import network.skypvp.shared.PlayerSessionSnapshot;
import network.skypvp.shared.ServerHeartbeatEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

public final class NetworkStateRegistry {
   private final Map<UUID, PlayerSessionSnapshot> activeSessions = new ConcurrentHashMap<>();
   private final Map<UUID, BreachDisconnectedSnapshot> breachDisconnectedPresence = new ConcurrentHashMap<>();
   private final Map<UUID, BreachSpectatorSnapshot> breachSpectatorPresence = new ConcurrentHashMap<>();
   private final Map<String, ServerHeartbeatEvent> heartbeatsByServer = new ConcurrentHashMap<>();
   /** Epoch millis when each server first reported joinable=true in the current ready streak. */
   private final Map<String, Long> joinableSinceEpochMillis = new ConcurrentHashMap<>();
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

   public void applyBreachDisconnectedPresence(BreachDisconnectedPresenceEvent event) {
      if (event == null || event.playerId() == null) {
         return;
      }
      if (!event.present()) {
         this.breachDisconnectedPresence.remove(event.playerId());
      } else if (event.serverId() != null && !event.serverId().isBlank()) {
         this.breachDisconnectedPresence.put(
            event.playerId(),
            new BreachDisconnectedSnapshot(event.serverId(), event.instanceId(), event.occurredAtEpochMillis())
         );
      }
   }

   /** @deprecated use {@link #applyBreachDisconnectedPresence(BreachDisconnectedPresenceEvent)} */
   public void applyBreachAwayPresence(BreachDisconnectedPresenceEvent event) {
      this.applyBreachDisconnectedPresence(event);
   }

   public Optional<BreachDisconnectedSnapshot> breachDisconnectedPresence(UUID playerId) {
      return playerId == null ? Optional.empty() : Optional.ofNullable(this.breachDisconnectedPresence.get(playerId));
   }

   /** @deprecated use {@link #breachDisconnectedPresence(UUID)} */
   public Optional<BreachDisconnectedSnapshot> breachAwayPresence(UUID playerId) {
      return this.breachDisconnectedPresence(playerId);
   }

   public void applyBreachSpectatorPresence(BreachSpectatorPresenceEvent event) {
      if (event == null || event.playerId() == null) {
         return;
      }
      if (!event.present()) {
         this.breachSpectatorPresence.remove(event.playerId());
      } else if (event.serverId() != null && !event.serverId().isBlank()) {
         this.breachSpectatorPresence.put(
            event.playerId(),
            new BreachSpectatorSnapshot(event.serverId(), event.instanceId(), event.occurredAtEpochMillis())
         );
      }
   }

   public Optional<BreachSpectatorSnapshot> breachSpectatorPresence(UUID playerId) {
      return playerId == null ? Optional.empty() : Optional.ofNullable(this.breachSpectatorPresence.get(playerId));
   }

   /** Local-only purge when a backend signals (or confirms) that reconnect routing should stop for this player. */
   public void clearBreachReconnectHints(UUID playerId) {
      if (playerId == null) {
         return;
      }
      this.breachDisconnectedPresence.remove(playerId);
      this.breachSpectatorPresence.remove(playerId);
   }

   public void applyPlayerSession(PlayerSessionEvent event) {
      if (event != null && event.playerId() != null && event.action() != null) {
         if (event.action() == PlayerSessionAction.DISCONNECTED) {
            this.activeSessions.remove(event.playerId());
            this.vanishedPlayers.remove(event.playerId());
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
         if (event.joinable()) {
            this.joinableSinceEpochMillis.putIfAbsent(event.serverId(), System.currentTimeMillis());
         } else {
            this.joinableSinceEpochMillis.remove(event.serverId());
         }
      }
   }

   public void pruneStaleHeartbeats(long staleCutoffEpochMillis) {
      this.heartbeatsByServer.entrySet().removeIf(entry -> {
         boolean stale = entry.getValue().occurredAtEpochMillis() < staleCutoffEpochMillis;
         if (stale) {
            this.joinableSinceEpochMillis.remove(entry.getKey());
         }
         return stale;
      });
   }

   /** How long the server has continuously advertised joinable=true, or 0 if not currently joinable. */
   public long joinableStableMillis(String serverId) {
      if (serverId == null || serverId.isBlank()) {
         return 0L;
      }
      Long since = this.joinableSinceEpochMillis.get(serverId);
      return since == null ? 0L : Math.max(0L, System.currentTimeMillis() - since);
   }

   /**
    * Drops sessions (and vanish flags) for players no longer connected to this proxy. Session removal normally rides
    * the DISCONNECTED event through Redis; events published while the subscriber is reconnecting are lost forever
    * (pub/sub has no replay), so ghosts accumulate without this sweep. Single-proxy network: local presence is
    * authoritative. The grace period covers logins whose session event arrives before the player is fully connected.
    */
   public void pruneOfflineSessions(Set<UUID> onlinePlayerIds, long graceMillis) {
      if (onlinePlayerIds == null) {
         return;
      }
      long cutoff = System.currentTimeMillis() - Math.max(0L, graceMillis);
      this.activeSessions.entrySet().removeIf(entry -> {
         if (onlinePlayerIds.contains(entry.getKey())) {
            return false;
         }
         Instant connectedAt = entry.getValue().connectedAt();
         return connectedAt == null || connectedAt.toEpochMilli() < cutoff;
      });
      this.vanishedPlayers.removeIf(playerId -> !onlinePlayerIds.contains(playerId) && !this.activeSessions.containsKey(playerId));
   }

   /** Drops reconnect hints whose extraction pod has gone stale or whose timestamp is too old to trust. */
   public void pruneStaleBreachReconnectHints(long staleCutoffEpochMillis) {
      this.breachDisconnectedPresence.entrySet().removeIf(entry -> this.isStaleBreachHint(entry.getValue().serverId(), entry.getValue().updatedAtEpochMillis(), staleCutoffEpochMillis));
      this.breachSpectatorPresence.entrySet().removeIf(entry -> this.isStaleBreachHint(entry.getValue().serverId(), entry.getValue().updatedAtEpochMillis(), staleCutoffEpochMillis));
   }

   private boolean isStaleBreachHint(String serverId, long updatedAtEpochMillis, long staleCutoffEpochMillis) {
      if (updatedAtEpochMillis < staleCutoffEpochMillis) {
         return true;
      }
      return this.heartbeatFor(serverId).map(heartbeat -> heartbeat.occurredAtEpochMillis() < staleCutoffEpochMillis).orElse(true);
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

   public List<BreachInstanceSnapshot> breachInstancesForServer(String serverId) {
      if (serverId == null || serverId.isBlank()) {
         return List.of();
      }
      return this.heartbeatFor(serverId)
         .map(ServerHeartbeatEvent::breachInstances)
         .filter(instances -> instances != null && !instances.isEmpty())
         .map(List::copyOf)
         .orElse(List.of());
   }

   public static record BreachDisconnectedSnapshot(String serverId, String instanceId, long updatedAtEpochMillis) {
   }

   /** @deprecated use {@link BreachDisconnectedSnapshot} */
   public static record BreachAwaySnapshot(String serverId, String instanceId, long updatedAtEpochMillis) {
   }

   public static record BreachSpectatorSnapshot(String serverId, String instanceId, long updatedAtEpochMillis) {
   }
}
