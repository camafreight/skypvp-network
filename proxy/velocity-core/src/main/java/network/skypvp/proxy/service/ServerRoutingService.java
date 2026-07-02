package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.repository.ServerRegistryRepository;
import network.skypvp.proxy.state.ServerLifecycleState;
import network.skypvp.shared.ServerHeartbeatEvent;

public final class ServerRoutingService {
   private static final long STALE_HEARTBEAT_MILLIS = 30000L;
   private final ProxyServer proxyServer;
   private final NetworkStateRegistry stateRegistry;
   private final ProxyBootstrapConfig config;
   private final ServerRegistryRepository serverRegistryRepository;
   private final Map<String, ProxyBootstrapConfig.TrackedBackendServer> configuredServers;
   private final Set<String> drainingServers = ConcurrentHashMap.newKeySet();

   public ServerRoutingService(
      ProxyServer proxyServer, NetworkStateRegistry stateRegistry, ProxyBootstrapConfig config, ServerRegistryRepository serverRegistryRepository
   ) {
      this.proxyServer = proxyServer;
      this.stateRegistry = stateRegistry;
      this.config = config;
      this.serverRegistryRepository = serverRegistryRepository;
      this.configuredServers = config.backendServers
         .stream()
         .collect(Collectors.toMap(server -> server.serverId, server -> (ProxyBootstrapConfig.TrackedBackendServer)server, (left, right) -> left));
   }

   public void markServerAsDraining(String serverId) {
      if (serverId != null) {
         this.drainingServers.add(serverId);
      }
   }

   public void unmarkServerAsDraining(String serverId) {
      if (serverId != null) {
         this.drainingServers.remove(serverId);
      }
   }

   public boolean isServerDraining(String serverId) {
      return serverId != null && this.drainingServers.contains(serverId);
   }

   public Optional<RegisteredServer> selectBestInitialServer() {
      Optional<RegisteredServer> dynamic = this.bestCandidate(selectBestInitialServerId(this.snapshotStatuses()));
      return dynamic.isPresent() ? dynamic : this.staticFallback(null);
   }

   public Optional<RegisteredServer> selectBestEntryServer() {
      Optional<RegisteredServer> strictInitial = this.bestCandidate(selectBestInitialServerId(this.snapshotStatuses()));
      if (strictInitial.isPresent()) {
         return strictInitial;
      } else {
         Optional<RegisteredServer> fallback = this.staticFallback(null);
         return fallback.isPresent()
            ? fallback
            : this.bestCandidate(bestCandidateId(this.snapshotStatuses(), ServerRoutingService.ServerRouteStatus::isHealthyJoinTarget));
      }
   }

   public Optional<RegisteredServer> selectBestLoginServer() {
      return this.bestCandidate(selectBestInitialServerId(this.snapshotStatuses()));
   }

   public Optional<RegisteredServer> selectFallback(String excludedServerId) {
      Optional<RegisteredServer> dynamic = this.bestCandidate(selectBestFallbackServerId(this.snapshotStatuses(), excludedServerId));
      return dynamic.isPresent() ? dynamic : this.staticFallback(excludedServerId);
   }

   public Optional<RegisteredServer> selectBestTargetForQueue(String queueKey, Set<String> excludedServerIds) {
      if (this.isInitialQueueKey(queueKey)) {
         return this.selectBestEntryServer();
      } else {
         return this.normalizeQueueKey(queueKey).equals(this.loginQueueKey())
            ? this.selectBestLoginServer()
            : this.bestCandidate(selectBestTargetForQueueId(this.snapshotStatuses(), this.normalizeQueueKey(queueKey), excludedServerIds));
      }
   }

