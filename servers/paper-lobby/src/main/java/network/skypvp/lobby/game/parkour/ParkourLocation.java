package network.skypvp.lobby.game.parkour;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class ParkourLocation {
   public String world;
   // $VF: renamed from: x int
   public int x;
   public int y;
   public int z;

   public ParkourLocation() {
   }

   public ParkourLocation(Location loc) {
      this.world = loc.getWorld().getName();
      this.x = loc.getBlockX();
      this.y = loc.getBlockY();
      this.z = loc.getBlockZ();
   }

   public Location toLocation() {
      World w = Bukkit.getWorld(this.world);
      return w == null ? null : new Location(w, (double)this.x, (double)this.y, (double)this.z);
   }

   public boolean matches(Location loc) {
      return loc != null && loc.getWorld() != null
         ? loc.getWorld().getName().equals(this.world) && loc.getBlockX() == this.x && loc.getBlockY() == this.y && loc.getBlockZ() == this.z
         : false;
   }
}
