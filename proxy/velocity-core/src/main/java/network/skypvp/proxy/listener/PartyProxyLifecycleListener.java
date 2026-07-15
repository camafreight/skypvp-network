package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;
import network.skypvp.proxy.service.BreachPlayMatchmakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PartyProxyLifecycleListener {
   private static final Logger logger = LoggerFactory.getLogger(PartyProxyLifecycleListener.class);
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyTransferGate transferGate;
   private final BreachPlayMatchmakingService breachPlayMatchmaking;

   public PartyProxyLifecycleListener(ProxyServer proxyServer, PartyService partyService, PartyTransferGate transferGate) {
      this(proxyServer, partyService, transferGate, null);
   }

   public PartyProxyLifecycleListener(
      ProxyServer proxyServer,
      PartyService partyService,
      PartyTransferGate transferGate,
      BreachPlayMatchmakingService breachPlayMatchmaking
   ) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.transferGate = transferGate;
      this.breachPlayMatchmaking = breachPlayMatchmaking;
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      if (this.transferGate != null) {
         this.transferGate.clear(event.getPlayer().getUniqueId());
      }
      if (this.breachPlayMatchmaking != null) {
         this.breachPlayMatchmaking.cancelPendingDeployForMember(event.getPlayer().getUniqueId(), "disconnect");
      }
      if (this.partyService == null) {
         return;
      }
      Player disconnectingPlayer = event.getPlayer();
      UUID playerId = disconnectingPlayer.getUniqueId();
      String username = disconnectingPlayer.getUsername();

      // A disconnect keeps the player in the party (so a reconnect stays grouped + friendly-fire protected). The
      // party is only disbanded when the last online member drops, and leadership hands off to an online member
      // if the leader is the one that disconnected.
      PartyService.PartyDisconnectResult result = this.partyService.handleDisconnect(this.proxyServer, playerId);
      switch (result.outcome()) {
         case NONE -> {
         }
         case DISBANDED -> logger.info("Party disbanded: '{}' was the last online member", username);
         case KEPT -> {
            Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(playerId);
            if (partyOpt.isEmpty()) {
               return;
            }
            PartyService.PartyState party = partyOpt.get();
            String newLeaderName = result.newLeaderId() == null
               ? null
               : this.proxyServer.getPlayer(result.newLeaderId()).<String>map(Player::getUsername).orElse("Unknown");
            if (result.wasLeader() && result.newLeaderId() != null) {
               logger.info("Party leadership handed off: leader '{}' disconnected, '{}' now leading while they are offline", username, newLeaderName);
            }

            for (UUID memberId : party.members()) {
               if (memberId.equals(playerId)) {
                  continue;
               }
               Optional<Player> memberOpt = this.proxyServer.getPlayer(memberId);
               if (memberOpt.isEmpty()) {
                  continue;
               }
               Player member = memberOpt.get();
               member.sendMessage(ServerTextUtil.component("&7" + username + " disconnected (still in the party)."));
               if (result.wasLeader() && result.newLeaderId() != null) {
                  if (memberId.equals(result.newLeaderId())) {
                     member.sendMessage(ServerTextUtil.component("&eYou are now the party leader while " + username + " is offline."));
                  } else {
                     member.sendMessage(ServerTextUtil.component("&b" + newLeaderName + " is now leading while " + username + " is offline."));
                  }
               }
            }
         }
      }
   }

   @Subscribe
   public void onServerConnected(ServerConnectedEvent event) {
      if (this.breachPlayMatchmaking != null) {
         this.breachPlayMatchmaking.completeDeployForMember(
            event.getPlayer().getUniqueId(),
            event.getServer().getServerInfo().getName()
         );
      }
   }
}
