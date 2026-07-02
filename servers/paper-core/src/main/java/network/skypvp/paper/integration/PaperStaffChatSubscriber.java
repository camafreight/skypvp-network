package network.skypvp.paper.integration;



import java.util.Objects;

import java.util.logging.Logger;

import network.skypvp.paper.PaperCorePlugin;

import network.skypvp.paper.chat.ChatFormatService;

import network.skypvp.shared.JsonCodec;

import network.skypvp.shared.RedisConnectionSettings;

import network.skypvp.shared.StaffChatEvent;

import network.skypvp.shared.chat.ChatFormatScope;

import org.bukkit.entity.Player;

import redis.clients.jedis.Jedis;

import redis.clients.jedis.JedisPubSub;



public final class PaperStaffChatSubscriber implements AutoCloseable {

   private final RedisConnectionSettings settings;

   private final String ownServerId;

   private final PaperCorePlugin plugin;

   private final ChatFormatService formatService;

   private final Logger logger;

   private volatile boolean running;

   private JedisPubSub pubSub;

   private Thread worker;



   public PaperStaffChatSubscriber(

           RedisConnectionSettings settings,

           String ownServerId,

           PaperCorePlugin plugin,

           ChatFormatService formatService,

           Logger logger

   ) {

      this.settings = settings;

      this.ownServerId = ownServerId;

      this.plugin = plugin;

      this.formatService = formatService;

      this.logger = logger;

   }



   public void start() {

      if (!this.running) {

         this.running = true;

         this.worker = new Thread(this::runLoop, "SkyPvP-paper-staffchat-subscriber");

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

                  Objects.requireNonNull(PaperStaffChatSubscriber.this);

               }



               public void onMessage(String channel, String payload) {

                  PaperStaffChatSubscriber.this.handleMessage(payload);

               }

            };

            this.logger.info("Subscribing to Redis staff-chat channel 'SkyPvP:network:staffchat'");

            jedis.subscribe(this.pubSub, "SkyPvP:network:staffchat");

         } catch (Exception var7) {

            if (this.running) {

               this.logger.warning("StaffChat Redis subscription failed (retrying in 5s): " + var7.getMessage());



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

         StaffChatEvent ev = JsonCodec.gson().fromJson(payload, StaffChatEvent.class);

         if (ev == null) {

            return;

         }



         if (this.ownServerId.equalsIgnoreCase(ev.serverId())) {

            return;

         }



         String senderName = ev.senderName() == null ? "Staff" : ev.senderName();

         String plainMsg = ev.message() == null ? "" : ev.message();

         this.plugin.platformScheduler().runGlobal(() -> {

            var staffLine = this.formatService.renderRemote(senderName, ChatFormatScope.STAFF, plainMsg);

            for (Player p : this.plugin.getServer().getOnlinePlayers()) {

               if (p.hasPermission("skypvp.staff")) {

                  p.sendMessage(staffLine);

               }

            }



            this.plugin.getServer().getConsoleSender().sendMessage(staffLine);

         });

      } catch (Exception var6) {

         this.logger.warning("[StaffChat] Failed to handle Redis message: " + var6.getMessage());

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


