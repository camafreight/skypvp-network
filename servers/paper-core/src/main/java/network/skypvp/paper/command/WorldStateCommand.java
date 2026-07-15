package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.model.WorldPresetMeta;
import network.skypvp.paper.service.WorldPresetService;
import network.skypvp.paper.service.WorldStateService;
import network.skypvp.shared.FieldValueFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class WorldStateCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final List<String> SUBCOMMANDS = List.of("status", "presets", "capture", "reset", "apply");
   private final PaperCorePlugin plugin;
   private final WorldStateService worldState;

   public WorldStateCommand(PaperCorePlugin plugin, WorldStateService worldState) {
      this.plugin = plugin;
      this.worldState = worldState;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>No permission.<reset>"));
         return true;
      } else {
         String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
         switch (sub) {
            case "status":
               this.handleStatus(sender);
               break;
            case "presets":
               this.handlePresets(sender);
               break;
            case "capture":
               if (args.length < 2) {
                  sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /worldstate capture <presetId><reset>"));
                  return true;
               }

               this.handleCapture(sender, args[1]);
               break;
            case "reset":
               this.worldState.resetFromPreset(sender);
               break;
            case "apply":
               if (args.length < 2) {
                  sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /worldstate apply <presetId><reset>"));
                  return true;
               }

               this.handleApply(sender, args[1]);
               break;
            default:
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /worldstate [status|presets|capture <id>|reset|apply <id>]<reset>"));
         }

         return true;
      }
   }

   private void handleStatus(CommandSender sender) {
      WorldPresetService presets = this.worldState.presetService();
      String presetId = this.worldState.resolvePresetId();
      boolean presetExists = presets.hasPreset(presetId);
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>World-state \u2014 <reset><#FFD700>" + this.plugin.serverId() + "<reset>"));
      String roleFormatted = FieldValueFormatter.fieldValueMiniMessage("Role", this.plugin.serverRole().name());
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + roleFormatted));
      String presetStatus = presetExists ? "<green>\u2714" : "<red>\u2718 (not found)";
      String presetFormatted = FieldValueFormatter.fieldValueMiniMessage("Preset", presetId + " " + presetStatus);
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + presetFormatted));
      String presetRootFormatted = FieldValueFormatter.fieldValueMiniMessage("Preset Root", presets.presetRoot().toString());
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + presetRootFormatted));
      String worldsFormatted = FieldValueFormatter.fieldValueMiniMessage("Managed Worlds", String.join(", ", this.worldState.managedWorlds()));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + worldsFormatted));
      String statusFormatted = FieldValueFormatter.fieldValueMiniMessage("Status", this.worldState.startupStatus());
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + statusFormatted));
      String joinableFormatted = FieldValueFormatter.fieldValueMiniMessage("Joinable", String.valueOf(this.worldState.isJoinableForRouting()));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + joinableFormatted));
      String holds = this.worldState.routingHolds().isEmpty()
            ? "(none)"
            : String.join(", ", this.worldState.routingHolds());
      String holdsFormatted = FieldValueFormatter.fieldValueMiniMessage("Routing Holds", holds);
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + holdsFormatted));
      String resetInProgressFormatted = FieldValueFormatter.fieldValueMiniMessage("Reset In Progress", String.valueOf(this.worldState.isResetInProgress()));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + resetInProgressFormatted));
      String startupSyncFormatted = FieldValueFormatter.fieldValueMiniMessage("Startup Sync", String.valueOf(this.worldState.isStartupSyncInProgress()));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + startupSyncFormatted));
   }

   private void handlePresets(CommandSender sender) {
      List<String> available = this.worldState.presetService().listPresets();
      if (available.isEmpty()) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No presets found in " + this.worldState.presetService().presetRoot() + "<reset>"));
      } else {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Available presets (" + available.size() + "):<reset>"));
         String configuredPresetId = this.worldState.resolvePresetId();

         for (String id : available) {
            WorldPresetMeta meta = this.worldState.presetService().readMeta(id);
            boolean active = id.equals(configuredPresetId);
            sender.sendMessage(
               ServerTextUtil.miniMessageComponent(
                  "  <#555555>"
                     + (active ? "<green>\u25B6 " : "  ")
                     + "<#FFD700>"
                     + id
                     + "<reset>"
                     + (meta.description().isBlank() ? "" : " <#888888>\u2014 " + meta.description() + "<reset>")
               )
            );
         }
      }
   }

   private void handleCapture(CommandSender sender, String presetId) {
      if (!this.isValidPresetId(presetId)) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid preset ID (alphanumeric, hyphens, underscores only).<reset>"));
      } else {
         this.worldState.captureToPreset(presetId, sender);
      }
   }

   private void handleApply(CommandSender sender, String presetId) {
      if (!this.isValidPresetId(presetId)) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid preset ID (alphanumeric, hyphens, underscores only).<reset>"));
      } else {
         this.worldState.resetFromPreset(presetId, sender);
      }
   }

   private boolean isValidPresetId(String id) {
      return id != null && !id.isBlank() && id.matches("[a-zA-Z0-9_\\-]+");
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         return List.of();
      } else if (args.length == 1) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> options = new ArrayList<>(SUBCOMMANDS);
         options.removeIf(o -> !o.startsWith(prefix));
         return options;
      } else {
         if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("capture".equals(sub) || "apply".equals(sub)) {
               String prefix = args[1].toLowerCase(Locale.ROOT);
               List<String> presets = new ArrayList<>(this.worldState.presetService().listPresets());
               presets.removeIf(p -> !p.toLowerCase(Locale.ROOT).startsWith(prefix));
               return presets;
            }
         }

         return List.of();
      }
   }
}
