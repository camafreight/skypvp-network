package network.skypvp.paper.clientupdate;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Single coalescing drain for client-bound HUD/FX work on Folia.
 *
 * <p>Producers enqueue intents; a 1-tick global drain emits on the correct affinity
 * ({@code runOnPlayer} for action/boss/title, {@code runAtLocation} for scoreboard/tab,
 * {@code runAtChunk} for world FX).</p>
 */
public final class ClientUpdatePipeline {

   public static final int PRIORITY_NORMAL = 0;
   public static final int PRIORITY_OVERRIDE = 10;
   public static final int PRIORITY_FLASH = 5;

   private static final int MAX_DISPLAY_FX_PER_TICK = 48;
   private static final int MAX_PARTICLE_UNITS_PER_TICK = 120;
   private static final int MAX_SOUND_PER_TICK = 40;
   private static final int MAX_BLOCK_CHANGE_PER_TICK = 24;
   private static final int MAX_FX_QUEUE = 1024;

   private final PaperCorePlugin plugin;
   private final ConcurrentHashMap<UUID, ActionBarIntent> pendingActionBars = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Boolean> pendingBossBars = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Boolean> pendingScoreboards = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Boolean> pendingTabs = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, TitleIntent> pendingTitles = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, FxIntent> pendingFx = new ConcurrentHashMap<>();

   private final EnumMap<UpdateChannel, LongAdder> enqueued = new EnumMap<>(UpdateChannel.class);
   private final EnumMap<UpdateChannel, LongAdder> coalesced = new EnumMap<>(UpdateChannel.class);
   private final EnumMap<UpdateChannel, LongAdder> emitted = new EnumMap<>(UpdateChannel.class);
   private final EnumMap<UpdateChannel, LongAdder> dropped = new EnumMap<>(UpdateChannel.class);

   private final AtomicInteger displayBudget = new AtomicInteger();
   private final AtomicInteger particleBudget = new AtomicInteger();
   private final AtomicInteger soundBudget = new AtomicInteger();
   private final AtomicInteger blockBudget = new AtomicInteger();
   private final AtomicLong drainTicks = new AtomicLong();
   private final AtomicLong fxSequence = new AtomicLong();

   private PlatformTask drainTask;

   public ClientUpdatePipeline(PaperCorePlugin plugin) {
      this.plugin = plugin;
      for (UpdateChannel channel : UpdateChannel.values()) {
         this.enqueued.put(channel, new LongAdder());
         this.coalesced.put(channel, new LongAdder());
         this.emitted.put(channel, new LongAdder());
         this.dropped.put(channel, new LongAdder());
      }
   }

   public void start() {
      if (this.drainTask != null) {
         return;
      }
      this.drainTask = this.plugin.platformScheduler().runGlobalTimer(this::drain, 1L, 1L);
      this.plugin.getLogger().info("[ClientUpdate] Pipeline drain started (1 tick).");
   }

   public void stop() {
      if (this.drainTask != null) {
         this.drainTask.cancel();
         this.drainTask = null;
      }
      this.pendingActionBars.clear();
      this.pendingBossBars.clear();
      this.pendingScoreboards.clear();
      this.pendingTabs.clear();
      this.pendingTitles.clear();
      this.pendingFx.clear();
   }

   public void offerActionBar(Player player, Component content, int priority) {
      if (player == null || content == null || !player.isOnline()) {
         return;
      }
      UUID id = player.getUniqueId();
      this.enqueued.get(UpdateChannel.ACTION_BAR).increment();
      this.pendingActionBars.compute(id, (key, previous) -> {
         if (previous != null) {
            this.coalesced.get(UpdateChannel.ACTION_BAR).increment();
            if (previous.priority() > priority) {
               return previous;
            }
         }
         return new ActionBarIntent(content, priority);
      });
   }

   public void offerBossBarRefresh(Player player) {
      this.offerHudFlag(UpdateChannel.BOSS_BAR, player, this.pendingBossBars);
   }

   public void offerScoreboardRefresh(Player player) {
      this.offerHudFlag(UpdateChannel.SCOREBOARD, player, this.pendingScoreboards);
   }

   public void offerTabRefresh(Player player) {
      this.offerHudFlag(UpdateChannel.TAB, player, this.pendingTabs);
   }

   public void offerTitle(Player player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut, int priority) {
      if (player == null || !player.isOnline()) {
         return;
      }
      UUID id = player.getUniqueId();
      this.enqueued.get(UpdateChannel.TITLE).increment();
      TitleIntent next = new TitleIntent(
            title == null ? Component.empty() : title,
            subtitle == null ? Component.empty() : subtitle,
            fadeIn,
            stay,
            fadeOut,
            priority
      );
      this.pendingTitles.compute(id, (key, previous) -> {
         if (previous != null) {
            this.coalesced.get(UpdateChannel.TITLE).increment();
            if (previous.priority() > priority) {
               return previous;
            }
         }
         return next;
      });
   }

