package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.ServerRoutingService;

public final class PartyAtomicAdmissionListener {
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyQueueService partyQueueService;
   private final ServerRoutingService routingService;

   public PartyAtomicAdmissionListener(
      ProxyServer proxyServer, PartyService partyService, PartyQueueService partyQueueService, ServerRoutingService routingService
   ) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.partyQueueService = partyQueueService;
      this.routingService = routingService;
   }

   @Subscribe
   public void onServerPreConnect(ServerPreConnectEvent event) {
      if (this.partyService != null && this.partyQueueService != null && this.routingService != null) {
         UUID playerId = event.getPlayer().getUniqueId();
         Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(playerId);
         if (!partyOpt.isEmpty()) {
            PartyService.PartyState party = partyOpt.get();
            if (party.followLeader() && party.leaderId().equals(playerId)) {
               String targetServerId = event.getOriginalServer().getServerInfo().getName();
               List<UUID> onlineMembers = this.partyService.onlineMembers(this.proxyServer, party.partyId());
               if (onlineMembers.size() > 1) {
                  int availableSlots = this.routingService.availableSlotsForServer(targetServerId);
                  if (availableSlots < onlineMembers.size()) {
                     PartyQueueService.QueueGroupResult grouped = this.partyQueueService
                        .enqueue(this.routingService.queueKeyForServer(targetServerId), party.partyId(), party.leaderId(), onlineMembers);
                     if (grouped.valid()) {
                        event.getPlayer()
                           .sendMessage(
                              Component.text(
                                 "Queued your party as a full group because " + targetServerId + " lacks free slots for everyone.", NamedTextColor.YELLOW
                              )
                           );
                        event.setResult(ServerResult.denied());
                     }
                  }
               }
            }
         }
      }
   }
}
