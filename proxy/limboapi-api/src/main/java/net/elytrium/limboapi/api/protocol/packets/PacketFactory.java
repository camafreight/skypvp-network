package net.elytrium.limboapi.api.protocol.packets;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.List;
import java.util.Map;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.data.ChunkSnapshot;
import net.elytrium.limboapi.api.material.VirtualItem;
import net.elytrium.limboapi.api.material.WorldVersion;
import net.elytrium.limboapi.api.protocol.item.ItemComponentMap;
import net.elytrium.limboapi.api.protocol.packets.data.MapData;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface PacketFactory {
   Object createChangeGameStatePacket(int var1, float var2);

   Object createChunkDataPacket(ChunkSnapshot var1, boolean var2, int var3);

   Object createChunkDataPacket(ChunkSnapshot var1, Dimension var2);

   Object createChunkUnloadPacket(int var1, int var2);

   Object createDefaultSpawnPositionPacket(int var1, int var2, int var3, float var4);

   Object createDefaultSpawnPositionPacket(String var1, int var2, int var3, int var4, float var5, float var6);

   Object createMapDataPacket(int var1, byte var2, MapData var3);

   Object createPlayerAbilitiesPacket(int var1, float var2, float var3);

   Object createPlayerAbilitiesPacket(byte var1, float var2, float var3);

   Object createPositionRotationPacket(double var1, double var3, double var5, float var7, float var8, boolean var9, int var10, boolean var11);

   Object createSetExperiencePacket(float var1, int var2, int var3);

   Object createSetSlotPacket(int var1, int var2, VirtualItem var3, int var4, int var5, @Nullable CompoundBinaryTag var6);

   Object createSetSlotPacket(int var1, int var2, VirtualItem var3, int var4, int var5, @Nullable ItemComponentMap var6);

   Object createTimeUpdatePacket(long var1, long var3);

   Object createUpdateViewPositionPacket(int var1, int var2);

   Object createUpdateTagsPacket(WorldVersion var1);

   Object createUpdateTagsPacket(ProtocolVersion var1);

   Object createUpdateTagsPacket(Map<String, Map<String, List<Integer>>> var1);
}
