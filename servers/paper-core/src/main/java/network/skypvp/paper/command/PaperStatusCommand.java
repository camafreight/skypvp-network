package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.FieldValueFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class PaperStatusCommand implements CommandExecutor {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;

   public PaperStatusCommand(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      String serverId = this.plugin.serverId();
      int onlinePlayers = this.plugin.getServer().getOnlinePlayers().size();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Paper Status]</bold><reset>"));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Server", serverId)));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Role", this.plugin.serverRole().name())));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Online Players", String.valueOf(onlinePlayers))));
      return true;
   }
}
