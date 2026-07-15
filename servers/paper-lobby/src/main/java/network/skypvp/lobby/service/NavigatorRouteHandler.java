package network.skypvp.lobby.service;

import org.bukkit.entity.Player;

public interface NavigatorRouteHandler {
   void connectExact(Player player, String serverId, String label);

   /** Routes the player into Aether Breach / extraction (direct connect or proxy queue). */
   void routeExtraction(Player player, String serverId, String label);
}
