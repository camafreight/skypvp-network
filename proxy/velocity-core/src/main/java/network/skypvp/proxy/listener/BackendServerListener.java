package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.service.ProxyHoldService;
import network.skypvp.proxy.service.QueueService;
import network.skypvp.proxy.service.ServerRoutingService;
import org.slf4j.Logger;

public final class BackendServerListener {
   private final ServerRoutingService routingService;
   private final ProxyHoldService holdService;
   private final QueueService queueService;
   private final Logger logger;

   public BackendServerListener(ServerRoutingService routingService, ProxyHoldService holdService, QueueService queueService, Logger logger) {
      this.routingService = routingService;
      this.holdService = holdService;
      this.queueService = queueService;
      this.logger = logger;
   }

   @Subscribe
   public EventTask onKickedFromServer(KickedFromServerEvent event) {
      String kickedFrom = event.getServer().getServerInfo().getName();
      Player player = event.getPlayer();
      String playerName = player.getUsername();
      String queueKey = this.routingService.queueKeyForServer(kickedFrom);
      if (!queueKey.isBlank()) {
         Optional<RegisteredServer> sameModeTarget = this.routingService.selectBestTargetForQueue(queueKey, Set.of(kickedFrom));
         if (sameModeTarget.isPresent()) {
            RegisteredServer target = sameModeTarget.get();
            Component notification = ((TextComponent)ServerTextUtil.component("&eYour server was unavailable. Moved to ")
                  .append(ServerTextUtil.component("&6" + target.getServerInfo().getName())))
               .append(ServerTextUtil.component("&e."));
            event.setResult(RedirectPlayer.create(target, notification));
            this.logger
               .info("Redirected '{}' from '{}' to same-mode target '{}' via queue-aware fallback.", playerName, kickedFrom, target.getServerInfo().getName());
            return null;
         }

         if (this.holdService != null && this.holdService.available()) {
            this.logger
               .warn("No healthy same-queue target available for '{}' after kick from '{}' — deferring to proxy limbo kick callback.", playerName, kickedFrom);
            return null;
         }
      }

      Optional<RegisteredServer> fallback = this.routingService.selectFallback(kickedFrom);
      if (fallback.isEmpty()) {
         Component reason = (Component)event.getServerKickReason().orElse(ServerTextUtil.component("Server unavailable."));
         if (this.holdService != null && this.holdService.available()) {
            this.logger.warn("No healthy fallback available for '{}' after kick from '{}' — deferring to proxy limbo kick callback.", playerName, kickedFrom);
            return null;
         } else {
            event.setResult(DisconnectPlayer.create(reason));
            this.logger.error("No healthy fallback available — cannot redirect '{}'.", playerName);
            return null;
         }
      } else if (kickedFrom.equalsIgnoreCase(fallback.get().getServerInfo().getName())) {
         Component reason = (Component)event.getServerKickReason().orElse(ServerTextUtil.component("Disconnected from the network."));
         if (this.holdService != null && this.holdService.available()) {
            this.logger.warn("Fallback candidate '{}' matched the kicked server for '{}' — deferring to proxy limbo kick callback.", kickedFrom, playerName);
            return null;
         } else {
            event.setResult(DisconnectPlayer.create(reason));
            this.logger.warn("Player '{}' kicked from fallback candidate '{}' — disconnecting.", playerName, kickedFrom);
            return null;
         }
      } else {
         if (!queueKey.isBlank()) {
            QueueService.QueueJoinResult queued = this.queueService.joinQueue(player.getUniqueId(), playerName, queueKey);
            if (queued.valid()) {
               this.logger
                  .info("Queued '{}' for '{}' after kick from '{}'; position {}/{}.", playerName, queueKey, kickedFrom, queued.position(), queued.queueSize());
            }
         }

         Component notification = ((TextComponent)((TextComponent)((TextComponent)ServerTextUtil.component("&eYou were disconnected from ")
                     .append(ServerTextUtil.component("&c" + kickedFrom)))
                  .append(ServerTextUtil.component("&e and moved to ")))
               .append(ServerTextUtil.component("&6" + fallback.get().getServerInfo().getName())))
            .append(ServerTextUtil.component("&e."));
         if (!queueKey.isBlank()) {
            notification = notification.append(ServerTextUtil.component("&7 You are queued for '"))
               .append(ServerTextUtil.component("&b" + queueKey))
               .append(ServerTextUtil.component("&7'."));
         }

         event.setResult(RedirectPlayer.create(fallback.get(), notification));
         this.logger.info("Redirected '{}' from '{}' to fallback '{}'.", playerName, kickedFrom, fallback.get().getServerInfo().getName());
         return null;
      }
   }
}
