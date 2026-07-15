package network.skypvp.paper.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.shared.BreachInstanceSnapshot;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;

public final class NetworkHeartbeatCache {
   private static final long STALE_MILLIS = 45000L;

   private final Map<String, ServerHeartbeatEvent> heartbeatsByServer = new ConcurrentHashMap<>();

   public void apply(ServerHeartbeatEvent event) {
      if (event == null || event.serverId() == null || event.serverId().isBlank()) {
         return;
      }
      this.heartbeatsByServer.put(event.serverId(), event);
   }

   public Optional<ServerHeartbeatEvent> heartbeatFor(String serverId) {
      if (serverId == null || serverId.isBlank()) {
         return Optional.empty();
      }
      ServerHeartbeatEvent event = this.heartbeatsByServer.get(serverId);
      if (event == null || isStale(event)) {
         return Optional.empty();
      }
      return Optional.of(event);
   }

   public List<ServerHeartbeatEvent> liveHeartbeats() {
      return this.heartbeatsByServer.values().stream()
         .filter(event -> !isStale(event))
         .sorted(Comparator.comparing(ServerHeartbeatEvent::serverId))
         .toList();
   }

   public List<ServerHeartbeatEvent> liveHeartbeatsForRole(NetworkServerRole role) {
      return this.liveHeartbeats().stream()
         .filter(event -> event.role() == role)
         .toList();
   }

   public int totalPlayersForRole(NetworkServerRole role) {
      return this.liveHeartbeatsForRole(role).stream().mapToInt(ServerHeartbeatEvent::onlinePlayers).sum();
   }

   public int liveServerCountForRole(NetworkServerRole role) {
      return this.liveHeartbeatsForRole(role).size();
   }

   public int totalCapacityForRole(NetworkServerRole role) {
      return this.liveHeartbeatsForRole(role).stream().mapToInt(ServerHeartbeatEvent::maxPlayers).sum();
   }

   public int totalOpenBreachSlots() {
      return this.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION).stream().mapToInt(ServerHeartbeatEvent::openBreachSlots).sum();
   }

   public int totalActiveBreaches() {
      return this.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION).stream().mapToInt(ServerHeartbeatEvent::activeBreaches).sum();
   }

   public int totalQueuedPlayers() {
      return this.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION).stream().mapToInt(ServerHeartbeatEvent::queuedPlayers).sum();
   }

   public int totalRaidParticipants() {
      int total = 0;
      for (AggregatedBreachInstance instance : this.aggregatedBreachInstances()) {
         total += instance.occupiedPlayers();
      }
      return total;
   }

   public List<AggregatedBreachInstance> aggregatedBreachInstances() {
      List<AggregatedBreachInstance> instances = new ArrayList<>();
      for (ServerHeartbeatEvent heartbeat : this.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION)) {
         if (heartbeat.breachInstances() == null) {
            continue;
         }
         for (BreachInstanceSnapshot snapshot : heartbeat.breachInstances()) {
            if (snapshot == null || !snapshot.joinable()) {
               continue;
            }
            instances.add(new AggregatedBreachInstance(heartbeat.serverId(), snapshot));
         }
      }
      instances.sort(Comparator
         .comparing((AggregatedBreachInstance instance) -> instance.snapshot().mapId(), String.CASE_INSENSITIVE_ORDER)
         .thenComparing(instance -> instance.snapshot().instanceId(), String.CASE_INSENSITIVE_ORDER));
      return List.copyOf(instances);
   }

   public Optional<AggregatedBreachInstance> breachInstanceAt(int oneBasedIndex) {
      if (oneBasedIndex < 1) {
         return Optional.empty();
      }
      List<AggregatedBreachInstance> instances = this.aggregatedBreachInstances();
      if (oneBasedIndex > instances.size()) {
         return Optional.empty();
      }
      return Optional.of(instances.get(oneBasedIndex - 1));
   }

   public Optional<ServerHeartbeatEvent> lobbyServerAt(int oneBasedIndex) {
      if (oneBasedIndex < 1) {
         return Optional.empty();
      }
      List<ServerHeartbeatEvent> servers = this.liveHeartbeatsForRole(NetworkServerRole.LOBBY);
      if (oneBasedIndex > servers.size()) {
         return Optional.empty();
      }
      return Optional.of(servers.get(oneBasedIndex - 1));
   }

   public Optional<ServerHeartbeatEvent> extractionServerAt(int oneBasedIndex) {
      if (oneBasedIndex < 1) {
         return Optional.empty();
      }
      List<ServerHeartbeatEvent> servers = this.liveHeartbeatsForRole(NetworkServerRole.EXTRACTION);
      if (oneBasedIndex > servers.size()) {
         return Optional.empty();
      }
      return Optional.of(servers.get(oneBasedIndex - 1));
   }

   public String activeMapList() {
      return this.aggregatedBreachInstances().stream()
         .map(instance -> formatMapDisplay(instance.snapshot().mapId()))
         .distinct()
         .reduce((left, right) -> left + ", " + right)
         .orElse("None");
   }

   public static String formatMapDisplay(String mapId) {
      if (mapId == null || mapId.isBlank()) {
         return "Unknown";
      }
      String normalized = mapId.replace('_', ' ').replace('-', ' ').trim();
      if (normalized.isBlank()) {
         return mapId;
      }
      StringBuilder out = new StringBuilder();
      for (String part : normalized.split("\\s+")) {
         if (part.isBlank()) {
            continue;
         }
         if (!out.isEmpty()) {
            out.append(' ');
         }
         out.append(Character.toUpperCase(part.charAt(0)));
         if (part.length() > 1) {
            out.append(part.substring(1).toLowerCase(Locale.ROOT));
         }
      }
      return out.isEmpty() ? mapId : out.toString();
   }

   private static boolean isStale(ServerHeartbeatEvent event) {
      long age = System.currentTimeMillis() - event.occurredAtEpochMillis();
      return age > STALE_MILLIS;
   }

   public record AggregatedBreachInstance(String serverId, BreachInstanceSnapshot snapshot) {
      public int occupiedPlayers() {
         return Math.max(0, this.snapshot.maxPlayers() - this.snapshot.openSlots());
      }
   }
}