   public String queueKeyForServer(String serverId) {
      if (serverId != null && !serverId.isBlank()) {
         ProxyBootstrapConfig.TrackedBackendServer tracked = this.configuredServers.get(serverId);
         if (tracked != null) {
            if (tracked.cluster != null && !tracked.cluster.isBlank()) {
               return this.normalizeQueueKey(tracked.cluster);
            }

            if (tracked.role != null && !tracked.role.isBlank()) {
               return this.normalizeQueueKey(tracked.role);
            }

            if (tracked.serverId != null && !tracked.serverId.isBlank()) {
               return this.normalizeQueueKey(tracked.serverId);
            }
         }

         Optional<ServerRoutingService.ServerRouteStatus> dynamic = this.describeServer(serverId);
         if (dynamic.isPresent()) {
            ServerRoutingService.ServerRouteStatus status = dynamic.get();
            if (status.cluster() != null && !status.cluster().isBlank()) {
               return this.normalizeQueueKey(status.cluster());
            }

            if (status.role() != null && !status.role().isBlank() && !"UNKNOWN".equalsIgnoreCase(status.role())) {
               return this.normalizeQueueKey(status.role());
            }
         }

         return this.normalizeQueueKey(serverId);
      } else {
         return this.initialQueueKey();
      }
   }

   public String initialQueueKey() {
      return this.normalizeQueueKey(this.config.limbo.initialQueueKey);
   }

   public String loginQueueKey() {
      return "lobby";
   }

   public boolean isInitialQueueKey(String queueKey) {
      return this.normalizeQueueKey(queueKey).equals(this.initialQueueKey());
   }

   public Optional<ServerRoutingService.ServerRouteStatus> describeServer(String serverId) {
      if (serverId != null && !serverId.isBlank()) {
         ServerRegistryRepository.ServerRegistrySnapshot dbSnapshot = this.serverRegistryRepository == null
            ? null
            : this.serverRegistryRepository.snapshotFor(serverId).orElse(null);
         return Optional.of(this.buildStatus(serverId, dbSnapshot));
      } else {
         return Optional.empty();
      }
   }

   public int availableSlotsForServer(String serverId) {
      Optional<ServerRoutingService.ServerRouteStatus> status = this.describeServer(serverId);
      if (status.isEmpty()) {
         return 0;
      } else {
         ServerRoutingService.ServerRouteStatus snapshot = status.get();
         int capacity = snapshot.softCapacity() > 0 ? snapshot.softCapacity() : snapshot.maxPlayers();
         return capacity <= 0 ? 0 : Math.max(0, capacity - snapshot.onlinePlayers());
      }
   }

   public List<ServerRoutingService.ServerRouteStatus> snapshotStatuses() {
      Set<String> ids = new LinkedHashSet<>();
      this.config.backendServers.forEach(server -> ids.add(server.serverId));
      this.stateRegistry.knownHeartbeats().forEach(event -> ids.add(event.serverId()));
      this.proxyServer.getAllServers().forEach(server -> ids.add(server.getServerInfo().getName()));
      Map<String, ServerRegistryRepository.ServerRegistrySnapshot> dbSnapshots = new HashMap<>();
      if (this.serverRegistryRepository != null) {
         this.serverRegistryRepository.snapshotAll().forEach(snapshot -> dbSnapshots.put(snapshot.serverId(), snapshot));
         dbSnapshots.keySet().forEach(ids::add);
      }

      return ids.stream()
         .map(id -> this.buildStatus(id, dbSnapshots.get(id)))
         .filter(status -> status.configured() || (!status.stale() && status.lifecycleState() != ServerLifecycleState.OFFLINE))
         .sorted(Comparator.comparing(ServerRoutingService.ServerRouteStatus::serverId))
         .toList();
   }

   static Optional<String> selectBestInitialServerId(List<ServerRoutingService.ServerRouteStatus> statuses) {
      return bestCandidateId(statuses, ServerRoutingService.ServerRouteStatus::isEligibleForInitial);
   }

   static Optional<String> selectBestFallbackServerId(List<ServerRoutingService.ServerRouteStatus> statuses, String excludedServerId) {
      return bestCandidateId(statuses, status -> status.isEligibleForFallback(excludedServerId));
   }

