package net.elytrium.limboapi.api.protocol;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.List;
import java.util.function.Function;

public interface PreparedPacket {
   <T> PreparedPacket prepare(T var1);

   <T> PreparedPacket prepare(T[] var1);

   <T> PreparedPacket prepare(List<T> var1);

   <T> PreparedPacket prepare(T var1, ProtocolVersion var2);

   <T> PreparedPacket prepare(T var1, ProtocolVersion var2, ProtocolVersion var3);

   <T> PreparedPacket prepare(T[] var1, ProtocolVersion var2);

   <T> PreparedPacket prepare(T[] var1, ProtocolVersion var2, ProtocolVersion var3);

   <T> PreparedPacket prepare(List<T> var1, ProtocolVersion var2);

   <T> PreparedPacket prepare(List<T> var1, ProtocolVersion var2, ProtocolVersion var3);

   <T> PreparedPacket prepare(Function<ProtocolVersion, T> var1);

   <T> PreparedPacket prepare(Function<ProtocolVersion, T> var1, ProtocolVersion var2);

   <T> PreparedPacket prepare(Function<ProtocolVersion, T> var1, ProtocolVersion var2, ProtocolVersion var3);

   PreparedPacket build();

   void release();
}
