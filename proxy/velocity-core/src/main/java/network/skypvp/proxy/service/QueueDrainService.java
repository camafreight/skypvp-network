package network.skypvp.proxy.service;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.shared.QueueTransferEvent;
import network.skypvp.shared.RedisEventPublisher;
import org.slf4j.Logger;

public final class QueueDrainService {
   private final ProxyServer proxyServer;
   private final QueueService queueService;
   private final ServerRoutingService routingService;
   private final ProxyHoldService holdService;
   private final AdmissionControlService admissionControl;
   private final int maxTransfersPerDrainPass;
   private final RedisEventPublisher redisPublisher;
   private final String queueChannel;
   private final MaintenanceRegistry maintenanceRegistry;
   private final Logger logger;

   public QueueDrainService(
      ProxyServer proxyServer,
      QueueService queueService,
      ServerRoutingService routingService,
      ProxyHoldService holdService,
      AdmissionControlService admissionControl,
      int maxTransfersPerDrainPass,
      MaintenanceRegistry maintenanceRegistry,
      RedisEventPublisher redisPublisher,
      String queueChannel,
      Logger logger
   ) {
      this.proxyServer = proxyServer;
      this.queueService = queueService;
      this.routingService = routingService;
      this.holdService = holdService;
      this.admissionControl = admissionControl;
      this.maxTransfersPerDrainPass = Math.max(1, maxTransfersPerDrainPass);
      this.maintenanceRegistry = maintenanceRegistry;
      this.redisPublisher = redisPublisher;
      this.queueChannel = queueChannel;
      this.logger = logger;
   }

   public int drainAllQueues() {
      if (this.maintenanceRegistry != null && this.maintenanceRegistry.isEnabled()) {
         return 0;
      } else {
         int moved = 0;

         for (String queueKey : this.queueService.queueKeys()) {
            moved += this.drainQueue(queueKey);
         }

         return moved;
      }
   }

   public int drainQueue(String queueKey) {
      if (this.maintenanceRegistry != null && this.maintenanceRegistry.isEnabled()) {
         return 0;
      } else {
         int moved = 0;
         Set<String> usedTargets = new HashSet<>();

         while (moved < this.maxTransfersPerDrainPass) {
            if (!this.admissionControl.tryAcquireTransferPermit()) {
               return moved;
            }

            Optional<RegisteredServer> targetOpt = this.routingService.selectBestTargetForQueue(queueKey, usedTargets);
            if (targetOpt.isEmpty()) {
               return moved;
            }

            RegisteredServer target = targetOpt.get();
            Optional<QueueService.QueueEntry> entryOpt = this.queueService.poll(queueKey);
            if (entryOpt.isEmpty()) {
               return moved;
            }

            QueueService.QueueEntry head = entryOpt.get();
            Optional<Player> playerOpt = this.proxyServer.getPlayer(head.playerId());
            if (playerOpt.isEmpty()) {
               boolean releasedHeldPlayer = this.holdService != null && this.holdService.available() && this.holdService.releaseHeld(head.playerId(), target);
               if (releasedHeldPlayer) {
                  if (this.redisPublisher != null) {
                     this.redisPublisher
                        .publishJson(
                           this.queueChannel,
                           new QueueTransferEvent(head.playerId(), head.username(), queueKey, target.getServerInfo().getName(), System.currentTimeMillis())
                        );
                  }

                  this.logger.info("Drained queue '{}' -> '{}' for {} ({})", queueKey, target.getServerInfo().getName(), head.username(), head.playerId());
                  moved++;
                  usedTargets.add(target.getServerInfo().getName());
               }
            } else {
               Player player = playerOpt.get();
               boolean releasedFromHold = this.holdService != null && this.holdService.available() && this.holdService.releaseHeld(player, target);
               if (releasedFromHold) {
                  if (this.redisPublisher != null) {
                     this.redisPublisher
                        .publishJson(
                           this.queueChannel,
                           new QueueTransferEvent(
                              player.getUniqueId(), player.getUsername(), queueKey, target.getServerInfo().getName(), System.currentTimeMillis()
                           )
                        );
                  }

                  this.logger
                     .info("Drained queue '{}' -> '{}' for {} ({})", queueKey, target.getServerInfo().getName(), player.getUsername(), player.getUniqueId());
                  moved++;
                  usedTargets.add(target.getServerInfo().getName());
               } else {
                  String currentServer = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
                  if (currentServer.equalsIgnoreCase(target.getServerInfo().getName())) {
                     player.sendMessage(
                        ServerTextUtil.component("&e" + "You are already connected to " + target.getServerInfo().getName() + ". Removed from queue.")
                     );
                     this.logger.info("Skipped same-server queue drain for {} ({}) on '{}'", player.getUsername(), player.getUniqueId(), queueKey);
                     moved++;
                     usedTargets.add(target.getServerInfo().getName());
                  } else {
                     if (!releasedFromHold) {
                        player.createConnectionRequest(target).fireAndForget();
                     }

                     player.sendMessage(ServerTextUtil.component("&a" + "Queue ready: sending you to " + target.getServerInfo().getName() + "."));
                     if (this.redisPublisher != null) {
                        this.redisPublisher
                           .publishJson(
                              this.queueChannel,
                              new QueueTransferEvent(
                                 player.getUniqueId(), player.getUsername(), queueKey, target.getServerInfo().getName(), System.currentTimeMillis()
                              )
                           );
                     }

                     this.logger
                        .info("Drained queue '{}' -> '{}' for {} ({})", queueKey, target.getServerInfo().getName(), player.getUsername(), player.getUniqueId());
                     moved++;
                     usedTargets.add(target.getServerInfo().getName());
                  }
               }
            }
         }

         return moved;
      }
   }

   public AdmissionControlService.AdmissionSnapshot admissionSnapshot() {
      return this.admissionControl.snapshot();
   }

   public void setAdmissionEnabled(boolean enabled) {
      this.admissionControl.setEnabled(enabled);
   }

   public void reconfigureAdmission(int transfersPerSecond, int burstCapacity) {
      this.admissionControl.reconfigure(transfersPerSecond, burstCapacity);
   }

   public int maxTransfersPerDrainPass() {
      return this.maxTransfersPerDrainPass;
   }
}