   static Optional<String> selectBestTargetForQueueId(List<ServerRoutingService.ServerRouteStatus> statuses, String queueKey, Set<String> excludedServerIds) {
      String normalized = queueKey == null ? "" : queueKey.trim().toUpperCase();
      Set<String> excluded = excludedServerIds == null ? Set.of() : excludedServerIds;
      return bestCandidateId(statuses, status -> status.isEligibleForQueue(normalized, excluded));
   }

   private Optional<RegisteredServer> bestCandidate(Optional<String> serverId) {
      return serverId.flatMap(this.proxyServer::getServer);
   }

   private String normalizeQueueKey(String queueKey) {
      return queueKey == null ? "" : queueKey.trim().toLowerCase(Locale.ROOT);
   }

   private static Optional<String> bestCandidateId(
      List<ServerRoutingService.ServerRouteStatus> statuses, Predicate<ServerRoutingService.ServerRouteStatus> filter
   ) {
      return statuses.stream()
         .filter(filter)
         .sorted(
            Comparator.comparing(ServerRoutingService.ServerRouteStatus::overSoftCapacity)
               .thenComparing((ServerRoutingService.ServerRouteStatus status) -> status.openBreachSlots(), Comparator.reverseOrder())
               .thenComparingDouble(ServerRoutingService.ServerRouteStatus::loadRatio)
               .thenComparingInt(ServerRoutingService.ServerRouteStatus::onlinePlayers)
               .thenComparing(ServerRoutingService.ServerRouteStatus::serverId)
         )
         .map(ServerRoutingService.ServerRouteStatus::serverId)
         .findFirst();
   }

   private Optional<RegisteredServer> staticFallback(String excludedServerId) {
      if (this.config.redisEnabled && this.config.requireHeartbeatForRouting) {
         return Optional.empty();
      } else if (this.config.fallbackServer == null || this.config.fallbackServer.isBlank()) {
         return Optional.empty();
      } else {
         return this.config.fallbackServer.equalsIgnoreCase(excludedServerId) ? Optional.empty() : this.proxyServer.getServer(this.config.fallbackServer);
      }
   }

   private ServerRoutingService.ServerRouteStatus buildStatus(String serverId, ServerRegistryRepository.ServerRegistrySnapshot dbSnapshot) {
      ProxyBootstrapConfig.TrackedBackendServer configured = this.configuredServers.get(serverId);
      ServerHeartbeatEvent heartbeat = this.stateRegistry.heartbeatFor(serverId).orElse(null);
      boolean registered = this.proxyServer.getServer(serverId).isPresent();
      String role = dbSnapshot != null
         ? dbSnapshot.role()
         : (heartbeat != null && heartbeat.role() != null ? heartbeat.role().name() : (configured != null ? configured.role : "UNKNOWN"));
      int onlinePlayers = dbSnapshot != null ? dbSnapshot.onlinePlayers() : (heartbeat != null ? heartbeat.onlinePlayers() : 0);
      int maxPlayers = dbSnapshot != null
         ? dbSnapshot.maxPlayers()
         : (heartbeat != null ? heartbeat.maxPlayers() : (configured != null ? configured.softCapacity : 0));
      boolean heartbeatPreferred = heartbeat != null
         && (dbSnapshot == null
            || dbSnapshot.lastHeartbeatAt() == null
            || heartbeat.occurredAtEpochMillis() >= dbSnapshot.lastHeartbeatAt().toEpochMilli());
      boolean joinable = heartbeatPreferred
         ? heartbeat.joinable()
         : dbSnapshot != null ? dbSnapshot.joinable() : false;
      long heartbeatAgeMillis;
      if (dbSnapshot != null && dbSnapshot.lastHeartbeatAt() != null) {
         heartbeatAgeMillis = Math.max(0L, System.currentTimeMillis() - dbSnapshot.lastHeartbeatAt().toEpochMilli());
      } else {
         heartbeatAgeMillis = heartbeat == null ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - heartbeat.occurredAtEpochMillis());
      }

