package network.skypvp.lobby.game.parkour;

import network.skypvp.shared.ServerTextUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ParkourSetupCommand implements CommandExecutor {
   private final ParkourManager parkourManager;

   public ParkourSetupCommand(ParkourManager parkourManager) {
      this.parkourManager = parkourManager;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (sender instanceof Player p) {
         if (!p.hasPermission("skypvp.admin.parkoursetup")) {
            p.sendMessage(ServerTextUtil.component("&cNo permission."));
            return true;
         } else if (args.length < 2) {
            p.sendMessage(ServerTextUtil.component("&cUsage: /parkoursetup <create|setstart|addcheckpoint|setfinish> <trackname>"));
            return true;
         } else {
            String action = args[0].toLowerCase();
            String trackName = args[1];
            Location loc = p.getLocation().clone().subtract(0.0, 1.0, 0.0);
            ParkourLocation pLoc = new ParkourLocation(loc);
            if (action.equals("create")) {
               if (this.parkourManager.getTrack(trackName) != null) {
                  p.sendMessage(ServerTextUtil.component("&cTrack already exists."));
                  return true;
               } else {
                  ParkourTrack track = new ParkourTrack(trackName);
                  this.parkourManager.saveTrackRemote(track);
                  p.sendMessage(ServerTextUtil.component("&a" + "Created track: " + trackName));
                  return true;
               }
            } else {
               ParkourTrack track = this.parkourManager.getTrack(trackName);
               if (track == null) {
                  p.sendMessage(ServerTextUtil.component("&c" + "Track not found: " + trackName));
                  return true;
               } else {
                  switch (action) {
                     case "setstart":
                        track.setStart(pLoc);
                        this.parkourManager.saveTrackRemote(track);
                        p.sendMessage(ServerTextUtil.component("&aStart set at your feet."));
                        break;
                     case "addcheckpoint":
                        track.addCheckpoint(pLoc);
                        this.parkourManager.saveTrackRemote(track);
                        p.sendMessage(ServerTextUtil.component("&a" + "Checkpoint added at your feet. Total: " + track.getCheckpoints().size()));
                        break;
                     case "setfinish":
                        track.setFinish(pLoc);
                        this.parkourManager.saveTrackRemote(track);
                        p.sendMessage(ServerTextUtil.component("&aFinish set at your feet."));
                        break;
                     default:
                        p.sendMessage(ServerTextUtil.component("&cUnknown action."));
                  }

                  return true;
               }
            }
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Players only."));
         return true;
      }
   }
}
