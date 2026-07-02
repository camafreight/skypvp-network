package network.skypvp.paper.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.paper.repository.PlayerSessionRepository;

public final class PlayerSessionService {
   private final PlayerSessionRepository repository;
   private final Logger logger;
   private final Map<UUID, Long> activeSessionIds = new ConcurrentHashMap<>();

   public PlayerSessionService(PlayerSessionRepository repository, Logger logger) {
      this.repository = repository;
      this.logger = logger;
   }

   public void onPlayerJoin(UUID playerId, String username, String serverId) {
      this.repository.upsertPlayer(playerId, username);
      long sessionId = this.repository.recordSessionStart(playerId, serverId);
      if (sessionId >= 0L) {
         this.activeSessionIds.put(playerId, sessionId);
      }

      this.logger.fine("Session opened for " + username + " (" + playerId + ") on " + serverId + " [session=" + sessionId + "]");
   }

   public void onPlayerQuit(UUID playerId) {
      this.repository.updateLastSeen(playerId);
      Long sessionId = this.activeSessionIds.remove(playerId);
      if (sessionId != null) {
         this.repository.recordSessionEnd(sessionId);
      }
   }

   public int activeSessionCount() {
      return this.activeSessionIds.size();
   }

   public Optional<Instant> firstSeen(UUID playerId) {
      return playerId == null ? Optional.empty() : this.repository.findFirstSeen(playerId);
   }
}
