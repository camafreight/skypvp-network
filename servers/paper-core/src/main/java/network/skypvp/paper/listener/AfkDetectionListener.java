package network.skypvp.paper.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public final class AfkDetectionListener implements Listener {
   private static final long MOVEMENT_SAMPLE_INTERVAL_TICKS = 40L;
   private static final long AFK_THRESHOLD_MINUTES = 5L;
   private static final double MIN_MOVEMENT_DISTANCE = 0.5;
   private final PaperCorePlugin plugin;
   private final Map<UUID, AfkDetectionListener.PlayerAfkState> playerStates = new ConcurrentHashMap<>();

   public AfkDetectionListener(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onPlayerMove(PlayerMoveEvent event) {
      Player player = event.getPlayer();
      UUID id = player.getUniqueId();
      AfkDetectionListener.PlayerAfkState state = this.playerStates.computeIfAbsent(id, k -> new AfkDetectionListener.PlayerAfkState());
      Location from = event.getFrom();
      Location to = event.getTo();
      if (from != null && to != null) {
         double distance = from.distance(to);
         if (distance >= 0.5) {
            state.recordMovement();
         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onToggleSneak(PlayerToggleSneakEvent event) {
      this.recordActivity(event.getPlayer());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEvent event) {
      this.recordActivity(event.getPlayer());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBlockBreak(BlockBreakEvent event) {
      this.recordActivity(event.getPlayer());
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBlockPlace(BlockPlaceEvent event) {
      this.recordActivity(event.getPlayer());
   }

   public boolean isAfk(UUID player) {
      AfkDetectionListener.PlayerAfkState state = this.playerStates.get(player);
      return state == null ? false : state.isAfkNow();
   }

   public long getAfkMinutes(UUID player) {
      AfkDetectionListener.PlayerAfkState state = this.playerStates.get(player);
      return state == null ? 0L : state.getAfkMinutes();
   }

   public void onPlayerQuit(UUID player) {
      this.playerStates.remove(player);
   }

   private void recordActivity(Player player) {
      AfkDetectionListener.PlayerAfkState state = this.playerStates.computeIfAbsent(player.getUniqueId(), k -> new AfkDetectionListener.PlayerAfkState());
      state.recordInteraction();
   }

   private static final class PlayerAfkState {
      private long lastActivityTime = System.currentTimeMillis();
      private Location lastMovementLocation = null;

      private PlayerAfkState() {
      }

      void recordMovement() {
         this.lastActivityTime = System.currentTimeMillis();
      }

      void recordInteraction() {
         this.lastActivityTime = System.currentTimeMillis();
      }

      boolean isAfkNow() {
         long inactiveMs = System.currentTimeMillis() - this.lastActivityTime;
         long thresholdMs = 300000L;
         return inactiveMs > thresholdMs;
      }

      long getAfkMinutes() {
         if (!this.isAfkNow()) {
            return 0L;
         } else {
            long inactiveMs = System.currentTimeMillis() - this.lastActivityTime;
            return inactiveMs / 60000L;
         }
      }
   }
}
