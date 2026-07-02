package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerInventoryManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

public final class ExtractionRaidInventoryListener implements Listener {

    private final PaperCorePlugin core;
    private final BreachEngine engine;

    public ExtractionRaidInventoryListener(PaperCorePlugin core, BreachEngine engine) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        this.handleInventoryChange(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        this.handleInventoryChange(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        this.handleInventoryChange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        this.handleInventoryChange(player);
    }

    private void handleInventoryChange(org.bukkit.entity.HumanEntity entity) {
        if (!(entity instanceof Player player)) {
            return;
        }
        if (!BreachLobbyProtection.isLobbySafe(this.engine, player)) {
            return;
        }
        PlayerInventoryManager inventoryManager = this.core.playerInventoryManager();
        if (inventoryManager == null) {
            return;
        }
        inventoryManager.scheduleRaidInventorySave(player);
    }
}