   /**
    * Budget gate for synchronous FX (damage floats, extract particles). Resets each drain tick.
    */
   public boolean tryAcquire(UpdateChannel channel, int units) {
      if (!this.reserveBudget(channel, units)) {
         this.dropped.get(channel).increment();
         return false;
      }
      this.enqueued.get(channel).increment();
      this.emitted.get(channel).increment();
      return true;
   }

   /** Reserve budget without touching enqueue/emit counters (queued FX path). */
   private boolean reserveBudget(UpdateChannel channel, int units) {
      if (units <= 0) {
         return true;
      }
      AtomicInteger budget = this.budgetCounter(channel);
      int max = this.budgetMax(channel);
      if (budget == null || max <= 0) {
         return true;
      }
      for (;;) {
         int current = budget.get();
         if (current + units > max) {
            return false;
         }
         if (budget.compareAndSet(current, current + units)) {
            return true;
         }
      }
   }

   /**
    * Queue chunk-affine FX for the next drain. Last intent per (world,chunk,channel) wins.
    */
   public void offerFx(UpdateChannel channel, World world, int chunkX, int chunkZ, Runnable emit) {
      if (channel == null || channel.isHud() || world == null || emit == null) {
         return;
      }
      if (this.pendingFx.size() >= MAX_FX_QUEUE) {
         this.dropped.get(channel).increment();
         return;
      }
      this.enqueued.get(channel).increment();
      long key = fxKey(world.getUID(), chunkX, chunkZ, channel);
      FxIntent next = new FxIntent(channel, world.getUID(), chunkX, chunkZ, emit, this.fxSequence.incrementAndGet());
      this.pendingFx.compute(key, (ignored, previous) -> {
         if (previous != null) {
            this.coalesced.get(channel).increment();
         }
         return next;
      });
   }

   public void recordDrop(UpdateChannel channel) {
      if (channel != null) {
         this.dropped.get(channel).increment();
      }
   }

   public ClientUpdateStats snapshotStats() {
      EnumMap<UpdateChannel, long[]> map = new EnumMap<>(UpdateChannel.class);
      for (UpdateChannel channel : UpdateChannel.values()) {
         map.put(channel, new long[]{
               this.enqueued.get(channel).sum(),
               this.coalesced.get(channel).sum(),
               this.emitted.get(channel).sum(),
               this.dropped.get(channel).sum()
         });
      }
      return new ClientUpdateStats(map, this.drainTicks.get());
   }

   private void offerHudFlag(UpdateChannel channel, Player player, ConcurrentHashMap<UUID, Boolean> pending) {
      if (player == null || !player.isOnline()) {
         return;
      }
      this.enqueued.get(channel).increment();
      if (pending.put(player.getUniqueId(), Boolean.TRUE) != null) {
         this.coalesced.get(channel).increment();
      }
   }

   private void drain() {
      this.drainTicks.incrementAndGet();
      this.displayBudget.set(0);
      this.particleBudget.set(0);
      this.soundBudget.set(0);
      this.blockBudget.set(0);

      this.drainActionBars();
      this.drainHudFlags(UpdateChannel.BOSS_BAR, this.pendingBossBars, player -> {
         if (this.plugin.bossBarService() != null) {
            this.plugin.bossBarService().flushPlayer(player);
         }
      });
      this.drainHudFlags(UpdateChannel.SCOREBOARD, this.pendingScoreboards, player -> {
         if (this.plugin.scoreboardService() != null) {
            this.plugin.scoreboardService().flushPlayer(player);
         }
      });
      this.drainHudFlags(UpdateChannel.TAB, this.pendingTabs, player -> {
         if (this.plugin.tabListService() != null) {
            this.plugin.tabListService().flushPlayer(player);
         }
      });
      this.drainTitles();
      this.drainFx();
   }

