package network.skypvp.paper.service;

import network.skypvp.shared.ServerTextUtil;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import network.skypvp.paper.platform.PlatformTask;

public final class PerformanceMonitorService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final boolean PERFORMANCE_MONITOR_ENABLED = true;
   private static final long SAMPLE_INTERVAL_TICKS = 100L;
   private static final double LOW_TPS_THRESHOLD = 17.0;
   private static final double HIGH_MSPT_THRESHOLD = 55.0;
   private static final int SEVERE_CONSECUTIVE_SAMPLES = 3;
   private static final long ALERT_COOLDOWN_SECONDS = 120L;
   private static final int RECENT_EVENTS_LIMIT = 12;
   private final PaperCorePlugin plugin;
   private final boolean enabled;
   private final long sampleIntervalTicks;
   private final double lowTpsThreshold;
   private final double highMsptThreshold;
   private final int severeConsecutiveSamples;
   private final long alertCooldownMillis;
   private final int recentEventsLimit;
   private final Deque<String> recentEvents;
   private PerformanceMonitorService.Snapshot latestSnapshot = PerformanceMonitorService.Snapshot.empty();
   private PlatformTask sampleTask;
   private int severeStreak;
   private long lastAlertEpochMillis;
   private long lastGcTimeTotal = -1;
   private int providerChurnCounter = 0;

   public PerformanceMonitorService(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.enabled = PERFORMANCE_MONITOR_ENABLED;
      this.sampleIntervalTicks = Math.max(40L, SAMPLE_INTERVAL_TICKS);
      this.lowTpsThreshold = LOW_TPS_THRESHOLD;
      this.highMsptThreshold = HIGH_MSPT_THRESHOLD;
      this.severeConsecutiveSamples = Math.max(1, SEVERE_CONSECUTIVE_SAMPLES);
      this.alertCooldownMillis = Math.max(15000L, ALERT_COOLDOWN_SECONDS * 1000L);
      this.recentEventsLimit = Math.max(5, RECENT_EVENTS_LIMIT);
      this.recentEvents = new ArrayDeque<>(this.recentEventsLimit);
   }

   public void start() {
      if (!this.enabled) {
         this.plugin.getLogger().info("[Perf] Monitor disabled.");
      } else {
         this.sampleTask = this.plugin.platformScheduler().runGlobalTimer(this::captureSample, this.sampleIntervalTicks, this.sampleIntervalTicks);
         this.plugin
            .getLogger()
            .info(
               "[Perf] Monitoring started; interval="
                  + this.sampleIntervalTicks
                  + " ticks, thresholds tps<"
                  + this.lowTpsThreshold
                  + " mspt>"
                  + this.highMsptThreshold
                  + "."
            );
      }
   }

   public void stop() {
      if (this.sampleTask != null) {
         this.sampleTask.cancel();
         this.sampleTask = null;
      }
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public PerformanceMonitorService.Snapshot latestSnapshot() {
      return this.latestSnapshot;
   }

   public List<String> recentEvents() {
      return new ArrayList<>(this.recentEvents);
   }

   public void clearEvents(CommandSender sender) {
      this.recentEvents.clear();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Cleared lag event history.</#888888>"));
   }

   public void incrementProviderChurn() {
      this.providerChurnCounter++;
   }

   private void captureSample() {
      double tps = this.readTps();
      double mspt = this.readMspt();
      Runtime rt = Runtime.getRuntime();
      long used = rt.totalMemory() - rt.freeMemory();
      long max = rt.maxMemory();
      int players = this.plugin.getServer().getOnlinePlayers().size();
      boolean tpsBad = tps > 0.0 && tps < this.lowTpsThreshold;
      boolean msptBad = mspt > 0.0 && mspt > this.highMsptThreshold;
      boolean severe = tpsBad || msptBad;
      if (severe) {
         this.severeStreak++;
      } else {
         this.severeStreak = 0;
      }

      long currentGcTotal = this.readTotalGcTime();
      long gcPressureMs = this.lastGcTimeTotal == -1 ? 0 : currentGcTotal - this.lastGcTimeTotal;
      this.lastGcTimeTotal = currentGcTotal;
      
      int churn = this.providerChurnCounter;
      this.providerChurnCounter = 0;

      this.latestSnapshot = new PerformanceMonitorService.Snapshot(tps, mspt, used, max, players, severe, this.severeStreak, gcPressureMs, churn, Instant.now());
      if (this.severeStreak >= this.severeConsecutiveSamples) {
         this.maybeAlertStaff();
      }
   }

   private long readTotalGcTime() {
      long total = 0;
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
         long time = gc.getCollectionTime();
         if (time > 0) {
            total += time;
         }
      }
      return total;
   }

   private void maybeAlertStaff() {
      long now = System.currentTimeMillis();
      if (now - this.lastAlertEpochMillis >= this.alertCooldownMillis) {
         this.lastAlertEpochMillis = now;
         String event = "lag detected: tps="
            + this.fmt(this.latestSnapshot.tps())
            + " mspt="
            + this.fmt(this.latestSnapshot.mspt())
            + " players="
            + this.latestSnapshot.players()
            + " streak="
            + this.latestSnapshot.severeStreak();
         this.pushEvent(event);
         String message = "<#FF5555><bold>[Lag Alert]</bold></#FF5555> <#888888>TPS=<#FFD700>"
            + this.fmt(this.latestSnapshot.tps())
            + "</#FFD700> MSPT=<#FFD700>"
            + this.fmt(this.latestSnapshot.mspt())
            + "</#FFD700> GC=<#FFD700>"
            + this.latestSnapshot.gcPressureMs()
            + "ms</#FFD700> Churn=<#FFD700>"
            + this.latestSnapshot.providerChurnRate()
            + "/s</#FFD700> players=<#FFD700>"
            + this.latestSnapshot.players()
            + "</#FFD700>. Use <#FFFFFF>/lag</#FFFFFF> for details.</#888888>";

         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("skypvp.staff")) {
               player.sendMessage(ServerTextUtil.miniMessageComponent(message));
            }
         }

         this.plugin.getLogger().warning("[Perf] " + event);
      }
   }

   private void pushEvent(String event) {
      if (this.recentEvents.size() >= this.recentEventsLimit) {
         this.recentEvents.removeFirst();
      }

      this.recentEvents.addLast(event);
   }

   private double readTps() {
      try {
         Method method = this.plugin.getServer().getClass().getMethod("getTPS");
         if (method.invoke(this.plugin.getServer()) instanceof double[] arr && arr.length > 0) {
            return arr[0];
         }
      } catch (ReflectiveOperationException var6) {
      }

      try {
         Object spigot = this.plugin.getServer().spigot();
         Method method = spigot.getClass().getMethod("getTPS");
         if (method.invoke(spigot) instanceof double[] arr && arr.length > 0) {
            return arr[0];
         }
      } catch (ReflectiveOperationException var5) {
      }

      return -1.0;
   }

   private double readMspt() {
      try {
         Method method = this.plugin.getServer().getClass().getMethod("getAverageTickTime");
         if (method.invoke(this.plugin.getServer()) instanceof Number number) {
            return number.doubleValue();
         }
      } catch (ReflectiveOperationException var4) {
      }

      return -1.0;
   }

   private String fmt(double value) {
      return value < 0.0 ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
   }

   public static record Snapshot(
      double tps, double mspt, long usedMemoryBytes, long maxMemoryBytes, int players, boolean severe, int severeStreak, long gcPressureMs, int providerChurnRate, Instant capturedAt
   ) {
      public static PerformanceMonitorService.Snapshot empty() {
         return new PerformanceMonitorService.Snapshot(-1.0, -1.0, 0L, 0L, 0, false, 0, 0L, 0, Instant.EPOCH);
      }

      public long memoryUsagePercent() {
         return this.maxMemoryBytes <= 0L ? 0L : Math.max(0L, Math.min(100L, this.usedMemoryBytes * 100L / this.maxMemoryBytes));
      }
   }
}
