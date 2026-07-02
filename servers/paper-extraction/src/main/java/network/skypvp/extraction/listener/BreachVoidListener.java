package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachEngine;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Detects players falling into the void (in the hub or inside a breach world) and routes them through
 * {@link BreachEngine#onVoidFall(Player)}: hub players are bounced back to spawn, live raiders are eliminated
 * and dropped into soft-spectator at a spawn point.
 *
 * <p>The {@code PlayerMoveEvent} fires on the player's own region thread, so the engine can safely read player
 * state and call {@code teleportAsync}. A short per-player debounce avoids re-triggering while the recovery
 * teleport is still in flight (movement events keep firing during a fall).
 */
public final class BreachVoidListener implements Listener {

    /** Blocks below the world's minimum build height before we treat the player as "in the void". */
    private static final int VOID_DEPTH_BELOW_MIN = 5;

    private final BreachEngine engine;
    private final Set<UUID> handling = ConcurrentHashMap.newKeySet();

    public BreachVoidListener(BreachEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        double voidY = to.getWorld().getMinHeight() - VOID_DEPTH_BELOW_MIN;
        if (to.getY() >= voidY) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!this.handling.add(playerId)) {
            return;
        }
        try {
            this.engine.onVoidFall(player);
        } finally {
            this.engine.scheduler().runOnPlayerLater(player, () -> this.handling.remove(playerId), 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.handling.remove(event.getPlayer().getUniqueId());
    }
}
