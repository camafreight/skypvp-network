package net.elytrium.limboapi.api.mcprotocollib;

import java.util.Arrays;
import net.elytrium.limboapi.api.chunk.data.BlockStorage;

public class NibbleArray3D {
   private final byte[] data;

   public NibbleArray3D(int size) {
      this.data = new byte[size >> 1];
   }

   public NibbleArray3D(int size, int defaultValue) {
      this.data = new byte[size >> 1];
      this.fill(defaultValue);
   }

   public NibbleArray3D(byte[] array) {
      this.data = array;
   }

   public byte[] getData() {
      return this.data;
   }

   public int get(int posX, int posY, int posZ) {
      int key = BlockStorage.index(posX, posY, posZ);
      int index = key >> 1;
      return (key & 1) == 0 ? this.data[index] & 15 : this.data[index] >> 4 & 15;
   }

   public void set(int posX, int posY, int posZ, int value) {
      this.set(BlockStorage.index(posX, posY, posZ), value);
   }

   public void set(int key, int val) {
      int index = key >> 1;
      if ((key & 1) == 0) {
         this.data[index] = (byte)(this.data[index] & 240 | val & 15);
      } else {
         this.data[index] = (byte)(this.data[index] & 15 | (val & 15) << 4);
      }
   }

   public void fill(int value) {
      for (int index = 0; index < this.data.length << 1; index++) {
         this.set(index, value);
      }
   }

   public NibbleArray3D copy() {
      return new NibbleArray3D(Arrays.copyOf(this.data, this.data.length));
   }
}
