package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.service.ServerLifecycleService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class ServerLifecycleCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ServerLifecycleService lifecycleService;

   public ServerLifecycleCommand(ServerLifecycleService lifecycleService) {
      this.lifecycleService = lifecycleService;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>No permission.</#FF5555>"));
         return true;
      } else if (!this.lifecycleService.isEnabled()) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Lifecycle rotation is disabled in config.</#888888>"));
         return true;
      } else {
         String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
         switch (sub) {
            case "status":
               this.lifecycleService.statusLines().forEach(line -> sender.sendMessage(ServerTextUtil.miniMessageComponent(line)));
               break;
            case "arm":
               this.lifecycleService.method_238(sender);
               break;
            case "disarm":
               this.lifecycleService.disarm(sender);
               break;
            case "restartnow":
               this.lifecycleService.restartNow(sender);
               break;
            default:
               sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lifecycle <status|arm|disarm|restartnow></#888888>"));
         }

         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         List<String> options = List.of("status", "arm", "disarm", "restartnow");
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
}
