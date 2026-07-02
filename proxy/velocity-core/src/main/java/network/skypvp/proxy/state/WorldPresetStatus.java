package network.skypvp.proxy.state;

import java.util.Locale;

public enum WorldPresetStatus {
   DRAFT,
   VALIDATED,
   ACTIVE,
   DEPRECATED;

   private WorldPresetStatus() {
   }

   public static WorldPresetStatus fromString(String raw) {
      if (raw != null && !raw.isBlank()) {
         try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var2) {
            return DRAFT;
         }
      } else {
         return DRAFT;
      }
   }
}
