package net.elytrium.limboapi.api.chunk.data;

import java.util.List;
import net.elytrium.limboapi.api.chunk.VirtualBiome;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualBlockEntity;

public interface ChunkSnapshot {
   VirtualBlock getBlock(int var1, int var2, int var3);

   int getPosX();

   int getPosZ();

   boolean isFullChunk();

   BlockSection[] getSections();

   LightSection[] getLight();

   VirtualBiome[] getBiomes();

   List<VirtualBlockEntity.Entry> getBlockEntityEntries();
}
