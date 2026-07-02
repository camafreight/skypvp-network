package network.skypvp.paper.gamemode.api;

import org.bukkit.entity.Player;

public interface SocialTeleportPolicy {
   String modeKey();

   default SocialTeleportPolicy.TeleportDecision canRequestFriendTeleport(Player requester, Player target) {
      return SocialTeleportPolicy.TeleportDecision.allow();
   }

   default SocialTeleportPolicy.TeleportDecision canExecuteFriendTeleport(Player requester, Player target) {
      return SocialTeleportPolicy.TeleportDecision.allow();
   }

   public static record TeleportDecision(boolean allowed, String reason) {
      public static SocialTeleportPolicy.TeleportDecision allow() {
         return new SocialTeleportPolicy.TeleportDecision(true, "");
      }

      public static SocialTeleportPolicy.TeleportDecision deny(String reason) {
         return new SocialTeleportPolicy.TeleportDecision(false, reason == null ? "Teleport denied by gamemode policy." : reason);
      }
   }
}
