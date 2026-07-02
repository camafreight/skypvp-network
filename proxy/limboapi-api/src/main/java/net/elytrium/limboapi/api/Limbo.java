package net.elytrium.limboapi.api;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import java.util.function.Supplier;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.protocol.PacketDirection;
import net.elytrium.limboapi.api.protocol.packets.PacketMapping;

public interface Limbo {
   void spawnPlayer(Player var1, LimboSessionHandler var2);

   void respawnPlayer(Player var1);

   long getCurrentOnline();

   Limbo setName(String var1);

   Limbo setReadTimeout(int var1);

   Limbo setWorldTime(long var1);

   Limbo setGameMode(GameMode var1);

   Limbo setShouldRejoin(boolean var1);

   Limbo setShouldRespawn(boolean var1);

   @Deprecated
   Limbo setShouldUpdateTags(boolean var1);

   Limbo setReducedDebugInfo(boolean var1);

   Limbo setViewDistance(int var1);

   Limbo setSimulationDistance(int var1);

   Limbo setMaxSuppressPacketLength(int var1);

   Limbo registerCommand(LimboCommandMeta var1);

   Limbo registerCommand(CommandMeta var1, Command var2);

   Limbo registerPacket(PacketDirection var1, Class<?> var2, Supplier<?> var3, PacketMapping[] var4);

   void dispose();
}
