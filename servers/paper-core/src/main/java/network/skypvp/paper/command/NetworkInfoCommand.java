package network.skypvp.paper.command;

import network.skypvp.shared.ServerTextUtil;

import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class NetworkInfoCommand implements CommandExecutor {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final String serverId;
   private final String serverRole;
   private final long startTimeMs;

   public NetworkInfoCommand(PaperCorePlugin plugin, String serverId, String serverRole) {
      this.plugin = plugin;
      this.serverId = serverId;
      this.serverRole = serverRole;
      this.startTimeMs = System.currentTimeMillis();
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      long uptime = System.currentTimeMillis() - this.startTimeMs;
      long uptimeHours = uptime / 3600000L;
      long uptimeMinutes = uptime / 60000L % 60L;
      int online = this.plugin.getServer().getOnlinePlayers().size();
      int max = this.plugin.getServer().getMaxPlayers();
      sender.sendMessage(ServerTextUtil.miniMessageComponent("\n<gradient:#FFB300:#FF6F00><bold>  ⓘ SkyPvP Network  </bold></gradient>"));
      sender.sendMessage(
         ServerTextUtil.miniMessageComponent(
            "  <#555555>Server:<reset> <#FFD700>" + this.serverId + "<reset> <#555555>Role:<reset> <#FFD700>" + this.serverRole + "<reset>"
         )
      );
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>Players:<reset> <#FFD700>" + online + "<reset> <#555555>/<reset> " + max));
      sender.sendMessage(ServerTextUtil.miniMessageComponent("  <#555555>Uptime:<reset> <#FFD700>" + uptimeHours + "h " + uptimeMinutes + "m<reset>"));
      sender.sendMessage(ServerTextUtil.miniMessageComponent(""));
      return true;
   }
}
