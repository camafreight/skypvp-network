package network.skypvp.paper.resourcepack;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Applies the network resource pack on join and kicks players who refuse a forced pack.
 */
public final class ResourcePackListener implements Listener {

    private final ResourcePackService service;

    public ResourcePackListener(ResourcePackService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!service.isActive()) {
            return;
        }
        // MONITOR so locale/session setup can finish; pack prompt is independent of inventory prep.
        service.sendTo(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        service.handleStatus(event.getPlayer(), event.getID(), event.getStatus());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer().getUniqueId());
    }
}
