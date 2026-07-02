package network.skypvp.paper.service;

import network.skypvp.paper.PaperCorePlugin;

public final class GracefulDrainService {
   private final PaperCorePlugin plugin;
   private volatile boolean draining;

   public GracefulDrainService(PaperCorePlugin plugin) {
      this.plugin = plugin;
      Runtime.getRuntime().addShutdownHook(new Thread(this::beginDrain, "SkyPvP-graceful-drain"));
   }

   public void beginDrain() {
      if (!this.draining) {
         this.draining = true;
         this.plugin.getLogger().info("Graceful drain engaged: refusing new joins while players migrate away.");
         this.plugin.publishNotJoinableHeartbeatNow();
         if (this.plugin.redisPublisher() != null) {
            this.plugin.redisPublisher().publishJson(network.skypvp.shared.NetworkChannels.SERVER_DRAINING, new network.skypvp.shared.ServerDrainEvent(this.plugin.serverId(), System.currentTimeMillis()));
         }
      }
   }

   public boolean isDraining() {
      return this.draining;
   }
}
