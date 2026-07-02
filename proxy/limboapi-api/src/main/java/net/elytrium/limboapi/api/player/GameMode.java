package net.elytrium.limboapi.api.player;

import org.checkerframework.checker.nullness.qual.Nullable;

public enum GameMode {
   SURVIVAL,
   CREATIVE,
   ADVENTURE,
   SPECTATOR;

   private static final GameMode[] VALUES = values();

   public short getID() {
      return (short)this.ordinal();
   }

   @Nullable
   public static GameMode getByID(int id) {
      return VALUES[id];
   }
}