      boolean stale = heartbeatAgeMillis > 30000L;
      int softCapacity = configured != null ? configured.softCapacity : maxPlayers;
      int loadDenominator = Math.max(1, softCapacity > 0 ? softCapacity : Math.max(1, maxPlayers));
      double loadRatio = (double)onlinePlayers / (double)loadDenominator;
      boolean overSoftCapacity = softCapacity > 0 && onlinePlayers >= softCapacity;
      boolean configuredServer = configured != null;
      boolean fallbackEligible = configured != null ? configured.fallbackEligible : "LOBBY".equalsIgnoreCase(role);
      String cluster = configured != null ? configured.cluster : "";
      boolean draining = this.isServerDraining(serverId);
      ServerLifecycleState lifecycleState;
      if (draining) {
         lifecycleState = ServerLifecycleState.DRAINING;
         joinable = false;
      } else if (heartbeatPreferred) {
         lifecycleState = heartbeat.joinable() ? ServerLifecycleState.READY : ServerLifecycleState.BOOTING;
      } else if (dbSnapshot != null) {
         lifecycleState = dbSnapshot.lifecycleState();
      } else {
         lifecycleState = joinable ? ServerLifecycleState.READY : ServerLifecycleState.BOOTING;
      }
      return new ServerRoutingService.ServerRouteStatus(
         serverId,
         role,
         cluster,
         registered,
         configuredServer,
         fallbackEligible,
         joinable,
         stale,
         onlinePlayers,
         maxPlayers,
         softCapacity,
         loadRatio,
         heartbeatAgeMillis,
         overSoftCapacity,
         lifecycleState,
         heartbeat != null ? heartbeat.openBreachSlots() : 0,
         heartbeat != null ? heartbeat.activeBreaches() : 0,
         heartbeat != null ? heartbeat.queuedPlayers() : 0,
         heartbeat != null ? heartbeat.maxPlayersPerPod() : 0
      );
   }

   public static record ServerRouteStatus(
      String serverId,
      String role,
      String cluster,
      boolean registered,
      boolean configured,
      boolean fallbackEligible,
      boolean joinable,
      boolean stale,
      int onlinePlayers,
      int maxPlayers,
      int softCapacity,
      double loadRatio,
      long heartbeatAgeMillis,
      boolean overSoftCapacity,
      ServerLifecycleState lifecycleState,
      int openBreachSlots,
      int activeBreaches,
      int queuedPlayers,
      int maxPlayersPerPod
   ) {
      public boolean isHealthyJoinTarget() {
         return this.registered && this.joinable && !this.stale && this.lifecycleState.isRoutable();
      }

      public boolean isEligibleForInitial() {
         return this.isHealthyJoinTarget() && "LOBBY".equalsIgnoreCase(this.role);
      }

      public boolean isEligibleForFallback(String excludedServerId) {
         return this.isHealthyJoinTarget() && this.fallbackEligible && (excludedServerId == null || !this.serverId.equalsIgnoreCase(excludedServerId));
      }

      public boolean isEligibleForQueue(String queueKey, Set<String> excludedServerIds) {
         boolean breachQueue = "breach".equalsIgnoreCase(queueKey);
         boolean queueMatches = queueKey == null
            || queueKey.isBlank()
            || queueKey.equalsIgnoreCase(this.cluster)
            || queueKey.equalsIgnoreCase(this.role)
            || queueKey.equalsIgnoreCase(this.serverId);
         boolean excluded = excludedServerIds != null && excludedServerIds.stream().anyMatch(this.serverId::equalsIgnoreCase);
         boolean breachCapacityOk = !breachQueue || this.openBreachSlots > 0 || "EXTRACTION".equalsIgnoreCase(this.role);
         return this.isHealthyJoinTarget() && queueMatches && !excluded && !this.overSoftCapacity && breachCapacityOk;
      }
   }
}
