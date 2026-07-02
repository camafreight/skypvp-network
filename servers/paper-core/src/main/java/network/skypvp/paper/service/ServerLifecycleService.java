package network.skypvp.paper.service;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.CommandSender;
import network.skypvp.paper.platform.PlatformTask;

public final class ServerLifecycleService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final boolean LIFECYCLE_ENABLED = true;
   private static final int RESTART_AFTER_MINUTES = 360;
   private static final List<Integer> WARNING_MINUTES = List.of(30, 15, 10, 5, 1);
   private static final int COUNTDOWN_SECONDS = 10;
   private final PaperCorePlugin plugin;
   private final boolean enabled;
   private final long restartAfterMillis;
   private final List<Integer> warningMinutes;
   private final int countdownSeconds;
   private final Set<Integer> sentWarnings = new HashSet<>();
   private PlatformTask tickTask;
   private boolean armed;
   private boolean countdownActive;
   private int countdownRemainingSeconds;
   private long deadlineEpochMillis;

   public ServerLifecycleService(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.enabled = LIFECYCLE_ENABLED;
      int restartAfterMinutes = Math.max(10, RESTART_AFTER_MINUTES);
      this.restartAfterMillis = (long)restartAfterMinutes * 60000L;
      List<Integer> offsets = WARNING_MINUTES;

      this.warningMinutes = offsets.stream().filter(v -> v > 0).distinct().sorted((a, b) -> Integer.compare(b, a)).toList();
      this.countdownSeconds = Math.max(5, COUNTDOWN_SECONDS);
      this.armed = this.enabled;
      this.deadlineEpochMillis = plugin.bootEpochMillis() + this.restartAfterMillis;
   }

   public void start() {
      if (!this.enabled) {
         this.plugin.getLogger().info("[Lifecycle] Rotation service disabled.");
      } else {
         this.tickTask = this.plugin.platformScheduler().runGlobalTimer(this::tick, 20L, 20L);
         this.plugin
            .getLogger()
            .info(
               "[Lifecycle] Armed with restart-after="
                  + this.restartAfterMillis / 60000L
                  + "m, warnings="
                  + this.warningMinutes
                  + ", countdown="
                  + this.countdownSeconds
                  + "s."
            );
      }
   }

   public void stop() {
      if (this.tickTask != null) {
         this.tickTask.cancel();
         this.tickTask = null;
      }
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public boolean isArmed() {
      return this.armed;
   }

   public boolean isCountdownActive() {
      return this.countdownActive;
   }

   public long remainingSeconds() {
      return this.countdownActive
         ? Math.max(0L, (long)this.countdownRemainingSeconds)
         : Math.max(0L, (this.deadlineEpochMillis - System.currentTimeMillis() + 999L) / 1000L);
   }

   // $VF: renamed from: arm (org.bukkit.command.CommandSender) void
   public void method_238(CommandSender sender) {
      this.armed = true;
      this.countdownActive = false;
      this.countdownRemainingSeconds = 0;
      this.sentWarnings.clear();
      this.deadlineEpochMillis = System.currentTimeMillis() + this.restartAfterMillis;
      sender.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Automatic rotation armed. Restart in <#FFD700>"
               + this.formatDuration(this.remainingSeconds())
               + "</#FFD700>.</"
         )
      );
   }

   public void disarm(CommandSender sender) {
      this.armed = false;
      this.countdownActive = false;
      this.countdownRemainingSeconds = 0;
      this.sentWarnings.clear();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Automatic rotation disarmed.</#888888>"));
   }

   public void restartNow(CommandSender sender) {
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Manual restart requested. Starting countdown.</#888888>"));
      this.beginCountdown();
   }

   public List<String> statusLines() {
      List<String> lines = new ArrayList<>();
      lines.add(
         "<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>enabled=<#FFFFFF>"
            + this.enabled
            + "</#FFFFFF> armed=<#FFFFFF>"
            + this.armed
            + "</#FFFFFF> countdown=<#FFFFFF>"
            + this.countdownActive
            + "</#FFFFFF>"
      );
      lines.add(
         "<#888888>restartAfter=<#FFD700>"
            + this.restartAfterMillis / 60000L
            + "m</#FFD700> remaining=<#FFD700>"
            + this.formatDuration(this.remainingSeconds())
            + "</#FFD700> warnings=<#FFFFFF>"
            + this.warningMinutes
            + "</#FFFFFF>."
      );
      return lines;
   }

   private void tick() {
      if (this.armed) {
         if (this.countdownActive) {
            this.sendCountdownTick();
         } else {
            long remaining = this.remainingSeconds();

            for (int warningMinute : this.warningMinutes) {
               if (!this.sentWarnings.contains(warningMinute) && remaining <= (long)warningMinute * 60L) {
                  this.sentWarnings.add(warningMinute);
                  this.broadcastGlobal(
                     "<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Server rotation in <#FFD700>"
                        + warningMinute
                        + " minute"
                        + (warningMinute == 1 ? "" : "s")
                        + "</#FFD700>. Wrap up active actions.</#888888>",
                     true
                  );
               }
            }

            if (remaining <= (long)this.countdownSeconds) {
               this.beginCountdown();
            }
         }
      }
   }

   private void beginCountdown() {
      if (!this.countdownActive) {
         this.countdownActive = true;
         this.countdownRemainingSeconds = this.countdownSeconds;
         this.plugin.publishNotJoinableHeartbeatNow();
         this.broadcastGlobal(
            "<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Server restarting in <#FF5555><bold>"
               + this.countdownRemainingSeconds
               + "</bold></#FF5555> seconds.</#888888>",
            true
         );
      }
   }

   private void sendCountdownTick() {
      if (this.countdownRemainingSeconds <= 0) {
         this.executeShutdown();
      } else {
         if (this.countdownRemainingSeconds <= 5 || this.countdownRemainingSeconds % 5 == 0) {
            this.broadcastGlobal(
               "<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Restarting in <#FF5555><bold>"
                  + this.countdownRemainingSeconds
                  + "</bold></#FF5555>...</#888888>",
               false
            );
         }

         if (this.countdownRemainingSeconds == 5) {
            this.plugin.gracefulDrainService().beginDrain();
         }

         this.countdownRemainingSeconds--;
      }
   }

   private void executeShutdown() {
      this.broadcastGlobal("<#FFB300><bold>[Lifecycle]</bold></#FFB300> <#888888>Restarting now. Reconnect in a few seconds.</#888888>", true);
      this.triggerSaveAll();
      this.plugin.getLogger().info("[Lifecycle] Restart window reached; shutting down server for orchestration restart.");
      this.plugin.getServer().shutdown();
   }

   private void triggerSaveAll() {
      try {
         boolean dispatched = this.plugin.getServer().dispatchCommand(this.plugin.getServer().getConsoleSender(), "save-all flush");
         if (dispatched) {
            this.plugin.getLogger().info("[Lifecycle] Ran save-all flush before shutdown.");
         } else {
            this.plugin.getLogger().warning("[Lifecycle] save-all flush was not accepted before shutdown.");
         }
      } catch (RuntimeException var2) {
         this.plugin.getLogger().warning("[Lifecycle] Failed to run save-all flush before shutdown: " + var2.getMessage());
      }
   }

   private void broadcastGlobal(String miniMessage, boolean includeConsole) {
      this.plugin.getServer().broadcast(ServerTextUtil.miniMessageComponent(miniMessage));
      if (includeConsole) {
         this.plugin.getLogger().info("[Lifecycle] " + MiniMessage.miniMessage().stripTags(miniMessage));
      }
   }

   private String formatDuration(long totalSeconds) {
      long hours = totalSeconds / 3600L;
      long minutes = totalSeconds % 3600L / 60L;
      long seconds = totalSeconds % 60L;
      if (hours > 0L) {
         return hours + "h " + minutes + "m";
      } else {
         return minutes > 0L ? minutes + "m " + seconds + "s" : seconds + "s";
      }
   }
}
