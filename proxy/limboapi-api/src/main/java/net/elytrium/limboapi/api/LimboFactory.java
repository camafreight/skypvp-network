package net.elytrium.limboapi.api;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import net.elytrium.limboapi.api.chunk.BuiltInBiome;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualBiome;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualBlockEntity;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.BuiltInWorldFileType;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limboapi.api.material.Block;
import net.elytrium.limboapi.api.material.Item;
import net.elytrium.limboapi.api.material.VirtualItem;
import net.elytrium.limboapi.api.protocol.PreparedPacket;
import net.elytrium.limboapi.api.protocol.item.ItemComponentMap;
import net.elytrium.limboapi.api.protocol.packets.PacketFactory;
import net.kyori.adventure.nbt.CompoundBinaryTag;

public interface LimboFactory {
   VirtualBlock createSimpleBlock(Block var1);

   VirtualBlock createSimpleBlock(short var1);

   VirtualBlock createSimpleBlock(String var1);

   VirtualBlock createSimpleBlock(String var1, Map<String, String> var2);

   VirtualBlock createSimpleBlock(short var1, boolean var2);

   VirtualBlock createSimpleBlock(boolean var1, boolean var2, boolean var3, short var4);

   VirtualBlock createSimpleBlock(boolean var1, boolean var2, boolean var3, String var4, Map<String, String> var5);

   VirtualWorld createVirtualWorld(Dimension var1, double var2, double var4, double var6, float var8, float var9);

   @Deprecated
   VirtualChunk createVirtualChunk(int var1, int var2);

   VirtualChunk createVirtualChunk(int var1, int var2, VirtualBiome var3);

   VirtualChunk createVirtualChunk(int var1, int var2, BuiltInBiome var3);

   Limbo createLimbo(VirtualWorld var1);

   void releasePreparedPacketThread(Thread var1);

   PreparedPacket createPreparedPacket();

   PreparedPacket createPreparedPacket(ProtocolVersion var1, ProtocolVersion var2);

   PreparedPacket createConfigPreparedPacket();

   PreparedPacket createConfigPreparedPacket(ProtocolVersion var1, ProtocolVersion var2);

   void passLoginLimbo(Player var1);

   VirtualItem getItem(Item var1);

   VirtualItem getItem(String var1);

   VirtualItem getLegacyItem(int var1);

   ItemComponentMap createItemComponentMap();

   VirtualBlockEntity getBlockEntity(String var1);

   PacketFactory getPacketFactory();

   ProtocolVersion getPrepareMinVersion();

   ProtocolVersion getPrepareMaxVersion();

   WorldFile openWorldFile(BuiltInWorldFileType var1, Path var2) throws IOException;

   WorldFile openWorldFile(BuiltInWorldFileType var1, InputStream var2) throws IOException;

   WorldFile openWorldFile(BuiltInWorldFileType var1, CompoundBinaryTag var2);
}
