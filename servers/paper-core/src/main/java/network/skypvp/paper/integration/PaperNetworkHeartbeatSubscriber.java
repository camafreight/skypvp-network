package network.skypvp.paper.integration;

import java.util.logging.Logger;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.ServerHeartbeatEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class PaperNetworkHeartbeatSubscriber {
   private final RedisConnectionSettings settings;
   private final String heartbeatChannel;
   private final NetworkHeartbeatCache cache;
   private final Logger logger;
   private Thread worker;
   private JedisPubSub pubSub;
   private volatile boolean running;

   public PaperNetworkHeartbeatSubscriber(
      RedisConnectionSettings settings,
      String heartbeatChannel,
      NetworkHeartbeatCache cache,
      Logger logger
   ) {
      this.settings = settings;
      this.heartbeatChannel = heartbeatChannel;
      this.cache = cache;
      this.logger = logger;
   }

   public void start() {
      if (this.running) {
         return;
      }
      this.running = true;
      this.worker = new Thread(this::runLoop, "SkyPvP-paper-heartbeat-subscriber");
      this.worker.setDaemon(true);
      this.worker.start();
   }

   public void stop() {
      this.running = false;
      if (this.pubSub != null) {
         try {
            this.pubSub.unsubscribe();
         } catch (Exception ignored) {
         }
      }
      if (this.worker != null) {
         this.worker.interrupt();
      }
   }

   private void runLoop() {
      while (this.running) {
         try (Jedis jedis = new Jedis(this.settings.host(), this.settings.port())) {
            if (this.settings.password() != null && !this.settings.password().isBlank()) {
               jedis.auth(this.settings.password());
            }
            if (this.settings.database() > 0) {
               jedis.select(this.settings.database());
            }

            this.pubSub = new JedisPubSub() {
               @Override
               public void onMessage(String channel, String message) {
                  PaperNetworkHeartbeatSubscriber.this.handleMessage(message);
               }
            };
            this.logger.info("Subscribing to Redis heartbeat channel '" + this.heartbeatChannel + "'");
            jedis.subscribe(this.pubSub, this.heartbeatChannel);
         } catch (Exception ex) {
            if (this.running) {
               this.logger.warning("Heartbeat Redis subscription failed (retrying in 5s): " + ex.getMessage());
               try {
                  Thread.sleep(5000L);
               } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
               }
            }
         }
      }
   }

   private void handleMessage(String payload) {
      if (payload == null || payload.isBlank()) {
         return;
      }
      try {
         ServerHeartbeatEvent event = JsonCodec.gson().fromJson(payload, ServerHeartbeatEvent.class);
         if (event != null) {
            this.cache.apply(event);
         }
      } catch (Exception ex) {
         this.logger.warning("[HeartbeatCache] Failed to parse heartbeat: " + ex.getMessage());
      }
   }
}
