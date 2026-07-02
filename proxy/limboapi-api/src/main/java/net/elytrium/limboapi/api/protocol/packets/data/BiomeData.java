package net.elytrium.limboapi.api.protocol.packets.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.elytrium.limboapi.api.chunk.VirtualBiome;
import net.elytrium.limboapi.api.chunk.data.ChunkSnapshot;

public class BiomeData {
   private final int[] post115Biomes = new int[1024];
   private final byte[] pre115Biomes = new byte[256];

   public BiomeData(ChunkSnapshot chunk) {
      VirtualBiome[] biomes = chunk.getBiomes();

      for (int i = 0; i < biomes.length; i++) {
         this.post115Biomes[i] = biomes[i].getID();
      }

      Map<Integer, Integer> samples = new HashMap<>(64);

      for (int posX = 0; posX < 16; posX += 4) {
         for (int posZ = 0; posZ < 16; posZ += 4) {
            samples.clear();

            for (int posY = 0; posY < 256; posY += 16) {
               VirtualBiome biome = biomes[(posY >> 2 & 63) << 4 | (posZ >> 2 & 3) << 2 | posX >> 2 & 3];
               samples.put(biome.getID(), samples.getOrDefault(biome.getID(), 0) + 1);
            }

            int id = samples.entrySet().stream().max(Entry.comparingByValue()).orElseThrow().getKey();

            for (int i = posX; i < posX + 4; i++) {
               for (int j = posZ; j < posZ + 4; j++) {
                  this.pre115Biomes[(j << 4) + i] = (byte)id;
               }
            }
         }
      }
   }

   public int[] getPost115Biomes() {
      return this.post115Biomes;
   }

   public byte[] getPre115Biomes() {
      return this.pre115Biomes;
   }
}
