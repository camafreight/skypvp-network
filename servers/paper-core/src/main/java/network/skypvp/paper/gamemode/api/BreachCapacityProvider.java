package network.skypvp.paper.gamemode.api;

import java.util.List;
import network.skypvp.shared.BreachInstanceSnapshot;

public interface BreachCapacityProvider {
   int openBreachSlots();

   int activeBreaches();

   int queuedPlayers();

   int maxPlayersPerPod();

   default List<BreachInstanceSnapshot> breachInstanceCatalog() {
      return List.of();
   }
}
