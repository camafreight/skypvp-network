package net.elytrium.limboapi.api.chunk;

import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;

public interface VirtualWorld {
   void setBlockEntity(int var1, int var2, int var3, @Nullable CompoundBinaryTag var4, @Nullable VirtualBlockEntity var5);

   @NonNull
   VirtualBlock getBlock(int var1, int var2, int var3);

   void setBiome2d(int var1, int var2, @NonNull VirtualBiome var3);

   void setBiome3d(int var1, int var2, int var3, @NonNull VirtualBiome var4);

   VirtualBiome getBiome(int var1, int var2, int var3);

   byte getBlockLight(int var1, int var2, int var3);

   void setBlockLight(int var1, int var2, int var3, byte var4);

   void fillBlockLight(@IntRange(from = 0L,to = 15L) int var1);

   void fillSkyLight(@IntRange(from = 0L,to = 15L) int var1);

   List<VirtualChunk> getChunks();

   List<List<VirtualChunk>> getOrderedChunks();

   @Nullable
   VirtualChunk getChunk(int var1, int var2);

   VirtualChunk getChunkOrNew(int var1, int var2);

   @NonNull
   Dimension getDimension();

   double getSpawnX();

   double getSpawnY();

   double getSpawnZ();

   float getYaw();

   float getPitch();

   void setBlock(int var1, int var2, int var3, @Nullable VirtualBlock var4);
}
