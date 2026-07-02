package network.skypvp.paper.integration;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;

public final class ProxyRouteMessenger {
   private ProxyRouteMessenger() {
   }

   public static void routePlayer(PaperCorePlugin plugin, Player player, String destinationKey) {
      send(plugin, player, "ROUTE", destinationKey);
   }

   public static void connectExact(PaperCorePlugin plugin, Player player, String serverId) {
      send(plugin, player, "CONNECT", serverId);
   }

   private static void send(PaperCorePlugin plugin, Player player, String action, String destination) {
      if (plugin != null && player != null && action != null && destination != null && !destination.isBlank()) {
         ByteArrayDataOutput out = ByteStreams.newDataOutput();
         out.writeUTF(action);
         out.writeUTF(destination.trim());
         player.sendPluginMessage(plugin, "skypvp:route", out.toByteArray());
      }
   }
}
