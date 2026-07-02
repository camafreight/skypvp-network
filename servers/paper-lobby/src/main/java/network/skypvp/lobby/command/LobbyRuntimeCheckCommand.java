package network.skypvp.lobby.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.ServerTextUtil.NoticeBuilder;
import network.skypvp.shared.ServerTextUtil.ThemeTone;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class LobbyRuntimeCheckCommand implements CommandExecutor {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final LobbyRuntimeStateRegistry states;
   private final Location spawn;

   public LobbyRuntimeCheckCommand(PaperCorePlugin plugin, LobbyRuntimeStateRegistry states, Location spawn) {
      this.plugin = plugin;
      this.states = states;
      this.spawn = spawn;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("skypvp.staff")) {
         sender.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>No permission.<reset>"));
         return true;
      } else {
         World spawnWorld = this.spawn.getWorld();
         boolean spawnWorldOk = spawnWorld != null;
         String spawnWorldName = spawnWorldOk ? spawnWorld.getName() : "<missing>";
         boolean redisEnabled = this.plugin.getConfig().getBoolean("redis.enabled", false);
         boolean dbEnabled = this.plugin.getConfig().getBoolean("postgres.enabled", false);
         int online = this.plugin.getServer().getOnlinePlayers().size();
         NoticeBuilder notice = ServerTextUtil.createNotice()
            .includeTitle("Lobby Runtime Check")
            .defaultBodyTone(ThemeTone.BRAND_100)
            .addMiniMessageLine("<#555555>GameState:<reset> <#FFD700>" + this.states.gameState().name() + "<reset>")
            .addMiniMessageLine(
               "<#555555>Spawn World:<reset> " + (spawnWorldOk ? "<#55FF55>OK<reset> <#888888>(" + spawnWorldName + ")<reset>" : "<#FF5555>MISSING<reset>")
            )
            .addMiniMessageLine("<#555555>Online:<reset> <#FFD700>" + online + "<reset>")
            .addMiniMessageLine("<#555555>Redis:<reset> " + (redisEnabled ? "<#00E676>enabled<reset>" : "<#FFB74D>disabled<reset>"))
            .addMiniMessageLine("<#555555>PostgreSQL:<reset> " + (dbEnabled ? "<#00E676>enabled<reset>" : "<#FFB74D>disabled<reset>"));
         if (!spawnWorldOk) {
            notice = notice.addMiniMessageLine("<#FF5555>[!] Spawn world is missing. Lobby recovery teleport can fail.<reset>");
         }

         sender.sendMessage(notice.buildComponent());
         return true;
      }
   }
}
