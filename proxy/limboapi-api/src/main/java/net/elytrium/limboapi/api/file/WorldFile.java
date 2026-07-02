package net.elytrium.limboapi.api.file;

import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import org.checkerframework.common.value.qual.IntRange;

public interface WorldFile {
   default void toWorld(LimboFactory factory, VirtualWorld world, int offsetX, int offsetY, int offsetZ) {
      this.toWorld(factory, world, offsetX, offsetY, offsetZ, 15);
   }

   void toWorld(LimboFactory var1, VirtualWorld var2, int var3, int var4, int var5, @IntRange(from = 0L,to = 15L) int var6);
}
