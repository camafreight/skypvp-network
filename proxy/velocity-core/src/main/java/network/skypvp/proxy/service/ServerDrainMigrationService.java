package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import network.skypvp.shared.ServerDrainEvent;
import network.skypvp.shared.ServerTextUtil;
import org.slf4j.Logger;

public final class ServerDrainMigrationService {
   private static final int MAX_MIGRATION_ATTEMPTS = 30;
   private static final long RETRY_INTERVAL_SECONDS = 2L;
   private static final Map<String, String> DYNAMIC_ROLES = Map.of(
      "LOBBY", "skypvp-lobby",
      "EXTRACTION", "skypvp-extraction"
   );

   private final Object taskOwner;
   private final ProxyServer proxyServer;
   private final ServerRoutingService routingService;
   private final ProxyHoldService holdService;
   private final KubernetesApiService kubeClient;
   private final Logger logger;

   public ServerDrainMigrationService(
      Object taskOwner,
      ProxyServer proxyServer,
      ServerRoutingService routingService,
      ProxyHoldService holdService,
      KubernetesApiService kubeClient,
      Logger logger
   ) {
      this.taskOwner = taskOwner;
      this.proxyServer = proxyServer;
      this.routingService = routingService;
      this.holdService = holdService;
      this.kubeClient = kubeClient;
      this.logger = logger;
   }

   public void handleServerDraining(ServerDrainEvent event) {
      if (event == null || event.serverId() == null || event.serverId().isBlank()) {
         return;
      }

      String serverId = event.serverId();
      this.routingService.markServerAsDraining(serverId);
      this.ensureReplacementCapacity(serverId);
      this.scheduleMigrationAttempt(serverId, 0);
   }

   private void ensureReplacementCapacity(String serverId) {
      if (this.routingService.selectDrainTarget(serverId).isPresent()) {
         return;
      }

      if (this.kubeClient == null || !this.kubeClient.available()) {
         return;
      }

      Optional<ServerRoutingService.ServerRouteStatus> status = this.routingService.describeServer(serverId);
      if (status.isEmpty() || status.get().role() == null) {
         return;
      }

      String role = status.get().role().toUpperCase();
      String deploymentName = DYNAMIC_ROLES.get(role);
      if (deploymentName == null) {
         return;
      }

      try {
         KubernetesApiService.KubernetesResourceStatus resourceStatus = this.kubeClient.getResourceStatus(
            deploymentName,
            KubernetesApiService.ResourceKind.STATEFULSET
         );
         int nextReplicas = resourceStatus.specReplicas() + 1;
         this.logger.info(
            "[Lifecycle] No drain target for '{}' — scaling StatefulSet '{}' from {} to {} replicas.",
            serverId,
            deploymentName,
            resourceStatus.specReplicas(),
            nextReplicas
         );
         this.kubeClient.scaleResource(deploymentName, KubernetesApiService.ResourceKind.STATEFULSET, nextReplicas);
      } catch (Exception exception) {
         this.logger.warn("[Lifecycle] Failed to scale replacement capacity for '{}': {}", serverId, exception.getMessage());
      }
   }

   private void scheduleMigrationAttempt(String serverId, int attempt) {
      this.proxyServer.getScheduler()
         .buildTask(this.taskOwner, () -> this.migratePlayers(serverId, attempt))
         .delay(attempt == 0 ? 0L : RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS)
         .schedule();
   }

   private void migratePlayers(String serverId, int attempt) {
      Optional<RegisteredServer> drainingServer = this.proxyServer.getServer(serverId);
      if (drainingServer.isEmpty()) {
         return;
      }

      java.util.List<Player> players = new java.util.ArrayList<>(drainingServer.get().getPlayersConnected());
      if (players.isEmpty()) {
         this.logger.info("[Lifecycle] Drain migration complete for '{}' — no players remain.", serverId);
         return;
      }

      Optional<RegisteredServer> target = this.routingService.selectDrainTarget(serverId);
      if (target.isPresent()) {
         RegisteredServer destination = target.get();
         String destinationName = destination.getServerInfo().getName();
         for (Player player : players) {
            if (player.getCurrentServer().isEmpty() || !serverId.equalsIgnoreCase(player.getCurrentServer().get().getServerInfo().getName())) {
               continue;
            }

            player.sendMessage(
               ServerTextUtil.component("&aServer is restarting. Moving you to &e" + destinationName + "&a...")
            );
            player.createConnectionRequest(destination).fireAndForget();
         }

         java.util.List<Player> remaining = new java.util.ArrayList<>(drainingServer.get().getPlayersConnected());
         if (!remaining.isEmpty() && attempt + 1 < MAX_MIGRATION_ATTEMPTS) {
            this.scheduleMigrationAttempt(serverId, attempt + 1);
         } else if (!remaining.isEmpty()) {
            this.logger.warn(
               "[Lifecycle] Drain migration target '{}' could not absorb all players on '{}' — {} remain.",
               destinationName,
               serverId,
               remaining.size()
            );
            this.sendPlayersToLimbo(serverId, remaining);
         } else {
            this.logger.info("[Lifecycle] Drain migration complete for '{}'.", serverId);
         }
         return;
      }

      if (attempt + 1 >= MAX_MIGRATION_ATTEMPTS) {
         this.logger.warn(
            "[Lifecycle] Exhausted drain migration retries for '{}' — {} player(s) still connected.",
            serverId,
            players.size()
         );
         this.sendPlayersToLimbo(serverId, players);
         return;
      }

      if (attempt == 0 || attempt % 5 == 0) {
         this.ensureReplacementCapacity(serverId);
      }

      this.logger.debug(
         "[Lifecycle] No drain target for '{}' yet (attempt {}/{}). Retrying in {}s for {} player(s).",
         serverId,
         attempt + 1,
         MAX_MIGRATION_ATTEMPTS,
         RETRY_INTERVAL_SECONDS,
         players.size()
      );
      this.scheduleMigrationAttempt(serverId, attempt + 1);
   }

   private void sendPlayersToLimbo(String serverId, java.util.List<Player> players) {
      String queueKey = this.routingService.queueKeyForServer(serverId);
      for (Player player : players) {
         if (player.getCurrentServer().isEmpty() || !serverId.equalsIgnoreCase(player.getCurrentServer().get().getServerInfo().getName())) {
            continue;
         }

         if (this.holdService != null && this.holdService.available()) {
            this.holdService.holdForOutage(player, queueKey, serverId);
         } else {
            player.disconnect(ServerTextUtil.component("&cServer is restarting and no fallback is available."));
         }
      }
   }
}
