package network.skypvp.lobby.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.service.LobbyLayoutService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class LobbyLayoutCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final List<String> SUBCOMMANDS = List.of("status", "setspawn", "setpoint", "pointtp", "addnpc", "delnpc", "addholo", "delholo", "apply");
   private final LobbyLayoutService lobbyLayoutService;

   public LobbyLayoutCommand(LobbyLayoutService lobbyLayoutService) {
      this.lobbyLayoutService = lobbyLayoutService;
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
            case "setspawn":
               this.handleSetSpawn(sender);
               break;
            case "setpoint":
               this.handleSetPoint(sender, args);
               break;
            case "pointtp":
               this.handlePointTeleport(sender, args);
               break;
            case "addnpc":
               this.handleAddNpc(sender, args);
               break;
            case "delnpc":
               this.handleDeleteNpc(sender, args);
               break;
            case "addholo":
               this.handleAddHologram(sender, args);
               break;
            case "delholo":
               this.handleDeleteHologram(sender, args);
               break;
            case "apply":
               this.handleApply(sender);
               break;
            default:
               this.sendUsage(sender);
         }

         return true;
      }
   }

   private void handleStatus(CommandSender sender) {
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700><bold>Lobby template layout</bold><reset>"));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>Preset:<reset> <#888888>" + this.lobbyLayoutService.activePresetId() + "<reset>"));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>Navigation points:<reset> <#888888>" + this.lobbyLayoutService.navigationCount() + "<reset>"));
      List<String> npcs = this.lobbyLayoutService.npcSummaries();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>NPCs:<reset> <#888888>" + npcs.size() + "<reset>"));

      for (String summary : npcs) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("    <#888888>- " + summary + "<reset>"));
      }

      List<String> holograms = this.lobbyLayoutService.hologramSummaries();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>Holograms:<reset> <#888888>" + holograms.size() + "<reset>"));

      for (String summary : holograms) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("    <#888888>- " + summary + "<reset>"));
      }
   }

   private void handleSetSpawn(CommandSender sender) {
      if (sender instanceof Player player) {
         this.lobbyLayoutService.setSpawn(player.getLocation());
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Template spawn saved.</bold><reset><#888888> Active preset updated.<reset>"));
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleSetPoint(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 2) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout setpoint <id><reset>"));
         } else if (!this.lobbyLayoutService.setNavigationPoint(args[1], player.getLocation())) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid point id. Use [a-z0-9_-].<reset>"));
         } else {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Navigation point saved.</bold><reset>"));
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handlePointTeleport(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 2) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout pointtp <id><reset>"));
         } else {
            this.lobbyLayoutService
               .navigationPoint(args[1], player.getWorld())
               .ifPresentOrElse(player::teleport, () -> player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Point not found.<reset>")));
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleAddNpc(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 3) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout addnpc <id> <display_name> [actionType] [actionData...] [entityType]<reset>"));
         } else {
            String id = args[1];
            String displayName = args[2].replace('_', ' ');
            String actionType = args.length >= 4 ? args[3] : "NONE";
            String actionData = "";
            String entityType = "VILLAGER";
            if (args.length >= 5) {
               actionData = args[4];
            }

            if (args.length >= 6) {
               actionData = String.join(" ", Arrays.asList(args).subList(4, args.length - 1));
               entityType = args[args.length - 1];
            }

            boolean ok = this.lobbyLayoutService.upsertNpc(id, displayName, actionType, actionData, entityType, player.getLocation(), player.getWorld());
            if (!ok) {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid NPC id. Use [a-z0-9_-].<reset>"));
            } else {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>NPC saved.</bold><reset><#888888> Layout persisted to preset.<reset>"));
            }
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleDeleteNpc(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 2) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout delnpc <id><reset>"));
         } else if (!this.lobbyLayoutService.removeNpc(args[1], player.getWorld())) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>NPC not found.<reset>"));
         } else {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>NPC removed.</bold><reset>"));
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleAddHologram(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 3) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout addholo <id> <line1|line2|line3><reset>"));
         } else {
            String id = args[1];
            String joinedLines = String.join(" ", Arrays.asList(args).subList(2, args.length));
            List<String> lines = Arrays.stream(joinedLines.split("\\|")).map(String::trim).filter(s -> !s.isBlank()).toList();
            if (!this.lobbyLayoutService.upsertHologram(id, lines, player.getLocation(), player.getWorld())) {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid hologram id or empty lines.<reset>"));
            } else {
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Hologram saved.</bold><reset><#888888> Layout persisted to preset.<reset>"));
            }
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleDeleteHologram(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 2) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout delholo <id><reset>"));
         } else if (!this.lobbyLayoutService.removeHologram(args[1], player.getWorld())) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Hologram not found.<reset>"));
         } else {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Hologram removed.</bold><reset>"));
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void handleApply(CommandSender sender) {
      if (sender instanceof Player player) {
         this.lobbyLayoutService.reload();
         this.lobbyLayoutService.applyOnly(player.getWorld());
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>Layout applied.</bold><reset>"));
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use this command."));
      }
   }

   private void sendUsage(CommandSender sender) {
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbylayout [status|setspawn|setpoint|pointtp|addnpc|delnpc|addholo|delholo|apply]<reset>"));
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         return List.of();
      } else if (args.length == 1) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> matches = new ArrayList<>();

         for (String option : SUBCOMMANDS) {
            if (option.startsWith(prefix)) {
               matches.add(option);
            }
         }

         return matches;
      } else if (args.length == 2 && "pointtp".equalsIgnoreCase(args[0])) {
         String prefix = args[1].toLowerCase(Locale.ROOT);
         List<String> matches = new ArrayList<>();

         for (Entry<String, ?> entry : this.lobbyLayoutService.navigationPoints().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
               matches.add(entry.getKey());
            }
         }

         return matches;
      } else if (args.length != 2 || !"delnpc".equalsIgnoreCase(args[0]) && !"delholo".equalsIgnoreCase(args[0])) {
         return args.length == 4 && "addnpc".equalsIgnoreCase(args[0]) ? List.of("NONE", "CONNECT", "COMMAND", "MESSAGE") : List.of();
      } else {
         String prefix = args[1].toLowerCase(Locale.ROOT);
         List<String> source = "delnpc".equalsIgnoreCase(args[0])
            ? this.lobbyLayoutService.npcSummaries().stream().map(s -> s.split(" ")[0]).toList()
            : this.lobbyLayoutService.hologramSummaries().stream().map(s -> s.split(" ")[0]).toList();
         List<String> matches = new ArrayList<>();

         for (String optionx : source) {
            if (optionx.startsWith(prefix)) {
               matches.add(optionx);
            }
         }

         return matches;
      }
   }
}
