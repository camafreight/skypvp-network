package network.skypvp.lobby.command;

import network.skypvp.shared.ServerTextUtil;

import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.state.LobbyFlowState;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class LobbyStateCommand implements CommandExecutor, TabCompleter {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final LobbyRuntimeStateRegistry states;

   public LobbyStateCommand(LobbyRuntimeStateRegistry states) {
      this.states = states;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>No permission.<reset>"));
         return true;
      } else if (args.length == 0) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Lobby state: <reset><#FFD700>" + this.states.gameState().name() + "<reset>"));
         return true;
      } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
         try {
            LobbyFlowState next = LobbyFlowState.valueOf(args[1].toUpperCase());
            this.states.setGameState(next);
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Lobby state updated to <reset><#FFD700>" + next.name() + "<reset>"));
         } catch (IllegalArgumentException var6) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Invalid state. Use: OPEN, EVENT_COUNTDOWN, EVENT_LIVE, RESTARTING<reset>"));
         }

         return true;
      } else {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /lobbystate [set [state]]<reset>"));
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         return List.of("set");
      } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
         String p = args[1].toUpperCase();
         return List.of("OPEN", "EVENT_COUNTDOWN", "EVENT_LIVE", "RESTARTING").stream().filter(s -> s.startsWith(p)).toList();
      } else {
         return List.of();
      }
   }
}
