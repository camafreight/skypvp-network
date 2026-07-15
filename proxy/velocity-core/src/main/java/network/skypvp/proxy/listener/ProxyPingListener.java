package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.util.NetworkServerIconLibrary;
import network.skypvp.shared.NetworkMotdLibrary;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.slf4j.Logger;

public final class ProxyPingListener {
   private final ProxyServer proxyServer;
   private final ProxyBootstrapConfig config;
   private final MaintenanceRegistry maintenanceRegistry;
   private final NetworkStateRegistry stateRegistry;
   private final Logger logger;

   public ProxyPingListener(
      ProxyServer proxyServer,
      ProxyBootstrapConfig config,
      MaintenanceRegistry maintenanceRegistry,
      NetworkStateRegistry stateRegistry,
      Logger logger
   ) {
      this.proxyServer = proxyServer;
      this.config = config;
      this.maintenanceRegistry = maintenanceRegistry;
      this.stateRegistry = stateRegistry;
      this.logger = logger;
   }

   @Subscribe
   public void onProxyPing(ProxyPingEvent event) {
      ServerPing current = event.getPing();
      int proxyOnline = this.proxyServer.getPlayerCount();
      int maxPlayers = Math.max(proxyOnline, current.getPlayers().map(ServerPing.Players::getMax).orElse(5000));
      long tick = System.currentTimeMillis();

      NetworkMotdLibrary.Snapshot snapshot = NetworkMotdLibrary.Snapshot.builder()
         .refreshEpochMillis(tick)
         .proxyOnlinePlayers(proxyOnline)
         .networkOnlinePlayers(this.networkOnlinePlayers())
         .maxPlayers(maxPlayers)
         .maintenance(this.maintenanceRegistry != null && this.maintenanceRegistry.isEnabled())
         .extractionPods(this.extractionPods())
         .openBreachSlots(this.openBreachSlots())
         .queuedPlayers(this.queuedPlayers())
         .versionRange(this.versionRangeLabel())
         .build();

      Component description = NetworkMotdLibrary.buildDescription(snapshot);
      ServerPing.Builder builder = current.asBuilder()
         .description(description)
         .onlinePlayers(proxyOnline)
         .maximumPlayers(maxPlayers);
      NetworkServerIconLibrary.favicon(this.logger).ifPresent(builder::favicon);
      event.setPing(builder.build());
   }

   private int networkOnlinePlayers() {
      if (this.stateRegistry == null) {
         return 0;
      }
      return this.stateRegistry.knownHeartbeats().stream().mapToInt(ServerHeartbeatEvent::onlinePlayers).sum();
   }

   private int extractionPods() {
      if (this.stateRegistry == null) {
         return 0;
      }
      return (int)this.stateRegistry.knownHeartbeats().stream()
         .filter(event -> event.role() == NetworkServerRole.EXTRACTION)
         .count();
   }

   private int openBreachSlots() {
      if (this.stateRegistry == null) {
         return 0;
      }
      return this.stateRegistry.knownHeartbeats().stream()
         .filter(event -> event.role() == NetworkServerRole.EXTRACTION)
         .mapToInt(ServerHeartbeatEvent::openBreachSlots)
         .sum();
   }

   private int queuedPlayers() {
      if (this.stateRegistry == null) {
         return 0;
      }
      return this.stateRegistry.knownHeartbeats().stream()
         .filter(event -> event.role() == NetworkServerRole.EXTRACTION)
         .mapToInt(ServerHeartbeatEvent::queuedPlayers)
         .sum();
   }

   private String versionRangeLabel() {
      if (this.config.versionCompatibility == null || this.config.versionCompatibility.latestJavaVersionLabel == null) {
         return "1.20–1.21.11";
      }
      String latest = this.config.versionCompatibility.latestJavaVersionLabel.trim();
      if (latest.isBlank()) {
         return "1.20–1.21.11";
      }
      return "1.20–" + latest;
   }
}
