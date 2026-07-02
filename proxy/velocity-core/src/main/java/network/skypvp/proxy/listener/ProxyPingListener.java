package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.shared.NetworkAnimationEngine;

public final class ProxyPingListener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyServer proxyServer;
   private final ProxyBootstrapConfig config;
   private final MaintenanceRegistry maintenanceRegistry;

   public ProxyPingListener(ProxyServer proxyServer, ProxyBootstrapConfig config, MaintenanceRegistry maintenanceRegistry) {
      this.proxyServer = proxyServer;
      this.config = config;
      this.maintenanceRegistry = maintenanceRegistry;
   }

   @Subscribe
   public void onProxyPing(ProxyPingEvent event) {
      // Headless core: do not mutate proxy ping/MOTD.
   }
}