   private void drainActionBars() {
      if (this.pendingActionBars.isEmpty()) {
         return;
      }
      Map<UUID, ActionBarIntent> batch = new ConcurrentHashMap<>(this.pendingActionBars);
      this.pendingActionBars.keySet().removeAll(batch.keySet());
      for (Map.Entry<UUID, ActionBarIntent> entry : batch.entrySet()) {
         Player player = this.plugin.getServer().getPlayer(entry.getKey());
         if (player == null || !player.isOnline()) {
            continue;
         }
         ActionBarIntent intent = entry.getValue();
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (this.plugin.actionBarService() != null) {
               this.plugin.actionBarService().emitNow(player, intent.content());
               this.emitted.get(UpdateChannel.ACTION_BAR).increment();
            }
         });
      }
   }

   private void drainHudFlags(UpdateChannel channel, ConcurrentHashMap<UUID, Boolean> pending, java.util.function.Consumer<Player> flush) {
      if (pending.isEmpty()) {
         return;
      }
      Map<UUID, Boolean> batch = new ConcurrentHashMap<>(pending);
      pending.keySet().removeAll(batch.keySet());
      boolean locationAffinity = channel == UpdateChannel.SCOREBOARD || channel == UpdateChannel.TAB;
      for (UUID id : batch.keySet()) {
         Player player = this.plugin.getServer().getPlayer(id);
         if (player == null || !player.isOnline()) {
            continue;
         }
         if (locationAffinity) {
            this.plugin.platformScheduler().runAtLocation(player.getLocation(), () -> {
               flush.accept(player);
               this.emitted.get(channel).increment();
            });
         } else {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
               flush.accept(player);
               this.emitted.get(channel).increment();
            });
         }
      }
   }

   private void drainTitles() {
      if (this.pendingTitles.isEmpty()) {
         return;
      }
      Map<UUID, TitleIntent> batch = new ConcurrentHashMap<>(this.pendingTitles);
      this.pendingTitles.keySet().removeAll(batch.keySet());
      for (Map.Entry<UUID, TitleIntent> entry : batch.entrySet()) {
         Player player = this.plugin.getServer().getPlayer(entry.getKey());
         if (player == null || !player.isOnline()) {
            continue;
         }
         TitleIntent intent = entry.getValue();
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            player.showTitle(net.kyori.adventure.title.Title.title(
                  intent.title(),
                  intent.subtitle(),
                  net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(Math.max(0, intent.fadeIn()) * 50L),
                        java.time.Duration.ofMillis(Math.max(0, intent.stay()) * 50L),
                        java.time.Duration.ofMillis(Math.max(0, intent.fadeOut()) * 50L)
                  )
            ));
            this.emitted.get(UpdateChannel.TITLE).increment();
         });
      }
   }

   private void drainFx() {
      if (this.pendingFx.isEmpty()) {
         return;
      }
      Map<Long, FxIntent> batch = new ConcurrentHashMap<>(this.pendingFx);
      this.pendingFx.keySet().removeAll(batch.keySet());
      for (FxIntent intent : batch.values()) {
         if (!this.reserveBudget(intent.channel(), 1)) {
            this.dropped.get(intent.channel()).increment();
            continue;
         }
         World world = this.plugin.getServer().getWorld(intent.worldId());
         if (world == null) {
            this.dropped.get(intent.channel()).increment();
            continue;
         }
         this.plugin.platformScheduler().runAtChunk(world, intent.chunkX(), intent.chunkZ(), () -> {
            try {
               intent.emit().run();
               this.emitted.get(intent.channel()).increment();
            } catch (Throwable thrown) {
               this.plugin.getLogger().warning("[ClientUpdate] FX emit failed on " + intent.channel() + ": " + thrown.getMessage());
               this.dropped.get(intent.channel()).increment();
            }
         });
      }
   }

   private AtomicInteger budgetCounter(UpdateChannel channel) {
      return switch (channel) {
         case DISPLAY_FX -> this.displayBudget;
         case PARTICLE -> this.particleBudget;
         case SOUND -> this.soundBudget;
         case BLOCK_CHANGE -> this.blockBudget;
         default -> null;
      };
   }

   private int budgetMax(UpdateChannel channel) {
      return switch (channel) {
         case DISPLAY_FX -> MAX_DISPLAY_FX_PER_TICK;
         case PARTICLE -> MAX_PARTICLE_UNITS_PER_TICK;
         case SOUND -> MAX_SOUND_PER_TICK;
         case BLOCK_CHANGE -> MAX_BLOCK_CHANGE_PER_TICK;
         default -> 0;
      };
   }

   private static long fxKey(UUID worldId, int chunkX, int chunkZ, UpdateChannel channel) {
      long worldHash = worldId.getMostSignificantBits() ^ worldId.getLeastSignificantBits();
      return worldHash * 31L
            + ((long) chunkX << 32)
            + (chunkZ & 0xffffffffL)
            + (channel.ordinal() * 17L);
   }

   private record ActionBarIntent(Component content, int priority) {
   }

   private record TitleIntent(Component title, Component subtitle, int fadeIn, int stay, int fadeOut, int priority) {
   }

   private record FxIntent(UpdateChannel channel, UUID worldId, int chunkX, int chunkZ, Runnable emit, long seq) {
   }
}
