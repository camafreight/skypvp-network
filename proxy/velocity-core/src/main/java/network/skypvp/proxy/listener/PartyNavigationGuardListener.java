package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;
import network.skypvp.shared.ServerTextUtil;

public final class PartyNavigationGuardListener {
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyTransferGate transferGate;

   public PartyNavigationGuardListener(ProxyServer proxyServer, PartyService partyService, PartyTransferGate transferGate) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.transferGate = transferGate;
   }

   @Subscribe
   public void onServerPreConnect(ServerPreConnectEvent event) {
      if (this.partyService == null) {
         return;
      }
      Player player = event.getPlayer();
      UUID playerId = player.getUniqueId();
      Optional<String> currentServer = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
      if (currentServer.isEmpty()) {
         return;
      }
      String targetServer = event.getOriginalServer().getServerInfo().getName();
      if (targetServer.equalsIgnoreCase(currentServer.get())) {
         return;
      }
      if (this.transferGate != null && this.transferGate.allows(playerId, targetServer)) {
         return;
      }
      Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(playerId);
      if (partyOpt.isEmpty()) {
         return;
      }
      PartyService.PartyState party = partyOpt.get();
      if (party.leaderId().equals(playerId)) {
         return;
      }
      this.partyService.refreshFollowLeader(party.partyId());
      party = this.partyService.partyForMember(playerId).orElse(party);
      if (this.isFollowNavigationAllowed(party, targetServer)) {
         return;
      }
      String leaderName = this.resolveLeaderName(party.leaderId());
      player.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#ff6b6b><bold>✗ Party Lock</bold><reset> <#ffb3b3>Only the party leader can navigate. Wait for <#ffd93d>"
               + leaderName
               + "<#ffb3b3> to move the party."
         )
      );
      event.setResult(ServerResult.denied());
   }

   private boolean isFollowNavigationAllowed(PartyService.PartyState party, String targetServer) {
      if (!party.followLeader()) {
         return false;
      }
      return this.proxyServer.getPlayer(party.leaderId())
         .flatMap(Player::getCurrentServer)
         .map(connection -> targetServer.equalsIgnoreCase(connection.getServerInfo().getName()))
         .orElse(false);
   }

   private String resolveLeaderName(UUID leaderId) {
      if (leaderId == null) {
         return "the party leader";
      }
      return this.proxyServer.getPlayer(leaderId)
         .map(Player::getUsername)
         .orElse("the party leader");
   }
}
