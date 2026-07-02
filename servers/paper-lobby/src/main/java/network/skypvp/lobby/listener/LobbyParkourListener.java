package network.skypvp.lobby.listener;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.state.LobbyRuntimeStateRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class LobbyParkourListener implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final LobbyRuntimeStateRegistry states;
   private final Location spawn;
   private final Map<UUID, Long> runStartMs = new ConcurrentHashMap<>();
   private final Map<UUID, Location> checkpoints = new ConcurrentHashMap<>();
   private final Map<UUID, Long> personalBestMs = new ConcurrentHashMap<>();

   public LobbyParkourListener(LobbyRuntimeStateRegistry states, Location spawn) {
      this.states = states;
      this.spawn = spawn;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onMove(PlayerMoveEvent event) {
      Player p = event.getPlayer();
      Location to = event.getTo();
      if (to != null) {
         Material under = to.clone().subtract(0.0, 1.0, 0.0).getBlock().getType();
         UUID id = p.getUniqueId();
         if (under == Material.LIME_CONCRETE && !this.runStartMs.containsKey(id)) {
            this.runStartMs.put(id, System.currentTimeMillis());
            this.checkpoints.remove(id);
            this.states.setParkourRunning(id, true);
            p.sendMessage(ServerTextUtil.miniMessageComponent("<#FFD700>Parkour run started!<reset> <#888888>Reach the emerald finish block.<reset>"));
         } else if (under == Material.YELLOW_CONCRETE && this.runStartMs.containsKey(id)) {
            this.checkpoints.put(id, to.clone());
            p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Checkpoint saved.<reset>"));
         } else if (under == Material.RED_CONCRETE && this.runStartMs.containsKey(id)) {
            this.resetRun(p, true);
         } else {
            if (under == Material.EMERALD_BLOCK && this.runStartMs.containsKey(id)) {
               long elapsed = System.currentTimeMillis() - this.runStartMs.remove(id);
               this.states.setParkourRunning(id, false);
               this.checkpoints.remove(id);
               long best = this.personalBestMs.getOrDefault(id, Long.MAX_VALUE);
               if (elapsed < best) {
                  this.personalBestMs.put(id, elapsed);
                  p.sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>New Personal Best!</bold><reset> <#FFD700>" + this.format(elapsed) + "<reset>"));
               } else {
                  p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Parkour complete in <reset><#FFD700>" + this.format(elapsed) + "<reset>"));
               }
            }

            if (this.runStartMs.containsKey(id) && to.getY() <= -20.0) {
               Location back = this.checkpoints.getOrDefault(id, this.spawn);
               p.teleportAsync(back);
               p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Run recovered.<reset>"));
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      this.runStartMs.remove(id);
      this.checkpoints.remove(id);
      this.states.setParkourRunning(id, false);
   }

   private void resetRun(Player p, boolean teleportBack) {
      UUID id = p.getUniqueId();
      this.runStartMs.remove(id);
      this.checkpoints.remove(id);
      this.states.setParkourRunning(id, false);
      if (teleportBack) {
         p.teleportAsync(this.spawn);
      }

      p.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Parkour run reset.<reset>"));
   }

   private String format(long ms) {
      long seconds = ms / 1000L;
      long mins = seconds / 60L;
      long rem = seconds % 60L;
      long centis = ms % 1000L / 10L;
      return mins + "m " + rem + "." + (centis < 10L ? "0" : "") + centis + "s";
   }
}
