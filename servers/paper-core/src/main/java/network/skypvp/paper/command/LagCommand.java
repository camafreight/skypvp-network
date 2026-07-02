package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.service.PerformanceMonitorService;
import network.skypvp.shared.FieldValueFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class LagCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private final PerformanceMonitorService monitorService;

   public LagCommand(PerformanceMonitorService monitorService) {
      this.monitorService = monitorService;
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
            case "clear" -> {
               this.monitorService.clearEvents(sender);
               yield true;
            }
            default -> {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lag <status|events|clear></#888888>"));
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
         List<String> options = List.of("status", "events", "clear");
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

   private String colorForTps(double tps) {
      if (tps < 0.0) {
         return "#888888";
      } else if (tps >= 19.0) {
         return "#55FF55";
      } else {
         return tps >= 17.0 ? "#FFB300" : "#FF5555";
      }
   }

   private String colorForMspt(double mspt) {
      if (mspt < 0.0) {
         return "#888888";
      } else if (mspt <= 40.0) {
         return "#55FF55";
      } else {
         return mspt <= 55.0 ? "#FFB300" : "#FF5555";
      }
   }

   private String escape(String input) {
      return input.replace("<", "\\<").replace(">", "\\>");
   }
}
