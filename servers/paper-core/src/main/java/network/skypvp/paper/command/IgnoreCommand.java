package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class IgnoreCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   public static final Map<UUID, Set<UUID>> IGNORED = new ConcurrentHashMap<>();
   private final PaperCorePlugin plugin;

   public static boolean isIgnored(UUID viewer, UUID sender) {
      Set<UUID> list = IGNORED.get(viewer);
      return list != null && list.contains(sender);
   }

   public IgnoreCommand(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         if (label.equalsIgnoreCase("ignorelist")) {
            Set<UUID> list = IGNORED.getOrDefault(p.getUniqueId(), Set.of());
            if (list.isEmpty()) {
               p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Your ignore list is empty.<reset>"));
               return true;
            } else {
               p.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  \ud83d\udeab Ignore List  </bold></gradient>"));

               for (UUID id : list) {
                  Player online = this.plugin.getServer().getPlayer(id);
                  String name = online != null ? online.getName() : id.toString();
                  p.sendMessage(ServerTextUtil.miniMessageComponent("  <#FFD700>" + name + "<reset>"));
               }

               return true;
            }
         } else if (args.length == 0) {
            p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /ignore <player> or /ignorelist<reset>"));
            return true;
         } else if (args.length < 1) {
            p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /ignore [player]<reset>"));
            return true;
         } else {
            Player target = this.plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
               p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Player not found or not online.<reset>"));
               return true;
            } else if (target.equals(p)) {
               p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>You cannot ignore yourself.<reset>"));
               return true;
            } else {
               this.toggleIgnore(p, target);
               return true;
            }
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("This command is for players only."));
         return true;
      }
   }

   public boolean toggleIgnore(Player player, Player target) {
      Set<UUID> myList = IGNORED.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
      if (myList.contains(target.getUniqueId())) {
         myList.remove(target.getUniqueId());
         player.sendMessage(ServerTextUtil.miniMessageComponent("\ud83d\udeab <#FFD700>" + target.getName() + "<reset><#888888> removed from your ignore list.<reset>"));
         return false;
      } else {
         myList.add(target.getUniqueId());
         player.sendMessage(ServerTextUtil.miniMessageComponent("\ud83d\udeab <#FFD700>" + target.getName() + "<reset><#888888> added to your ignore list.<reset>"));

         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1 && sender instanceof Player p) {
         String prefix = args[0].toLowerCase();
         return this.plugin
            .getServer()
            .getOnlinePlayers()
            .stream()
            .filter(pl -> !pl.equals(p))
            .<String>map(Player::getName)
            .filter(n -> n.toLowerCase().startsWith(prefix))
            .collect(Collectors.toList());
      } else {
         return List.of();
      }
   }
}
