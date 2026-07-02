package net.elytrium.limboapi.api.material;

import com.velocitypowered.api.network.ProtocolVersion;

public interface VirtualItem {
   short getID(ProtocolVersion var1);

   short getID(WorldVersion var1);

   boolean isSupportedOn(ProtocolVersion var1);

   boolean isSupportedOn(WorldVersion var1);

   String getModernID();
}
