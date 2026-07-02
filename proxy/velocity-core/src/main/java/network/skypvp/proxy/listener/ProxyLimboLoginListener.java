package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import network.skypvp.proxy.service.ProxyHoldService;

public final class ProxyLimboLoginListener {
   private final ProxyHoldService holdService;

   public ProxyLimboLoginListener(ProxyHoldService holdService) {
      this.holdService = holdService;
   }

   @Subscribe
   public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
      if (this.holdService.available() && this.holdService.shouldHoldLogin(event.getPlayer())) {
         event.addOnJoinCallback(() -> this.holdService.holdLogin(event.getPlayer()));
      }
   }
}
