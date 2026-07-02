package net.elytrium.limboapi.api.chunk.data;

import net.elytrium.limboapi.api.chunk.VirtualBlock;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface BlockSection {
   void setBlockAt(int var1, int var2, int var3, @Nullable VirtualBlock var4);

   VirtualBlock getBlockAt(int var1, int var2, int var3);

   BlockSection getSnapshot();

   long getLastUpdate();
}
