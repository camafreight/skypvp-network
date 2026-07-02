package network.skypvp.lobby;

import java.util.List;
import network.skypvp.paper.gamemode.api.GameMechanicScope;
import network.skypvp.paper.gamemode.api.GameModeModuleDefinition;
import network.skypvp.paper.gamemode.api.MechanicClassification;

public final class LobbyMechanicCatalog implements GameModeModuleDefinition {
   public LobbyMechanicCatalog() {
   }

   public String modeKey() {
      return "lobby";
   }

   public String pluginId() {
      return "paper-lobby";
   }

   public List<MechanicClassification> mechanics() {
      return List.of(
         new MechanicClassification(
            "lobby.player-hub-routing", GameMechanicScope.GAMEMODE, this.pluginId(), "Lobby-specific flow for guiding players toward target experiences."
         ),
         new MechanicClassification("lobby.selector-experience", GameMechanicScope.GAMEMODE, this.pluginId(), "Lobby selector UX and interaction behavior.")
      );
   }
}
