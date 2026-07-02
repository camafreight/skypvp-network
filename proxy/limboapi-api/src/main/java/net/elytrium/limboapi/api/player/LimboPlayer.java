package net.elytrium.limboapi.api.player;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.material.VirtualItem;
import net.elytrium.limboapi.api.protocol.item.ItemComponentMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;

public interface LimboPlayer {
   void writePacket(Object var1);

   void writePacketAndFlush(Object var1);

   void flushPackets();

   void closeWith(Object var1);

   ScheduledExecutorService getScheduledExecutor();

   void sendImage(BufferedImage var1);

   void sendImage(BufferedImage var1, boolean var2);

   void sendImage(int var1, BufferedImage var2);

   void sendImage(int var1, BufferedImage var2, boolean var3);

   void sendImage(int var1, BufferedImage var2, boolean var3, boolean var4);

   void setInventory(VirtualItem var1, int var2);

   void setInventory(VirtualItem var1, int var2, int var3);

   void setInventory(int var1, VirtualItem var2, int var3, int var4, CompoundBinaryTag var5);

   void setInventory(int var1, VirtualItem var2, int var3, int var4, ItemComponentMap var5);

   void setGameMode(GameMode var1);

   void teleport(double var1, double var3, double var5, float var7, float var8);

   void disableFalling();

   void enableFalling();

   void disconnect();

   void disconnect(RegisteredServer var1);

   void sendAbilities();

   void sendAbilities(int var1, float var2, float var3);

   void sendAbilities(byte var1, float var2, float var3);

   byte getAbilities();

   GameMode getGameMode();

   Limbo getServer();

   Player getProxyPlayer();

   int getPing();

   void setWorldTime(long var1);
}
