package net.elytrium.limboapi.api.chunk.util;

import com.velocitypowered.api.network.ProtocolVersion;

public interface CompactStorage {
   void set(int var1, int var2);

   int get(int var1);

   void write(Object var1, ProtocolVersion var2);

   int getBitsPerEntry();

   @Deprecated(
      forRemoval = true
   )
   default int getDataLength() {
      return this.getDataLength(ProtocolVersion.MINIMUM_VERSION);
   }

   int getDataLength(ProtocolVersion var1);

   long[] getData();

   CompactStorage copy();
}
