package network.skypvp.lobby.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import network.skypvp.paper.service.VanishService;
import org.bukkit.entity.Player;

public final class LobbyRuntimeStateRegistry {
   private final AtomicReference<LobbyFlowState> gameState = new AtomicReference<>(LobbyFlowState.OPEN);
   private final Map<UUID, LobbyAudienceState> playerStates = new ConcurrentHashMap<>();
   private final Map<UUID, String> queuedTargets = new ConcurrentHashMap<>();
   private final Map<UUID, Long> queuedUntilEpochMs = new ConcurrentHashMap<>();

   public LobbyRuntimeStateRegistry() {
   }

   public LobbyFlowState gameState() {
      return this.gameState.get();
   }

   public void setGameState(LobbyFlowState state) {
      if (state != null) {
         this.gameState.set(state);
      }
   }

   public LobbyAudienceState playerState(UUID playerId) {
      return this.playerStates.getOrDefault(playerId, LobbyAudienceState.NORMAL);
   }

   public LobbyAudienceState refreshPlayerState(Player player) {
      UUID id = player.getUniqueId();
      LobbyAudienceState resolved;
      if (VanishService.VANISHED.contains(id)) {
         resolved = LobbyAudienceState.VANISHED_STAFF;
      } else {
         LobbyAudienceState current = this.playerStates.getOrDefault(id, LobbyAudienceState.NORMAL);
         if (current == LobbyAudienceState.QUEUED && this.isQueuedExpired(id)) {
            this.clearQueued(id);
            resolved = LobbyAudienceState.NORMAL;
         } else if (current != LobbyAudienceState.QUEUED && current != LobbyAudienceState.PARKOUR_RUNNING) {
            resolved = LobbyAudienceState.NORMAL;
         } else {
            resolved = current;
         }
      }

      this.playerStates.put(id, resolved);
      return resolved;
   }

   public void setQueued(UUID playerId, boolean queued) {
      if (queued) {
         this.setQueued(playerId, null, 15000L);
      } else {
         this.clearQueued(playerId);
         this.playerStates.put(playerId, LobbyAudienceState.NORMAL);
      }
   }

   public void setQueued(UUID playerId, String targetServer, long ttlMillis) {
      this.playerStates.put(playerId, LobbyAudienceState.QUEUED);
      if (targetServer != null && !targetServer.isBlank()) {
         this.queuedTargets.put(playerId, targetServer);
      } else {
         this.queuedTargets.remove(playerId);
      }

      long ttl = Math.max(2000L, ttlMillis);
      this.queuedUntilEpochMs.put(playerId, System.currentTimeMillis() + ttl);
   }

   public String queuedTarget(UUID playerId) {
      return this.queuedTargets.getOrDefault(playerId, "-");
   }

   public List<UUID> clearExpiredQueued() {
      long now = System.currentTimeMillis();
      List<UUID> expired = new ArrayList<>();

      for (Entry<UUID, Long> e : this.queuedUntilEpochMs.entrySet()) {
         if (e.getValue() <= now && this.playerStates.getOrDefault(e.getKey(), LobbyAudienceState.NORMAL) == LobbyAudienceState.QUEUED) {
            expired.add(e.getKey());
         }
      }

      for (UUID id : expired) {
         this.clearQueued(id);
         this.playerStates.put(id, LobbyAudienceState.NORMAL);
      }

      return expired;
   }

   private boolean isQueuedExpired(UUID playerId) {
      Long until = this.queuedUntilEpochMs.get(playerId);
      return until != null && until <= System.currentTimeMillis();
   }

   private void clearQueued(UUID playerId) {
      this.queuedTargets.remove(playerId);
      this.queuedUntilEpochMs.remove(playerId);
   }

   public void setParkourRunning(UUID playerId, boolean running) {
      if (running) {
         this.playerStates.put(playerId, LobbyAudienceState.PARKOUR_RUNNING);
      } else if (this.playerStates.getOrDefault(playerId, LobbyAudienceState.NORMAL) != LobbyAudienceState.QUEUED) {
         this.playerStates.put(playerId, LobbyAudienceState.NORMAL);
      }
   }

   public void removePlayer(UUID playerId) {
      this.playerStates.remove(playerId);
      this.clearQueued(playerId);
   }
}
