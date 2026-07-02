package network.skypvp.paper.integration;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.Locale;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.NetworkChannels;
import org.bukkit.entity.Player;

public final class ProxySocialMessenger {
   private ProxySocialMessenger() {
   }

   public static void sendFriendCommand(PaperCorePlugin plugin, Player player, String command) {
      send(player, plugin, "FRIEND", command);
   }

   public static void sendPartyCommand(PaperCorePlugin plugin, Player player, String command) {
      send(player, plugin, "PARTY", command);
   }

   private static void send(Player player, PaperCorePlugin plugin, String action, String command) {
      if (plugin != null && player != null && action != null && command != null) {
         String sanitized = command.trim();
         if (!sanitized.isBlank()) {
            String proxyCommand = toProxyCommand(action, sanitized);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(action);
            out.writeUTF(proxyCommand);
            player.sendPluginMessage(plugin, NetworkChannels.PROXY_SOCIAL_REQUEST, out.toByteArray());
         }
      }
   }

   static String toProxyCommand(String action, String command) {
      String normalized = command.toLowerCase(Locale.ROOT);
      return switch (action.trim().toUpperCase(Locale.ROOT)) {
         case "FRIEND" -> normalized.startsWith("friend") ? command : "friend " + command;
         case "PARTY" -> normalized.startsWith("party") ? command : "party " + command;
         default -> command;
      };
   }
}
