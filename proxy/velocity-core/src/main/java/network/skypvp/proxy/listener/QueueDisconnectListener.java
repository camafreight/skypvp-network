package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.QueueService;

public final class QueueDisconnectListener {
   private final QueueService queueService;
   private final PartyQueueService partyQueueService;

   public QueueDisconnectListener(QueueService queueService, PartyQueueService partyQueueService) {
      this.queueService = queueService;
      this.partyQueueService = partyQueueService;
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      this.queueService.leaveQueue(event.getPlayer().getUniqueId());
      if (this.partyQueueService != null) {
         this.partyQueueService.removeByMember(event.getPlayer().getUniqueId());
      }
   }
}
