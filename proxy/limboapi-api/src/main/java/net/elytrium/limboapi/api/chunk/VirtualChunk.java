package net.elytrium.limboapi.api.chunk;

import net.elytrium.limboapi.api.chunk.data.ChunkSnapshot;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.value.qual.IntRange;

public interface VirtualChunk {
   void setBlock(int var1, int var2, int var3, @Nullable VirtualBlock var4);

   void setBlockEntity(int var1, int var2, int var3, @Nullable CompoundBinaryTag var4, @Nullable VirtualBlockEntity var5);

   void setBlockEntity(VirtualBlockEntity.Entry var1);

   @NonNull
   VirtualBlock getBlock(int var1, int var2, int var3);

   void setBiome2D(int var1, int var2, @NonNull VirtualBiome var3);

   void setBiome3D(int var1, int var2, int var3, @NonNull VirtualBiome var4);

   @NonNull
   VirtualBiome getBiome(int var1, int var2, int var3);

   void setBlockLight(int var1, int var2, int var3, byte var4);

   byte getBlockLight(int var1, int var2, int var3);

   void setSkyLight(int var1, int var2, int var3, byte var4);

   byte getSkyLight(int var1, int var2, int var3);

   void fillBlockLight(@IntRange(from = 0L,to = 15L) int var1);

   void fillSkyLight(@IntRange(from = 0L,to = 15L) int var1);

   int getPosX();

   int getPosZ();

   ChunkSnapshot getFullChunkSnapshot();

   ChunkSnapshot getPartialChunkSnapshot(long var1);
}
