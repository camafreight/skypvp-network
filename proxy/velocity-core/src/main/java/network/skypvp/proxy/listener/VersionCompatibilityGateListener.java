package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import org.slf4j.Logger;

public final class VersionCompatibilityGateListener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyBootstrapConfig config;
   private final Logger logger;
   private final int latestKnownProtocol;

   public VersionCompatibilityGateListener(ProxyBootstrapConfig config, Logger logger) {
      this.config = config;
      this.logger = logger;
      this.latestKnownProtocol = this.detectLatestKnownProtocol();
      ProxyBootstrapConfig.VersionCompatibilitySettings settings = config.versionCompatibility;
      if (settings != null && settings.enforceServerGates) {
         logger.info(
            "Version gate active: latest-only roles={} clusters={} requiredLabel='{}' protocol={}",
            settings.latestOnlyRoles,
            settings.latestOnlyClusters,
            settings.latestJavaVersionLabel,
            this.resolveRequiredProtocol(settings)
         );
      }
   }

   @Subscribe
   public void onServerPreConnect(ServerPreConnectEvent event) {
      ProxyBootstrapConfig.VersionCompatibilitySettings settings = this.config.versionCompatibility;
      if (settings != null && settings.enforceServerGates) {
         String targetServerId = event.getOriginalServer().getServerInfo().getName();
         ProxyBootstrapConfig.TrackedBackendServer target = this.resolveTrackedServer(targetServerId);
         if (target != null) {
            if (this.isLatestOnlyTarget(settings, target)) {
               int playerProtocol = event.getPlayer().getProtocolVersion().getProtocol();
               int requiredProtocol = this.resolveRequiredProtocol(settings);
               if (requiredProtocol > 0) {
                  if (playerProtocol != requiredProtocol) {
                     String rendered = settings.latestOnlyDenyMessage
                        .replace("{mode}", target.cluster != null && !target.cluster.isBlank() ? target.cluster : target.role)
                        .replace("{requiredVersion}", settings.latestJavaVersionLabel)
                        .replace("{requiredProtocol}", Integer.toString(requiredProtocol));
                     event.getPlayer().sendMessage(ServerTextUtil.miniMessageComponent(rendered));
                     event.setResult(ServerResult.denied());
                     this.logger
                        .info(
                           "Blocked protocol {} from '{}' to server '{}' (required protocol {} / {}).",
                           playerProtocol,
                           event.getPlayer().getUsername(),
                           targetServerId,
                           requiredProtocol,
                           settings.latestJavaVersionLabel
                        );
                  }
               }
            }
         }
      }
   }

   private ProxyBootstrapConfig.TrackedBackendServer resolveTrackedServer(String targetServerId) {
      if (targetServerId != null && !targetServerId.isBlank() && this.config.backendServers != null) {
         for (ProxyBootstrapConfig.TrackedBackendServer server : this.config.backendServers) {
            if (server != null && targetServerId.equalsIgnoreCase(server.serverId)) {
               return server;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private boolean isLatestOnlyTarget(ProxyBootstrapConfig.VersionCompatibilitySettings settings, ProxyBootstrapConfig.TrackedBackendServer target) {
      String role = target.role == null ? "" : target.role.toUpperCase(Locale.ROOT);
      String cluster = target.cluster == null ? "" : target.cluster.toLowerCase(Locale.ROOT);
      boolean roleMatch = settings.latestOnlyRoles != null
         && settings.latestOnlyRoles
            .stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(role::equals);
      boolean clusterMatch = settings.latestOnlyClusters != null
         && settings.latestOnlyClusters
            .stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(cluster::equals);
      return roleMatch || clusterMatch;
   }

   private int resolveRequiredProtocol(ProxyBootstrapConfig.VersionCompatibilitySettings settings) {
      return settings.latestJavaProtocol > 0 ? settings.latestJavaProtocol : this.latestKnownProtocol;
   }

   private int detectLatestKnownProtocol() {
      try {
         Object[] values = (Object[])Class.forName("com.velocitypowered.api.network.ProtocolVersion").getMethod("values").invoke(null);
         int max = -1;

         for (Object value : values) {
            int protocol = (Integer)value.getClass().getMethod("getProtocol").invoke(value);
            if (protocol > max) {
               max = protocol;
            }
         }

         return max;
      } catch (ReflectiveOperationException var8) {
         this.logger.warn("Unable to detect latest Velocity protocol version: {}", var8.getMessage());
         return -1;
      }
   }
}
