package net.elytrium.limboapi.api.chunk;

import com.velocitypowered.api.network.ProtocolVersion;
import net.kyori.adventure.nbt.CompoundBinaryTag;

public interface VirtualBlockEntity {
   int getID(ProtocolVersion var1);

   int getID(BlockEntityVersion var1);

   boolean isSupportedOn(ProtocolVersion var1);

   boolean isSupportedOn(BlockEntityVersion var1);

   String getModernID();

   VirtualBlockEntity.Entry getEntry(int var1, int var2, int var3, CompoundBinaryTag var4);

   public interface Entry {
      VirtualBlockEntity getBlockEntity();

      int getPosX();

      int getPosY();

      int getPosZ();

      CompoundBinaryTag getNbt();

      int getID(ProtocolVersion var1);

      int getID(BlockEntityVersion var1);

      boolean isSupportedOn(ProtocolVersion var1);

      boolean isSupportedOn(BlockEntityVersion var1);
   }
}
