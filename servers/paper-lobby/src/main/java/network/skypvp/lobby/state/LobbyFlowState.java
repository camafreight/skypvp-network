package network.skypvp.lobby.state;

public enum LobbyFlowState {
   OPEN,
   EVENT_COUNTDOWN,
   EVENT_LIVE,
   RESTARTING;

   private LobbyFlowState() {
   }
}
