package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.repository.PlayerSocialSettingsRepository;
import network.skypvp.shared.PlayerSessionAction;
import network.skypvp.shared.PlayerSessionEvent;
import network.skypvp.shared.RedisEventPublisher;
import org.slf4j.Logger;

public final class ProxyConnectionListener {
   private final Logger logger;
   private final ProxyBootstrapConfig config;
   private final RedisEventPublisher redisPublisher;
   private final NetworkStateRegistry stateRegistry;
   private final PlayerSocialSettingsRepository socialSettingsRepository;
   private final Map<UUID, Long> connectedAtEpochMillis = new ConcurrentHashMap<>();

   public ProxyConnectionListener(
      Logger logger,
      ProxyBootstrapConfig config,
      RedisEventPublisher redisPublisher,
      NetworkStateRegistry stateRegistry
   ) {
      this(logger, config, redisPublisher, stateRegistry, null);
   }

   public ProxyConnectionListener(
      Logger logger,
      ProxyBootstrapConfig config,
      RedisEventPublisher redisPublisher,
      NetworkStateRegistry stateRegistry,
      PlayerSocialSettingsRepository socialSettingsRepository
   ) {
      this.logger = logger;
      this.config = config;
      this.redisPublisher = redisPublisher;
      this.stateRegistry = stateRegistry;
      this.socialSettingsRepository = socialSettingsRepository;
   }

   @Subscribe
   public void onLogin(LoginEvent event) {
      long now = System.currentTimeMillis();
      UUID playerId = event.getPlayer().getUniqueId();
      this.connectedAtEpochMillis.put(playerId, now);
      if (this.socialSettingsRepository != null) {
         this.socialSettingsRepository.preload(playerId);
      }
      if (this.redisPublisher != null) {
         this.redisPublisher
            .publishJson(
               this.config.sessionChannel,
               new PlayerSessionEvent(PlayerSessionAction.AUTHENTICATED, playerId, event.getPlayer().getUsername(), null, now)
            );
      }

      this.logger.info("Player '{}' authenticated for network '{}'", event.getPlayer().getUsername(), this.config.networkName);
   }

   @Subscribe
   public void onServerConnected(ServerConnectedEvent event) {
      if (this.redisPublisher != null) {
         this.redisPublisher
            .publishJson(
               this.config.sessionChannel,
               new PlayerSessionEvent(
                  PlayerSessionAction.SERVER_CONNECTED,
                  event.getPlayer().getUniqueId(),
                  event.getPlayer().getUsername(),
                  event.getServer().getServerInfo().getName(),
                  System.currentTimeMillis()
               )
            );
      }

      this.logger.info("Player '{}' connected to backend '{}'", event.getPlayer().getUsername(), event.getServer().getServerInfo().getName());
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      UUID playerId = event.getPlayer().getUniqueId();
      long connectedAt = this.connectedAtEpochMillis.getOrDefault(playerId, System.currentTimeMillis());
      if (this.socialSettingsRepository != null) {
         this.socialSettingsRepository.evict(playerId);
      }
      if (this.redisPublisher != null) {
         this.redisPublisher
            .publishJson(
               this.config.sessionChannel,
               new PlayerSessionEvent(
                  PlayerSessionAction.DISCONNECTED,
                  playerId,
                  event.getPlayer().getUsername(),
                  event.getPlayer().getCurrentServer().map(serverConnection -> serverConnection.getServerInfo().getName()).orElse(null),
                  connectedAt
               )
            );
      }

      this.connectedAtEpochMillis.remove(playerId);
      this.logger.info("Player '{}' disconnected from the proxy", event.getPlayer().getUsername());
   }
}
