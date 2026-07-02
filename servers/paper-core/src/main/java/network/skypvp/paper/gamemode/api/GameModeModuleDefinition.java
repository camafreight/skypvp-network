package network.skypvp.paper.gamemode.api;

import java.util.List;

public interface GameModeModuleDefinition {
   String modeKey();

   String pluginId();

   List<MechanicClassification> mechanics();
}
