package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import network.skypvp.proxy.service.ServerRoutingService;

public final class InitialServerSelectionListener {
   private final ServerRoutingService routingService;

   public InitialServerSelectionListener(ServerRoutingService routingService) {
      this.routingService = routingService;
   }

   @Subscribe
   public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
      this.routingService.selectBestEntryServer().ifPresent(event::setInitialServer);
   }
}
