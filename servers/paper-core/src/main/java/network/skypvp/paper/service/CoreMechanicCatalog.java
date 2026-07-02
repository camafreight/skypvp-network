package network.skypvp.paper.service;

import java.util.List;
import network.skypvp.paper.gamemode.api.GameMechanicScope;
import network.skypvp.paper.gamemode.api.MechanicClassification;

public final class CoreMechanicCatalog {
   private CoreMechanicCatalog() {
   }

   public static List<MechanicClassification> mechanics() {
      return List.of(
         new MechanicClassification(
            "core.rank-permission-sync", GameMechanicScope.CORE, "paper-core", "Network-wide rank attachment and permission synchronization."
         ),
         new MechanicClassification(
            "core.presentation-runtime",
            GameMechanicScope.CORE_LIBRARY,
            "paper-core",
            "Template-driven tab/scoreboard rendering shared across all Paper servers."
         ),
         new MechanicClassification(
            "core.server-routing-bridge", GameMechanicScope.CORE, "paper-core", "Proxy route messaging and server hop bridge used by multiple game modes."
         )
      );
   }
}
