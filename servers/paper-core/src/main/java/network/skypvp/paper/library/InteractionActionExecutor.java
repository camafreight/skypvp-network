package network.skypvp.paper.library;

import network.skypvp.shared.ServerTextUtil;

import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import org.bukkit.entity.Player;

final class InteractionActionExecutor {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage

   private InteractionActionExecutor() {
   }

   static void execute(PaperCorePlugin plugin, Player player, String actionType, String actionData) {
      if (plugin != null && player != null) {
         String normalizedAction = actionType != null && !actionType.isBlank() ? actionType.trim().toUpperCase(Locale.ROOT) : "NONE";
         String normalizedData = actionData == null ? "" : actionData.trim();
         switch (normalizedAction) {
            case "CONNECT":
            case "CONNECT_SERVER":
               if (!normalizedData.isBlank()) {
                  ProxyRouteMessenger.routePlayer(plugin, player, normalizedData.toLowerCase(Locale.ROOT));
               }
               break;
            case "COMMAND":
            case "PLAYER_COMMAND":
               if (!normalizedData.isBlank()) {
                  String command = normalizedData.startsWith("/") ? normalizedData.substring(1) : normalizedData;
                  player.performCommand(command);
               }
               break;
            case "OPEN_NETWORK_MENU":
            case "OPEN_MENU":
            case "NETWORK_MENU":
               if (plugin.networkMenuService() != null) {
                  plugin.networkMenuService().openRootMenu(player);
               }
               break;
            case "MENU":
            case "OPEN_MENU_KEY":
               if (plugin.networkMenuService() != null) {
                  plugin.networkMenuService().openMenuByKey(player, normalizedData);
               }
               break;
            case "MESSAGE":
               if (!normalizedData.isBlank()) {
                  player.sendMessage(ServerTextUtil.miniMessageComponent(normalizedData));
               }
               break;
            default:
               break;
         }
      }
   }
}
