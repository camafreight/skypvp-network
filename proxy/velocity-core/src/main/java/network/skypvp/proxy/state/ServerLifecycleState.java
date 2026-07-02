package network.skypvp.proxy.state;

import java.util.Locale;

public enum ServerLifecycleState {
   BOOTING,
   POPULATING,
   READY,
   DRAINING,
   RESETTING,
   MAINTENANCE,
   OFFLINE,
   FAILED;

   private ServerLifecycleState() {
   }

   public boolean isRoutable() {
      return this == READY;
   }

   public static ServerLifecycleState fromString(String raw) {
      if (raw != null && !raw.isBlank()) {
         try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var2) {
            return READY;
         }
      } else {
         return READY;
      }
   }
}
