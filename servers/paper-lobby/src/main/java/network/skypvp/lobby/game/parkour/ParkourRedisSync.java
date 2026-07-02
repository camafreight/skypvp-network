package network.skypvp.lobby.game.parkour;

import com.google.gson.Gson;
import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.JedisPubSub;

public class ParkourRedisSync {
   private final JavaPlugin plugin;
   private final PaperCorePlugin core;
   private final ParkourManager parkourManager;
   private final Gson gson = new Gson();
   private static final String CHANNEL = "network:lobby:parkour:sync";

   public ParkourRedisSync(JavaPlugin plugin, PaperCorePlugin core, ParkourManager parkourManager) {
      this.plugin = plugin;
      this.core = core;
      this.parkourManager = parkourManager;
      this.startSubscriber();
   }

   private void startSubscriber() {
      this.core.platform().runAsync(() -> {
         try {
            this.core
               .redisPublisher()
               .getJedis()
               .subscribe(
                  new JedisPubSub() {
                     {
                        Objects.requireNonNull(ParkourRedisSync.this);
                     }

                     public void onMessage(String channel, String message) {
                        if ("network:lobby:parkour:sync".equals(channel)) {
                           ParkourTrack track = (ParkourTrack)ParkourRedisSync.this.gson.fromJson(message, ParkourTrack.class);
                           ParkourRedisSync.this.core
                              .platform()
                              .runSync(() -> ParkourRedisSync.this.parkourManager.updateTrackLocal(track));
                        }
                     }
                  },
                  new String[]{"network:lobby:parkour:sync"}
               );
         } catch (Exception var2) {
            this.plugin.getLogger().warning("Failed to subscribe to parkour redis channel: " + var2.getMessage());
         }
      });
   }

   public void publishTrackUpdate(ParkourTrack track) {
      this.core.platform().runAsync(() -> {
         try {
            this.core.redisPublisher().publishJson("network:lobby:parkour:sync", track);
         } catch (Exception var3) {
            this.plugin.getLogger().warning("Failed to publish parkour track update: " + var3.getMessage());
         }
      });
   }
}
