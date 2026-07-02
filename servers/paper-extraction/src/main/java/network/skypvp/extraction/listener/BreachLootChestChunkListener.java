package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLootService;
import network.skypvp.extraction.model.BreachState;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public final class BreachLootChestChunkListener implements Listener {

    private final BreachEngine engine;
    private final BreachLootService lootService;

    public BreachLootChestChunkListener(BreachEngine engine, BreachLootService lootService) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.lootService = Objects.requireNonNull(lootService, "lootService");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        this.engine.instanceForWorld(event.getWorld()).ifPresent(instance -> {
            if (instance.state() != BreachState.ACTIVE) {
                return;
            }
            Chunk chunk = event.getChunk();
            this.lootService.activateChunk(instance.world(), chunk.getX(), chunk.getZ());
        });
    }
}
