package network.skypvp.paper.platform;

import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Paper/Folia translation layer. All scheduling and thread-sensitive Bukkit access should go through this
 * interface so runtime differences live in {@link PlatformScheduler} only.
 */
public interface ServerPlatform {

   PlatformKind kind();

   default boolean isFolia() {
      return this.kind() == PlatformKind.FOLIA;
   }

   void runAsync(Runnable task);

   BukkitTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

   /** Paper main thread; Folia global region thread. */
   void runGlobal(Runnable task);

   void runGlobalLater(Runnable task, long delayTicks);

   PlatformTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

   /** Alias for {@link #runGlobal(Runnable)}. */
   default void runSync(Runnable task) {
      this.runGlobal(task);
   }

   PlatformTask runSyncLater(Runnable task, long delayTicks);

   /** Alias for {@link #runGlobalTimer(Runnable, long, long)}. */
   default PlatformTask runSyncTimer(Runnable task, long delayTicks, long periodTicks) {
      return this.runGlobalTimer(task, delayTicks, periodTicks);
   }

   void runAtLocation(Location location, Runnable task);

   void runAtChunk(World world, int chunkX, int chunkZ, Runnable task);

   void runAtChunkLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks);

   default void runAtEntity(Entity entity, Runnable task) {
      if (entity != null) {
         this.runAtLocation(entity.getLocation(), task);
      }
   }

   void runOnPlayer(Player player, Runnable task);

   void runOnPlayerLater(Player player, Runnable task, long delayTicks);

   PlatformTask runOnPlayerTimer(Player player, Runnable task, long delayTicks, long periodTicks);

   /**
    * Runs {@code task} on each online player's correct thread (entity thread on Folia, main thread on Paper).
    */
   void runForEachPlayer(Consumer<Player> task);

   /**
    * Runs {@code task} where scoreboard/tab-list style mutations are safe (global region on Folia).
    */
   void runForEachPlayerOnGlobal(Consumer<Player> task);

   /**
    * Runs scoreboard team/objective mutations on the correct thread for the current runtime.
    */
   default void runScoreboard(Runnable task) {
      this.runGlobalScoreboard(task);
   }

   void runGlobalScoreboard(Runnable task);

   /**
    * Runs chunk/entity/block mutations owned by a world region. On Paper this executes immediately; on Folia it uses
    * the region scheduler for {@code location}.
    */
   default void runRegionOwned(Location location, Runnable task) {
      Objects.requireNonNull(location, "location");
      Objects.requireNonNull(task, "task");
      if (this.isFolia()) {
         this.runAtLocation(location, task);
      } else {
         task.run();
      }
   }

   /**
    * Runs {@code action} for each online player within {@code radiusSquared} of {@code location},
    * using per-player threading on Folia.
    */
   void runForNearbyPlayers(Location location, double radiusSquared, Consumer<Player> action);

   /** World create/unload/load orchestration (global region on Folia). */
   default void runWorldGlobal(Runnable task) {
      this.runGlobal(task);
   }

   default void runWorldGlobalLater(Runnable task, long delayTicks) {
      this.runGlobalLater(task, delayTicks);
   }

   void shutdown();
}
