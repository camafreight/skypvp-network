package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerInventoryManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ExtractionInventoryQuitListener implements Listener {

    private final BreachEngine engine;
    private final PaperCorePlugin core;

    public ExtractionInventoryQuitListener(BreachEngine engine, PaperCorePlugin core) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.core = Objects.requireNonNull(core, "core");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (this.engine.instanceFor(player).isPresent()) {
            return;
        }
        PlayerInventoryManager inventoryManager = this.core.playerInventoryManager();
        if (inventoryManager != null) {
            inventoryManager.persistCurrentInventoryOnQuit(player);
        }
    }
}
