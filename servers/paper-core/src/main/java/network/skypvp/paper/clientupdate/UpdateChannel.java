package network.skypvp.paper.clientupdate;

/**
 * Client-bound update channels. HUD channels coalesce per-player; FX channels share per-tick budgets.
 */
public enum UpdateChannel {
   ACTION_BAR,
   BOSS_BAR,
   SCOREBOARD,
   TAB,
   TITLE,
   SOUND,
   PARTICLE,
   DISPLAY_FX,
   BLOCK_CHANGE;

   public boolean isHud() {
      return switch (this) {
         case ACTION_BAR, BOSS_BAR, SCOREBOARD, TAB, TITLE -> true;
         default -> false;
      };
   }
}
