package network.skypvp.paper.platform;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Default {@link ServerPlatform} implementation. Detects Folia at runtime and routes to the correct scheduler API.
 */
public final class PlatformScheduler implements ServerPlatform {
   private static final long MS_PER_TICK = 50L;
   private final Plugin plugin;
   private final boolean folia;
   private final ScheduledExecutorService foliaAsyncExecutor;
   private final ExecutorService foliaWorkExecutor;
   private final Object globalRegionScheduler;
   private final Method globalRun;
   private final Method globalRunDelayed;
   private final Method globalRunAtFixedRate;
   private final Method entityRun;
   private final Method entityRunDelayed;
   private final Method entityRunAtFixedRate;
   private final Method regionRun;
   private final Method regionRunDelayed;

   private PlatformScheduler(Plugin plugin) {
      this.plugin = Objects.requireNonNull(plugin, "plugin");
      Object scheduler = null;
      Method run = null;
      Method runDelayed = null;
      Method runAtFixedRate = null;
      Method entRun = null;
      Method entRunDelayed = null;
      Method entRunAtFixedRate = null;
      Method regRun = null;
      Method regRunDelayed = null;
      boolean foliaDetected = false;

      try {
         Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
         foliaDetected = true;
         scheduler = plugin.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
         Class<?> schedulerClass = scheduler.getClass();
         run = schedulerClass.getMethod("run", Plugin.class, Consumer.class);
         runDelayed = schedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
         runAtFixedRate = schedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
         entRun = Entity.class.getMethod("getScheduler");
         Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
         entRunDelayed = entitySchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
         entRunAtFixedRate = entitySchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
         Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
         regRun = regionSchedulerClass.getMethod("run", Plugin.class, Location.class, Consumer.class);
         regRunDelayed = regionSchedulerClass.getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
      } catch (ReflectiveOperationException ignored) {
         foliaDetected = false;
      }

      this.folia = foliaDetected;
      this.globalRegionScheduler = scheduler;
      this.globalRun = run;
      this.globalRunDelayed = runDelayed;
      this.globalRunAtFixedRate = runAtFixedRate;
      this.entityRun = entRun;
      this.entityRunDelayed = entRunDelayed;
      this.entityRunAtFixedRate = entRunAtFixedRate;
      this.regionRun = regRun;
      this.regionRunDelayed = regRunDelayed;
      this.foliaAsyncExecutor = foliaDetected
         ? Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, plugin.getName() + "-async");
            thread.setDaemon(true);
            return thread;
         })
         : null;
      // One-off async work (DB queries, blocking world-copy I/O, command handlers) runs on a
      // separate elastic pool so a few blocking tasks can't starve everything. The scheduled pool
      // above is reserved for periodic async timers only.
      this.foliaWorkExecutor = foliaDetected
         ? Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
               Thread thread = new Thread(runnable, plugin.getName() + "-work-" + this.counter.incrementAndGet());
               thread.setDaemon(true);
               return thread;
            }
         })
         : null;
   }

   public static PlatformScheduler create(Plugin plugin) {
      return new PlatformScheduler(plugin);
   }

   @Override
   public PlatformKind kind() {
      return this.folia ? PlatformKind.FOLIA : PlatformKind.PAPER;
   }

   @Override
   public boolean isFolia() {
      return this.folia;
   }

   @Override
   public void runForNearbyPlayers(Location location, double radiusSquared, Consumer<Player> action) {
      Objects.requireNonNull(location, "location");
      Objects.requireNonNull(action, "action");
      World world = location.getWorld();
      if (world == null) {
         return;
      }
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!player.getWorld().equals(world) || player.getLocation().distanceSquared(location) > radiusSquared) {
            continue;
         }
         if (this.folia) {
            this.runOnPlayer(player, () -> action.accept(player));
         } else {
            action.accept(player);
         }
      }
   }

   @Override
   public void runAtEntity(Entity entity, Runnable task) {
      Objects.requireNonNull(task, "task");
      if (entity == null) {
         return;
      }
      this.runAtLocation(entity.getLocation(), task);
   }

   @Override
   public void runForEachPlayer(Consumer<Player> task) {
      Objects.requireNonNull(task, "task");
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (this.folia) {
            this.runOnPlayer(player, () -> task.accept(player));
         } else {
            task.accept(player);
         }
      }
   }

   /**
    * Runs per-player work on the owning region thread (Folia) or main thread (Paper).
    * Required for scoreboard/tab-list mutations tied to a player.
    */
   public void runForEachPlayerOnGlobal(Consumer<Player> task) {
      Objects.requireNonNull(task, "task");
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (this.folia) {
            this.runAtLocation(player.getLocation(), () -> task.accept(player));
         } else {
            task.accept(player);
         }
      }
   }

   public void runGlobalScoreboard(Runnable task) {
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         this.runGlobal(task);
      } else {
         task.run();
      }
   }

   public void runAsync(Runnable task) {
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         this.foliaWorkExecutor.execute(task);
      } else {
         this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, task);
      }
   }

   public void runGlobal(Runnable task) {
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         this.invokeGlobal(this.globalRun, task, 0L, 0L, false);
      } else {
         this.plugin.getServer().getScheduler().runTask(this.plugin, task);
      }
   }

   public void runGlobalLater(Runnable task, long delayTicks) {
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         this.invokeGlobal(this.globalRunDelayed, task, delayTicks, 0L, false);
      } else {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, task, delayTicks);
      }
   }

   public PlatformTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
      Objects.requireNonNull(task, "task");
      AtomicBoolean cancelled = new AtomicBoolean(false);
      Runnable wrapped = () -> {
         if (!cancelled.get()) {
            task.run();
         }
      };
      PlatformTask handle = new PlatformTask(cancelled);
      if (this.folia) {
         this.invokeGlobal(this.globalRunAtFixedRate, wrapped, delayTicks, periodTicks, true);
      } else {
         handle.bindPaperTask(this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, wrapped, delayTicks, periodTicks));
      }
      return handle;
   }

   public void runOnPlayer(Player player, Runnable task) {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         try {
            Object entityScheduler = this.entityRun.invoke(player);
            entityScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class)
               .invoke(entityScheduler, this.plugin, task, null, 1L);
         } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to schedule player task on Folia", ex);
         }
      } else {
         this.plugin.getServer().getScheduler().runTask(this.plugin, task);
      }
   }

   public void runOnPlayerLater(Player player, Runnable task, long delayTicks) {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         try {
            Object entityScheduler = this.entityRun.invoke(player);
            Consumer<Object> consumer = ignored -> task.run();
            this.entityRunDelayed.invoke(entityScheduler, this.plugin, consumer, null, delayTicks);
         } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to schedule delayed player task on Folia", ex);
         }
      } else {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, task, delayTicks);
      }
   }

   /** Paper main thread, Folia global region. Use for cross-world orchestration only. */
   public void runSync(Runnable task) {
      this.runGlobal(task);
   }

   public PlatformTask runSyncLater(Runnable task, long delayTicks) {
      Objects.requireNonNull(task, "task");
      AtomicBoolean cancelled = new AtomicBoolean(false);
      PlatformTask handle = new PlatformTask(cancelled);
      Runnable wrapped = () -> {
         if (!cancelled.get()) {
            task.run();
         }
      };
      if (this.folia) {
         this.invokeGlobal(this.globalRunDelayed, wrapped, delayTicks, 0L, false);
      } else {
         handle.bindPaperTask(this.plugin.getServer().getScheduler().runTaskLater(this.plugin, wrapped, delayTicks));
      }
      return handle;
   }

   public PlatformTask runSyncTimer(Runnable task, long delayTicks, long periodTicks) {
      return this.runGlobalTimer(task, delayTicks, periodTicks);
   }

   public PlatformTask runOnPlayerTimer(Player player, Runnable task, long delayTicks, long periodTicks) {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(task, "task");
      AtomicBoolean cancelled = new AtomicBoolean(false);
      Runnable wrapped = () -> {
         if (!cancelled.get()) {
            task.run();
         }
      };
      PlatformTask handle = new PlatformTask(cancelled);
      if (this.folia) {
         try {
            Object entityScheduler = this.entityRun.invoke(player);
            Consumer<Object> consumer = ignored -> wrapped.run();
            this.entityRunAtFixedRate.invoke(entityScheduler, this.plugin, consumer, null, delayTicks, periodTicks);
         } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to schedule player timer on Folia", ex);
         }
      } else {
         handle.bindPaperTask(this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, wrapped, delayTicks, periodTicks));
      }
      return handle;
   }

   public void runAtLocation(Location location, Runnable task) {
      Objects.requireNonNull(location, "location");
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         try {
            Object regionScheduler = plugin.getServer().getClass().getMethod("getRegionScheduler").invoke(plugin.getServer());
            Consumer<Object> consumer = ignored -> task.run();
            this.regionRun.invoke(regionScheduler, this.plugin, location, consumer);
         } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to schedule region task on Folia", ex);
         }
      } else {
         this.plugin.getServer().getScheduler().runTask(this.plugin, task);
      }
   }

   /**
    * Schedules work owned by a world region (chunk block/tile mutations, entities in that region).
    * On Folia this uses the region scheduler; on Paper it runs on the main thread.
    */
   public void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(task, "task");
      Location anchor = new Location(world, (chunkX << 4) + 8.0, world.getMinHeight() + 1.0, (chunkZ << 4) + 8.0);
      this.runAtLocation(anchor, task);
   }

   public void runAtChunkLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(task, "task");
      Location anchor = new Location(world, (chunkX << 4) + 8.0, world.getMinHeight() + 1.0, (chunkZ << 4) + 8.0);
      if (this.folia) {
         try {
            Object regionScheduler = plugin.getServer().getClass().getMethod("getRegionScheduler").invoke(plugin.getServer());
            Consumer<Object> consumer = ignored -> task.run();
            this.regionRunDelayed.invoke(regionScheduler, this.plugin, anchor, consumer, delayTicks);
         } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to schedule delayed region task on Folia", ex);
         }
      } else {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, task, delayTicks);
      }
   }

   /**
    * World lifecycle (create/unload/load) must run on the global region in Folia.
    * Other breach instances continue ticking on their own region threads while this runs.
    */
   public void runWorldGlobal(Runnable task) {
      this.runGlobal(task);
   }

   public void runWorldGlobalLater(Runnable task, long delayTicks) {
      this.runGlobalLater(task, delayTicks);
   }

   public BukkitTask runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
      Objects.requireNonNull(task, "task");
      if (this.folia) {
         ScheduledFuture<?> future = this.foliaAsyncExecutor.scheduleAtFixedRate(
            task,
            Math.max(0L, delayTicks) * MS_PER_TICK,
            Math.max(1L, periodTicks) * MS_PER_TICK,
            TimeUnit.MILLISECONDS
         );
         return new FoliaAsyncBukkitTask(future);
      }
      return this.plugin.getServer().getScheduler().runTaskTimerAsynchronously(this.plugin, task, delayTicks, periodTicks);
   }

   public void shutdown() {
      if (this.foliaAsyncExecutor != null) {
         this.foliaAsyncExecutor.shutdownNow();
      }
      if (this.foliaWorkExecutor != null) {
         this.foliaWorkExecutor.shutdownNow();
      }
   }

   private static final class FoliaAsyncBukkitTask implements BukkitTask {
      private final ScheduledFuture<?> future;

      private FoliaAsyncBukkitTask(ScheduledFuture<?> future) {
         this.future = future;
      }

      @Override
      public int getTaskId() {
         return -1;
      }

      @Override
      public Plugin getOwner() {
         return null;
      }

      @Override
      public boolean isSync() {
         return false;
      }

      @Override
      public boolean isCancelled() {
         return this.future.isCancelled();
      }

      @Override
      public void cancel() {
         this.future.cancel(false);
      }
   }

   private void invokeGlobal(Method method, Runnable task, long delay, long period, boolean fixedRate) {
      if (method == null || this.globalRegionScheduler == null) {
         this.plugin.getServer().getScheduler().runTask(this.plugin, task);
         return;
      }
      try {
         Consumer<Object> consumer = ignored -> task.run();
         if (fixedRate) {
            method.invoke(this.globalRegionScheduler, this.plugin, consumer, delay, period);
         } else if (delay > 0L) {
            method.invoke(this.globalRegionScheduler, this.plugin, consumer, delay);
         } else {
            method.invoke(this.globalRegionScheduler, this.plugin, consumer);
         }
      } catch (ReflectiveOperationException ex) {
         throw new IllegalStateException("Failed to schedule global task on Folia", ex);
      }
   }
}
