package net.elytrium.limboapi.api.chunk.data;

import net.elytrium.limboapi.api.mcprotocollib.NibbleArray3D;

public interface LightSection {
   void setBlockLight(int var1, int var2, int var3, byte var4);

   NibbleArray3D getBlockLight();

   byte getBlockLight(int var1, int var2, int var3);

   void setSkyLight(int var1, int var2, int var3, byte var4);

   NibbleArray3D getSkyLight();

   byte getSkyLight(int var1, int var2, int var3);

   long getLastUpdate();

   LightSection copy();
}
