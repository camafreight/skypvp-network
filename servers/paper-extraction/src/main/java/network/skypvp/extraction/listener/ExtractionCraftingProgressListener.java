package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.UUID;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Reloads armory progress from PostgreSQL when players join (safe across k8s pods). */
public final class ExtractionCraftingProgressListener implements Listener {

    private final BlueprintDiscoveryService discovery;
    private final CraftingMaterialService materials;

    public ExtractionCraftingProgressListener(
            BlueprintDiscoveryService discovery,
            CraftingMaterialService materials
    ) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        discovery.evictPlayer(playerId);
        materials.evictPlayer(playerId);
    }
}
