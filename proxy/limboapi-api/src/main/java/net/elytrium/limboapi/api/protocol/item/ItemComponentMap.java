package net.elytrium.limboapi.api.protocol.item;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.List;

public interface ItemComponentMap {
   <T> ItemComponentMap add(ProtocolVersion var1, String var2, T var3);

   ItemComponentMap remove(ProtocolVersion var1, String var2);

   List<ItemComponent> getAdded();

   List<ItemComponent> getRemoved();

   void read(ProtocolVersion var1, Object var2);

   void write(ProtocolVersion var1, Object var2);
}
