package network.skypvp.lobby.task;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class LobbySpawnBalancerTask implements Runnable {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final Location spawn;
   private final int crowdRadius;
   private final int threshold;
   private final int spreadRadius;

   public LobbySpawnBalancerTask(Location spawn, int crowdRadius, int threshold, int spreadRadius) {
      this.spawn = spawn;
      this.crowdRadius = Math.max(4, crowdRadius);
      this.threshold = Math.max(10, threshold);
      this.spreadRadius = Math.max(crowdRadius + 4, spreadRadius);
   }

   // $VF: renamed from: run () void
   @Override
   public void run() {
      World world = this.spawn.getWorld();
      if (world != null) {
         List<Player> crowded = new ArrayList<>();
         double r2 = (double)this.crowdRadius * (double)this.crowdRadius;

         for (Player p : world.getPlayers()) {
            if (!p.hasPermission("skypvp.staff")
               && p.getGameMode() != GameMode.CREATIVE
               && p.getGameMode() != GameMode.SPECTATOR
               && p.getLocation().distanceSquared(this.spawn) <= r2) {
               crowded.add(p);
            }
         }

         if (crowded.size() > this.threshold) {
            for (int i = this.threshold; i < crowded.size(); i++) {
               Player px = crowded.get(i);
               Location target = this.pickSpreadLocation(world);
               px.teleportAsync(target);
               px.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Spawn area is crowded, you were moved nearby for smoother lobby flow.<reset>"));
            }
         }
      }
   }

   private Location pickSpreadLocation(World world) {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      double angle = r.nextDouble(0.0, Math.PI * 2);
      double dist = r.nextDouble((double)this.crowdRadius + 2.0, (double)this.spreadRadius);
      double dx = Math.cos(angle) * dist;
      double dz = Math.sin(angle) * dist;
      Location base = this.spawn.clone().add(dx, 0.0, dz);
      int x = base.getBlockX();
      int z = base.getBlockZ();
      int y = world.getHighestBlockYAt(x, z) + 1;
      Location loc = new Location(world, (double)x + 0.5, (double)y, (double)z + 0.5, this.spawn.getYaw(), this.spawn.getPitch());
      if (loc.distanceSquared(this.spawn) < (double)(this.crowdRadius * this.crowdRadius)) {
         Vector v = loc.toVector().subtract(this.spawn.toVector()).normalize().multiply(this.crowdRadius + 2);
         loc = this.spawn.clone().add(v);
         int y2 = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ()) + 1;
         loc.setY((double)y2);
      }

      return loc;
   }
}
