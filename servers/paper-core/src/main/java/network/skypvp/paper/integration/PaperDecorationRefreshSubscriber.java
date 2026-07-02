package network.skypvp.paper.integration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.DecorationRefreshEvent;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.RedisConnectionSettings;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class PaperDecorationRefreshSubscriber implements AutoCloseable {
   private final RedisConnectionSettings settings;
   private final String ownScope;
   private final String ownServerId;
   private final PaperCorePlugin plugin;
   private final Logger logger;
   private final AtomicBoolean npcReloadQueued = new AtomicBoolean(false);
   private final AtomicBoolean hologramReloadQueued = new AtomicBoolean(false);
   private final AtomicBoolean nametagReloadQueued = new AtomicBoolean(false);
   private volatile boolean running;
   private JedisPubSub pubSub;
   private Thread worker;

   public PaperDecorationRefreshSubscriber(RedisConnectionSettings settings, String ownScope, String ownServerId, PaperCorePlugin plugin, Logger logger) {
      this.settings = settings;
      this.ownScope = ownScope;
      this.ownServerId = ownServerId;
      this.plugin = plugin;
      this.logger = logger;
   }

   public void start() {
      if (!this.running) {
         this.running = true;
         this.worker = new Thread(this::runLoop, "SkyPvP-paper-decoration-subscriber");
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
                  Objects.requireNonNull(PaperDecorationRefreshSubscriber.this);
               }

               public void onMessage(String channel, String payload) {
                  PaperDecorationRefreshSubscriber.this.handleMessage(payload);
               }
            };
            this.logger.info("Subscribing to Redis decoration-refresh channel 'SkyPvP:network:decorations' (scope=" + this.ownScope + ")");
            jedis.subscribe(this.pubSub, "SkyPvP:network:decorations");
         } catch (Exception var7) {
            if (this.running) {
               this.logger.warning("Decoration-refresh Redis subscription failed (retrying in 5s): " + var7.getMessage());

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

   private void handleMessage(String payload) {
      try {
         DecorationRefreshEvent ev = JsonCodec.gson().fromJson(payload, DecorationRefreshEvent.class);
         if (ev == null || ev.scope() == null) {
            return;
         }

         if (this.ownServerId != null && this.ownServerId.equalsIgnoreCase(ev.originServerId())) {
            return;
         }

         String kind = ev.kind() == null ? "" : ev.kind();
         if ("nametag".equalsIgnoreCase(kind)) {
            if (!this.shouldReloadForScope(ev.scope())) {
               return;
            }
            this.queueReload(this.nametagReloadQueued, this.plugin::reloadNametagDecorations);
            return;
         }

         if (this.ownScope == null || !this.ownScope.equalsIgnoreCase(ev.scope())) {
            return;
         }

         if ("hologram".equalsIgnoreCase(kind)) {
            this.queueReload(this.hologramReloadQueued, this.plugin::reloadHologramDecorations);
         } else if ("npc".equalsIgnoreCase(kind)) {
            this.queueReload(this.npcReloadQueued, this.plugin::reloadNpcDecorations);
         } else {
            this.queueReload(this.npcReloadQueued, this.plugin::reloadNpcDecorations);
            this.queueReload(this.hologramReloadQueued, this.plugin::reloadHologramDecorations);
         }
      } catch (Exception var4) {
         this.logger.warning("[Decorations] Failed to handle Redis message: " + var4.getMessage());
      }
   }

   /** Global nametag edits must fan out to every backend; scoped edits only hit matching servers. */
   private boolean shouldReloadForScope(String eventScope) {
      if (eventScope == null || eventScope.isBlank()) {
         return false;
      }
      if ("global".equalsIgnoreCase(eventScope)) {
         return true;
      }
      return this.ownScope != null && this.ownScope.equalsIgnoreCase(eventScope);
   }

   private void queueReload(AtomicBoolean guard, Runnable reload) {
      if (guard.compareAndSet(false, true)) {
         this.plugin.platformScheduler().runGlobal( () -> {
            guard.set(false);
            reload.run();
         });
      }
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
