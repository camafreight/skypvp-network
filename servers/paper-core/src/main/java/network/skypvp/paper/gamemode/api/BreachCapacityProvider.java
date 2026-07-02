package network.skypvp.paper.gamemode.api;

public interface BreachCapacityProvider {
   int openBreachSlots();

   int activeBreaches();

   int queuedPlayers();

   int maxPlayersPerPod();
}
