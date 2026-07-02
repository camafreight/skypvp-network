package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;

public final class PartyFollowLeaderListener {
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyMemberMover partyMemberMover;

   public PartyFollowLeaderListener(
      ProxyServer proxyServer, PartyService partyService, PartyMemberMover partyMemberMover
   ) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.partyMemberMover = partyMemberMover;
   }

   @Subscribe
   public void onServerConnected(ServerConnectedEvent event) {
      Player leader = event.getPlayer();
      Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(leader.getUniqueId());
      if (partyOpt.isEmpty()) {
         return;
      }

      PartyService.PartyState party = partyOpt.get();
      this.partyService.refreshFollowLeader(party.partyId());
      party = this.partyService.partyForMember(leader.getUniqueId()).orElse(party);
      if (!party.followLeader() || !party.leaderId().equals(leader.getUniqueId())) {
         return;
      }

      RegisteredServer targetServer = event.getServer();
      PartyMemberMover.MoveResult result = this.partyMemberMover.moveMembers(
         party.partyId(),
         leader.getUniqueId(),
         targetServer,
         party.members(),
         "<#94a3b8>Following party leader to <#e2e8f0>{server}<#94a3b8>.",
         "Your party was queued for {server} because there are not enough slots."
      );
      if (result.status() == PartyMemberMover.MoveStatus.QUEUED && result.leaderMessage() != null) {
         leader.sendMessage(
            ServerTextUtil.miniMessageComponent(
               result.leaderMessage().replace("{server}", targetServer.getServerInfo().getName())
            )
         );
      }
   }
}
