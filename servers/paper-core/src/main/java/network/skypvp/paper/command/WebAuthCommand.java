package network.skypvp.paper.command;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class WebAuthCommand implements CommandExecutor {
   private final PaperCorePlugin plugin;

   public WebAuthCommand(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage(ServerTextUtil.colorize("<red>Only players can use this command."));
         return true;
      } else if (!player.hasPermission("skypvp.admin.web")) {
         player.sendMessage(ServerTextUtil.colorize("<red>You do not have permission to access the web dashboard."));
         return true;
      } else {
         String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
         StringBuilder otpBuilder = new StringBuilder(6);

         for (int i = 0; i < 6; i++) {
            otpBuilder.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
         }

         String otp = otpBuilder.toString();
         UUID uuid = player.getUniqueId();
         this.plugin.platformScheduler().runAsync(
               () -> {
                  try {
                     this.plugin.redisPublisher().getJedis().setex("lf:webauth:" + otp, 300L, uuid.toString());
                     String link = "http://localhost:3000/?otp=" + otp;
                     player.sendMessage(
                        ServerTextUtil.createNotice()
                           .includeTitle("Web Dashboard")
                           .addMiniMessageLine("<gray>Your one-time authentication link has been generated.")
                           .addMiniMessageLine("<gray>It will expire in <white>5 minutes</white>.")
                           .addMiniMessageLine("")
                           .addMiniMessageLine(
                              "<click:open_url:'"
                                 + link
                                 + "'><hover:show_text:'<green>Click to login'><b><green>[CLICK HERE TO LOGIN]</green></b></hover></click>"
                           )
                           .addMiniMessageLine("<dark_gray>" + link)
                           .buildComponent()
                     );
                  } catch (Exception var5x) {
                     this.plugin.getLogger().severe("Failed to save OTP to redis: " + var5x.getMessage());
                     player.sendMessage(ServerTextUtil.colorize("<red>An internal error occurred while generating the OTP."));
                  }
               }
            );
         return true;
      }
   }
}
