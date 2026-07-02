package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import network.skypvp.proxy.service.ProxyHoldService;

public final class ProxyLimboLifecycleListener {
   private final ProxyHoldService holdService;

   public ProxyLimboLifecycleListener(ProxyHoldService holdService) {
      this.holdService = holdService;
   }

   @Subscribe
   public void onServerConnected(ServerConnectedEvent event) {
      this.holdService.onBackendConnected(event.getPlayer());
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      this.holdService.onProxyDisconnect(event.getPlayer().getUniqueId());
   }
}
