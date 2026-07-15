package network.skypvp.lobby;

import java.util.Optional;
import java.util.OptionalInt;
import network.skypvp.paper.gamemode.api.CoreBehaviorProfile;

public final class LobbyCoreBehaviorProfile implements CoreBehaviorProfile {
   public LobbyCoreBehaviorProfile() {
   }

   public String modeKey() {
      return "lobby";
   }

   public Optional<Boolean> booleanOverride(String key) {
      if ("core.hotbar.enabled".equals(key)) {
         return Optional.of(false);
      } else if ("core.hud.action-bar.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.hud.boss-bar.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.hud.scoreboard.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.hud.tab.header-footer.enabled".equals(key)) {
         return Optional.of(false);
      } else if ("core.hud.tab.player-list-name.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.lobby.systems.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.selector.delegate.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.world.preset-gate.required".equals(key)) {
         return Optional.of(true);
      } else if ("core.world.clear-ground-items-on-load.enabled".equals(key)) {
         return Optional.of(true);
      } else if ("core.world.build-protection.enabled".equals(key)) {
         return Optional.of(true);
      } else {
         return "core.world.void-generator.enabled".equals(key) ? Optional.of(true) : Optional.empty();
      }
   }

   public OptionalInt intOverride(String key) {
      if ("core.hud.action-bar.refresh-ticks".equals(key)) {
         return OptionalInt.of(5);
      } else if ("core.hud.boss-bar.refresh-ticks".equals(key)) {
         return OptionalInt.of(5);
      } else if ("core.hud.scoreboard.refresh-ticks".equals(key)) {
         return OptionalInt.of(5);
      } else {
         return "core.tab.refresh.ticks".equals(key) ? OptionalInt.of(20) : OptionalInt.empty();
      }
   }
}
