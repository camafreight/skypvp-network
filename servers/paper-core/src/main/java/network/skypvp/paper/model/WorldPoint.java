package network.skypvp.paper.model;

import org.bukkit.Location;

/** A serializable world position: {@code world} name, {@code x}/{@code y}/{@code z}, and {@code yaw}/{@code pitch}. */
public final class WorldPoint {
   public String world = "world";
   public double x = 0.5;
   public double y = 100.0;
   public double z = 0.5;
   public float yaw = 0.0F;
   public float pitch = 0.0F;

   public WorldPoint() {
   }

   public WorldPoint(String world, double x, double y, double z, float yaw, float pitch) {
      this.world = world;
      this.x = x;
      this.y = y;
      this.z = z;
      this.yaw = yaw;
      this.pitch = pitch;
   }

   public static WorldPoint fromLocation(Location location) {
      if (location == null) {
         return new WorldPoint();
      } else {
         String worldName = location.getWorld() == null ? "world" : location.getWorld().getName();
         return new WorldPoint(worldName, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
      }
   }
}
