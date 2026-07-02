package network.skypvp.paper.util;

import java.util.List;
import java.util.Locale;

public final class DecorationScopes {
   public static final List<String> KNOWN = List.of("global", "lobby", "extraction");

   private DecorationScopes() {
   }

   public static String normalize(String raw) {
      return raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
   }
}
