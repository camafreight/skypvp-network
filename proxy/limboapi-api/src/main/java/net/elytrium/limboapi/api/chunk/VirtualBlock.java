package net.elytrium.limboapi.api.chunk;

import com.velocitypowered.api.network.ProtocolVersion;
import net.elytrium.limboapi.api.material.WorldVersion;

public interface VirtualBlock {
   short getModernID();

   String getModernStringID();

   @Deprecated
   short getID(ProtocolVersion var1);

   short getBlockID(WorldVersion var1);

   short getBlockID(ProtocolVersion var1);

   boolean isSupportedOn(ProtocolVersion var1);

   boolean isSupportedOn(WorldVersion var1);

   short getBlockStateID(ProtocolVersion var1);

   boolean isSolid();

   boolean isAir();

   boolean isMotionBlocking();
}
