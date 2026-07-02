package net.elytrium.limboapi.api;

import net.elytrium.limboapi.api.player.LimboPlayer;

public interface LimboSessionHandler {
   default void onSpawn(Limbo server, LimboPlayer player) {
   }

   default void onConfig(Limbo server, LimboPlayer player) {
   }

   default void onMove(double posX, double posY, double posZ) {
   }

   default void onMove(double posX, double posY, double posZ, float yaw, float pitch) {
   }

   default void onRotate(float yaw, float pitch) {
   }

   default void onGround(boolean onGround) {
   }

   default void onTeleport(int teleportID) {
   }

   default void onChat(String chat) {
   }

   default void onGeneric(Object packet) {
   }

   default void onDisconnect() {
   }
}
