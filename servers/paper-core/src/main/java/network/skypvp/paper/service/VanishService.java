package network.skypvp.paper.service;

import network.skypvp.shared.ServerTextUtil;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.RedisEventPublisher;
import network.skypvp.shared.VanishStateEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class VanishService implements CommandExecutor, Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   public static final Set<UUID> VANISHED = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final PaperCorePlugin plugin;

   public VanishService(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player staff) {
         if (!staff.hasPermission("skypvp.staff")) {
            staff.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>No permission.<reset>"));
            return true;
         } else {
            if (VANISHED.contains(staff.getUniqueId())) {
               this.unvanish(staff);
            } else {
               this.vanish(staff);
            }

            return true;
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Only players can use /vanish."));
         return true;
      }
   }

   public void vanish(Player staff) {
      VANISHED.add(staff.getUniqueId());

      for (Player other : this.plugin.getServer().getOnlinePlayers()) {
         if (!other.hasPermission("skypvp.staff")) {
            other.hidePlayer(this.plugin, staff);
         }
      }

      this.publishVanishState(staff, true);
      staff.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>You are now <reset><#FF5555><bold>VANISHED</bold><reset><#888888>. Non-staff cannot see you.<reset>"));
   }

   public void unvanish(Player staff) {
      VANISHED.remove(staff.getUniqueId());

      for (Player other : this.plugin.getServer().getOnlinePlayers()) {
         other.showPlayer(this.plugin, staff);
      }

      this.publishVanishState(staff, false);
      staff.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>You are now <reset><#55FF55><bold>VISIBLE</bold><reset><#888888> to all players.<reset>"));
   }

   private void publishVanishState(Player player, boolean vanished) {
      RedisEventPublisher pub = this.plugin.redisPublisher();
      if (pub != null) {
         pub.publishJson("SkyPvP:network:vanish", new VanishStateEvent(player.getUniqueId().toString(), player.getName(), vanished));
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onJoin(PlayerJoinEvent event) {
      Player joining = event.getPlayer();

      for (UUID vanishedId : VANISHED) {
         Player vanished = this.plugin.getServer().getPlayer(vanishedId);
         if (vanished != null && !joining.hasPermission("skypvp.staff")) {
            joining.hidePlayer(this.plugin, vanished);
         }
      }

      if (VANISHED.contains(joining.getUniqueId())) {
         this.plugin.platformScheduler().runOnPlayerLater(joining, () -> this.vanish(joining), 5L);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      VANISHED.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onRespawn(PlayerRespawnEvent event) {
      Player respawned = event.getPlayer();
      if (VANISHED.contains(respawned.getUniqueId())) {
         this.plugin.platformScheduler().runOnPlayerLater(respawned, () -> this.vanish(respawned), 5L);
      }
   }
}
