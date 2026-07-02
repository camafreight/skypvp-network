package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.proxy.service.ProxyHoldService;
import org.slf4j.Logger;

public final class MaintenanceLoginListener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final Component MAINTENANCE_MESSAGE = ServerTextUtil.miniMessageComponent(
      "\n<gradient:#FFB300:#FF6F00><bold>  🔧 SkyPvP — Maintenance  </bold></gradient>\n\n<#888888>The network is temporarily down for maintenance.<reset>\n<#888888>We'll be back shortly! Follow us for updates.<reset>\n\n<#555555>────────────────────────<reset>\n<#888888>discord.gg/SkyPvP<reset>\n"
   );
   private final MaintenanceRegistry maintenanceRegistry;
   private final ProxyHoldService holdService;
   private final Logger logger;

   public MaintenanceLoginListener(MaintenanceRegistry maintenanceRegistry, ProxyHoldService holdService, Logger logger) {
      this.maintenanceRegistry = maintenanceRegistry;
      this.holdService = holdService;
      this.logger = logger;
   }

   @Subscribe
   public void onLogin(LoginEvent event) {
      if (this.maintenanceRegistry.isEnabled()) {
         if (this.holdService == null || !this.holdService.available() || !this.holdService.shouldHoldLogin(event.getPlayer())) {
            if (!event.getPlayer().hasPermission("skypvp.maintenance.bypass") && !event.getPlayer().hasPermission("skypvp.staff")) {
               event.setResult(ComponentResult.denied(MAINTENANCE_MESSAGE));
               this.logger.info("Blocked login for '{}' (maintenance mode active)", event.getPlayer().getUsername());
            }
         }
      }
   }
}
