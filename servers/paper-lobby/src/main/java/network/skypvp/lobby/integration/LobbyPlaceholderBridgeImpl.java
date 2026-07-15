package network.skypvp.lobby.integration;

import java.util.List;
import java.util.Locale;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiLayoutLibrary.NetworkNavigator54;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiPlaceholderTexts;
import network.skypvp.paper.integration.NetworkHeartbeatCache;
import network.skypvp.paper.integration.ProxyRouteMessenger;
import network.skypvp.paper.repository.NetworkServerDirectoryRepository;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.ServerHeartbeatEvent;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class LobbyPlaceholderBridgeImpl implements network.skypvp.paper.gamemode.api.LobbyPlaceholderBridge {
   private final LobbyRuntimeStateRegistry states;

   public LobbyPlaceholderBridgeImpl(LobbyRuntimeStateRegistry states) {
      this.states = states;
   }

   @Override
   public String resolve(Player player, String key) {
      if (player == null || key == null) {
         return "";
      }
      return switch (key) {
         case "lobby.game_state" -> this.states.gameState().name().toLowerCase(Locale.ROOT);
         case "lobby.player_state" -> this.states.refreshPlayerState(player).name().toLowerCase(Locale.ROOT);
         case "lobby.queue_target" -> this.states.queuedTarget(player.getUniqueId());
         case "lobby.player_line" -> buildPlayerLine(player);
         default -> "";
      };
   }

   private String buildPlayerLine(Player player) {
      return switch (this.states.refreshPlayerState(player)) {
         case QUEUED -> "Routing to " + this.states.queuedTarget(player.getUniqueId());
         case PARKOUR_RUNNING -> "Parkour in progress";
         case VANISHED_STAFF -> "Staff mode";
         default -> "Ready in the lobby";
      };
   }
}
