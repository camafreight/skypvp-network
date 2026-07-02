package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import network.skypvp.proxy.ProxyBootstrap;
import network.skypvp.shared.RedisConnectionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class WebAdminIntegrationService implements AutoCloseable {
   private final RedisConnectionSettings settings;
   private final String serverId;
   private final ProxyServer proxyServer;
   private final ProxyBootstrap plugin;
   private final Logger slf4jLogger;
   private volatile boolean running;
   private JedisPubSub pubSub;
   private Thread worker;

   public WebAdminIntegrationService(RedisConnectionSettings settings, String serverId, ProxyServer proxyServer, ProxyBootstrap plugin) {
      this.settings = settings;
      this.serverId = serverId;
      this.proxyServer = proxyServer;
      this.plugin = plugin;
      this.slf4jLogger = LoggerFactory.getLogger(WebAdminIntegrationService.class);
   }

   public void start() {
      if (!this.running) {
         this.running = true;
         this.worker = new Thread(this::runLoop, "SkyPvP-proxy-webadmin-sub");
         this.worker.setDaemon(true);
         this.worker.start();
      }
   }

   private void runLoop() {
      while (this.running) {
         try (Jedis jedis = new Jedis(this.settings.host(), this.settings.port())) {
            if (!this.settings.sanitizedPassword().isBlank()) {
               jedis.auth(this.settings.sanitizedPassword());
            }

            if (this.settings.database() != 0) {
               jedis.select(this.settings.database());
            }

            this.pubSub = new JedisPubSub() {
               {
                  Objects.requireNonNull(WebAdminIntegrationService.this);
                  Objects.requireNonNull(WebAdminIntegrationService.this);
               }

               public void onMessage(String channel, String payload) {
                  WebAdminIntegrationService.this.handleCommand(payload);
               }
            };
            String channel = "lf:commands:" + this.serverId;
            this.slf4jLogger.info("Subscribing to Redis webadmin channel '{}'", channel);
            jedis.subscribe(this.pubSub, channel);
         } catch (Exception var71) {
            if (this.running) {
               this.slf4jLogger.warn("WebAdmin Redis subscription failed (retrying in 5s): {}", var71.getMessage());

               try {
                  Thread.sleep(5000L);
                  continue;
               } catch (InterruptedException var61) {
                  Thread.currentThread().interrupt();
               }
            }
            break;
         }
      }
   }

   private void handleCommand(String command) {
      try {
         this.proxyServer.getCommandManager().executeAsync(this.proxyServer.getConsoleCommandSource(), command);
      } catch (Exception var3) {
         this.slf4jLogger.warn("[WebAdmin] Failed to execute proxy command: {}", var3.getMessage());
      }
   }

   private void setupLogAppender() {
   }

   @Override
   public void close() {
      this.running = false;
      if (this.pubSub != null) {
         try {
            this.pubSub.unsubscribe();
         } catch (Exception var2) {
         }
      }

      if (this.worker != null) {
         this.worker.interrupt();
      }
   }
}
