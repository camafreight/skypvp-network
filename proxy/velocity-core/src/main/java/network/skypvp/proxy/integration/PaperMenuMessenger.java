package network.skypvp.proxy.integration;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

public final class PaperMenuMessenger {
   private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("skypvp:menu");

   private PaperMenuMessenger() {
   }

   public static boolean openFriendsMenu(Player player) {
      return send(player, "FRIENDS");
   }

   public static boolean openPartyMenu(Player player) {
      return send(player, "PARTY");
   }

   private static boolean send(Player player, String menuKey) {
      return player != null && menuKey != null && !menuKey.isBlank() ? player.getCurrentServer().map(serverConnection -> {
         ByteArrayDataOutput out = ByteStreams.newDataOutput();
         out.writeUTF(menuKey);
         return serverConnection.sendPluginMessage(CHANNEL, out.toByteArray());
      }).orElse(false) : false;
   }
}
