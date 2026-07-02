package net.elytrium.limboapi.api.utils;

import java.util.HashMap;
import java.util.Map;

public final class EnumUniverse {
   private EnumUniverse() {
   }

   public static <T extends Enum<T>> Map<String, T> createProtocolLookup(T[] values) {
      Map<String, T> lookup = new HashMap<>();

      for (T value : values) {
         if (value.name().startsWith("MINECRAFT_")) {
            lookup.put(value.name().substring("MINECRAFT_".length()).replace("_", "."), value);
         }
      }

      return lookup;
   }
}
