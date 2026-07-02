package net.elytrium.limboapi.api.chunk.data;

import com.velocitypowered.api.network.ProtocolVersion;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import org.checkerframework.checker.nullness.qual.NonNull;

public interface BlockStorage {
   void write(Object var1, ProtocolVersion var2, int var3);

   void set(int var1, int var2, int var3, @NonNull VirtualBlock var4);

   @NonNull
   VirtualBlock get(int var1, int var2, int var3);

   int getDataLength(ProtocolVersion var1);

   BlockStorage copy();

   static int index(int posX, int posY, int posZ) {
      return posY << 8 | posZ << 4 | posX;
   }
}
