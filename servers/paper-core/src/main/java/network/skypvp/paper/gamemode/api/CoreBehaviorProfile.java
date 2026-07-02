package network.skypvp.paper.gamemode.api;

import java.util.Optional;
import java.util.OptionalInt;

public interface CoreBehaviorProfile {
   String modeKey();

   default Optional<Boolean> booleanOverride(String key) {
      return Optional.empty();
   }

   default OptionalInt intOverride(String key) {
      return OptionalInt.empty();
   }

   default Optional<String> stringOverride(String key) {
      return Optional.empty();
   }
}
