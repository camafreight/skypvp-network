package network.skypvp.paper.service;

import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.RedisConnectionSettings;
import org.bukkit.Bukkit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class WebAdminIntegrationService implements AutoCloseable {
   private final RedisConnectionSettings settings;
   private final String serverId;
   private final PaperCorePlugin plugin;
   private volatile boolean running;
   private JedisPubSub pubSub;
   private Thread worker;

   public WebAdminIntegrationService(RedisConnectionSettings settings, String serverId, PaperCorePlugin plugin) {
      this.settings = settings;
      this.serverId = serverId;
      this.plugin = plugin;
   }

   public void start() {
      if (!this.running) {
         this.running = true;
         this.worker = new Thread(this::runLoop, "SkyPvP-paper-webadmin-sub");
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
               }

               public void onMessage(String channel, String payload) {
                  WebAdminIntegrationService.this.handleCommand(payload);
               }
            };
            String channel = "skypvp:commands:" + this.serverId;
            this.plugin.getLogger().info("Subscribing to Redis webadmin channel '" + channel + "'");
            jedis.subscribe(this.pubSub, channel);
         } catch (Exception var7) {
            if (this.running) {
               this.plugin.getLogger().warning("WebAdmin Redis subscription failed (retrying in 5s): " + var7.getMessage());

               try {
                  Thread.sleep(5000L);
                  continue;
               } catch (InterruptedException var6) {
                  Thread.currentThread().interrupt();
               }
            }
            break;
         }
      }
   }

   private void handleCommand(String command) {
      if (command != null && !command.isBlank()) {
         this.plugin.platformScheduler().runGlobal( () -> {
            try {
               Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception var3) {
               this.plugin.getLogger().warning("[WebAdmin] Failed to execute console command: " + var3.getMessage());
            }
         });
      }
   }

   @Override
   public void close() {
      this.running = false;
      if (this.pubSub != null && this.pubSub.isSubscribed()) {
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
