package network.skypvp.lobby.task;

import network.skypvp.paper.library.WorldGroundItemCleanup;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class LobbyWorldMaintenanceTask implements Runnable {
   private final JavaPlugin plugin;
   private final Location spawn;
   private final long fixedTime;

   public LobbyWorldMaintenanceTask(JavaPlugin plugin, Location spawn, int ignoredRadius, long fixedTime) {
      this.plugin = plugin;
      this.spawn = spawn;
      this.fixedTime = fixedTime;
   }

   @Override
   public void run() {
      World world = this.spawn.getWorld();
      if (world != null) {
         world.setStorm(false);
         world.setThundering(false);
         world.setWeatherDuration(6000);
         world.setTime(this.fixedTime);
         int removed = WorldGroundItemCleanup.clearGroundItems(world);
         if (removed > 0) {
            this.plugin.getLogger().fine("[LobbyMaintenance] Removed " + removed + " dropped entities in lobby world.");
         }
      }
   }
}
