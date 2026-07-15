package network.skypvp.paper.integration;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.Collection;
import java.util.UUID;
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

   /** Runs a command on the Velocity proxy as the interacting player (placeholders should already be resolved). */
   public static void dispatchProxyCommand(PaperCorePlugin plugin, Player player, String command) {
      send(plugin, player, "PROXY_COMMAND", command);
   }

   /** Runs a command on the Velocity proxy console (placeholders should already be resolved on the backend). */
   public static void dispatchProxyConsoleCommand(PaperCorePlugin plugin, Player player, String command) {
      send(plugin, player, "PROXY_CONSOLE_COMMAND", command);
   }

   /**
    * Asks the proxy to pull the player's whole online party onto the server the player is currently on. Used when a
    * party leader starts a breach so scattered members are co-located on this extraction server. The destination
    * field is unused by the proxy for this action (a placeholder is sent to satisfy the payload format).
    */
   /**
    * Asks the Velocity proxy to matchmake {@code /breach play} against the network breach session pool.
    * The proxy reserves a cross-pod instance when possible; otherwise it signals this backend to provision locally.
    */
   public static void requestBreachPlay(PaperCorePlugin plugin, Player player, String mapId) {
      requestBreachPlay(plugin, player, mapId, null, null);
   }

   /**
    * Matchmake with an explicit deployable roster (party members already in a breach are omitted) and optional party id
    * so the proxy can route late joiners into the same instance as their squad.
    */
   public static void requestBreachPlay(
         PaperCorePlugin plugin,
         Player player,
         String mapId,
         Collection<UUID> deployableMembers,
         UUID partyId
   ) {
      if (plugin == null || player == null) {
         return;
      }
      ByteArrayDataOutput out = ByteStreams.newDataOutput();
      out.writeUTF("BREACH_PLAY");
      out.writeUTF(mapId == null ? "" : mapId.trim());
      java.util.List<UUID> roster = deployableMembers == null ? java.util.List.of() : java.util.List.copyOf(deployableMembers);
      out.writeInt(roster.size());
      for (UUID memberId : roster) {
         out.writeUTF(memberId.toString());
      }
      out.writeUTF(partyId == null ? "" : partyId.toString());
      player.sendPluginMessage(plugin, "skypvp:route", out.toByteArray());
   }

   public static void gatherParty(PaperCorePlugin plugin, Player player) {
      gatherParty(plugin, player, null);
   }

   /**
    * Roster-aware gather: only the members in {@code roster} (the breach squad the leader picked) are pulled onto the
    * leader's server; the rest of the party stays put. A null/empty roster means "gather the whole party". The leader
    * is always co-located regardless of the roster.
    */
   public static void gatherParty(PaperCorePlugin plugin, Player player, Collection<UUID> roster) {
      if (plugin == null || player == null) {
         return;
      }
      ByteArrayDataOutput out = ByteStreams.newDataOutput();
      out.writeUTF("PARTY_GATHER");
      out.writeUTF("self");
      if (roster == null || roster.isEmpty()) {
         out.writeInt(0);
      } else {
         out.writeInt(roster.size());
         for (UUID memberId : roster) {
            out.writeUTF(memberId.toString());
         }
      }
      player.sendPluginMessage(plugin, "skypvp:route", out.toByteArray());
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
