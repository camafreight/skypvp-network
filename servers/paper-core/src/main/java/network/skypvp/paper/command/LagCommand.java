package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import network.skypvp.paper.clientupdate.ClientUpdatePipeline;
import network.skypvp.paper.clientupdate.ClientUpdateStats;
import network.skypvp.paper.clientupdate.UpdateChannel;
import network.skypvp.paper.service.PerformanceMonitorService;
import network.skypvp.shared.FieldValueFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class LagCommand implements CommandExecutor, TabCompleter {
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private final PerformanceMonitorService monitorService;
   private final ClientUpdatePipeline pipeline;

   public LagCommand(PerformanceMonitorService monitorService) {
      this(monitorService, null);
   }

   public LagCommand(PerformanceMonitorService monitorService, ClientUpdatePipeline pipeline) {
      this.monitorService = monitorService;
      this.pipeline = pipeline;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (label.equalsIgnoreCase("tps")) {
         if (!this.monitorService.isEnabled()) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>TPS data unavailable.<reset>"));
            return true;
         } else {
            PerformanceMonitorService.Snapshot snap = this.monitorService.latestSnapshot();
            sender.sendMessage(
               ServerTextUtil.miniMessageComponent(
                  FieldValueFormatter.fieldValueMiniMessage("TPS", this.fmt(snap.tps()))
                     + " <#555555>|</#555555> "
                     + FieldValueFormatter.fieldValueMiniMessage("MSPT", this.fmt(snap.mspt()) + " ms")
               )
            );
            return true;
         }
      } else if (!sender.hasPermission("skypvp.staff")) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>No permission.<reset>"));
         return true;
      } else if (!this.monitorService.isEnabled()) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Performance monitor is disabled in config.<reset>"));
         return true;
      } else {
         String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

         return switch (sub) {
            case "status" -> {
               this.sendStatus(sender);
               yield true;
            }
            case "events" -> {
               this.sendEvents(sender);
               yield true;
            }
            case "pipeline" -> {
               this.sendPipeline(sender);
               yield true;
            }
            case "clear" -> {
               this.monitorService.clearEvents(sender);
               yield true;
            }
            default -> {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lag <status|events|pipeline|clear></#888888>"));
               yield true;
            }
         };
      }
   }

   private void sendStatus(CommandSender sender) {
      PerformanceMonitorService.Snapshot snapshot = this.monitorService.latestSnapshot();
      String when = snapshot.capturedAt().equals(Instant.EPOCH) ? "never" : TIME_FMT.format(snapshot.capturedAt().atZone(ZoneId.systemDefault()));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Lag]</bold></#FFB300>"));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Sample", when)));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("TPS", this.fmt(snapshot.tps()))));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("MSPT", this.fmt(snapshot.mspt()) + " ms")));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Players", String.valueOf(snapshot.players()))));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Memory", snapshot.memoryUsagePercent() + "%")));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("GC Time Delta", snapshot.gcPressureMs() + " ms")));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("HUD Churn", snapshot.providerChurnRate() + "/s")));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Severe Streak", String.valueOf(snapshot.severeStreak()))));
      if (this.pipeline != null) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#888888>Use <#FFFFFF>/lag pipeline</#FFFFFF> for client-update counters.</#888888>"));
      }
   }

   private void sendPipeline(CommandSender sender) {
      if (this.pipeline == null) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Client update pipeline is not running.</#888888>"));
         return;
      }
      ClientUpdateStats stats = this.pipeline.snapshotStats();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Pipeline]</bold></#FFB300> <#888888>drains=" + stats.drainTicks() + "</#888888>"));
      for (UpdateChannel channel : UpdateChannel.values()) {
         long enq = stats.enqueued(channel);
         long coal = stats.coalesced(channel);
         long emit = stats.emitted(channel);
         long drop = stats.dropped(channel);
         if (enq == 0L && coal == 0L && emit == 0L && drop == 0L) {
            continue;
         }
         sender.sendMessage(ServerTextUtil.miniMessageComponent(
               "  <#FFFFFF>" + channel.name() + "</#FFFFFF> "
                     + "<#888888>enq=</#888888>" + enq
                     + " <#888888>coal=</#888888>" + coal
                     + " <#888888>emit=</#888888>" + emit
                     + " <#888888>drop=</#888888>" + drop
         ));
      }
   }

   private void sendEvents(CommandSender sender) {
      List<String> events = this.monitorService.recentEvents();
      if (events.isEmpty()) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No recent lag events.</#888888>"));
      } else {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Lag]</bold></#FFB300> <#888888>Recent lag events:</#888888>"));

         for (String event : events) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>- <#FFFFFF>" + this.escape(event) + "</#FFFFFF></#888888>"));
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         List<String> options = List.of("status", "events", "pipeline", "clear");
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> out = new ArrayList<>();

         for (String option : options) {
            if (option.startsWith(prefix)) {
               out.add(option);
            }
         }

         return out;
      } else {
         return List.of();
      }
   }

   private String fmt(double value) {
      return value < 0.0 ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
   }

   private String escape(String input) {
      return input == null ? "" : input.replace("<", "").replace(">", "");
   }
}
