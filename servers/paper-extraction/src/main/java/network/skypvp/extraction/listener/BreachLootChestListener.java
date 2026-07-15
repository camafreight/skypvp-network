package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.loot.BreachLootChestDisplayService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestGuiService;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.NpcLibrary;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

/** Opens breach loot chest GUIs (inventory clicks handled by {@link network.skypvp.paper.gui.GuiManager}). */
public final class BreachLootChestListener implements Listener {

    private final BreachEngine engine;
    private final BreachLootChestGuiService guiService;
    private final NpcLibrary npcLibrary;
    private final org.bukkit.NamespacedKey blockPropTypeKey;

    public BreachLootChestListener(
            BreachEngine engine,
            BreachLootChestGuiService guiService,
            PaperCorePlugin core,
            org.bukkit.plugin.java.JavaPlugin plugin
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.guiService = Objects.requireNonNull(guiService, "guiService");
        this.npcLibrary = Objects.requireNonNull(core, "core").npcLibrary();
        this.blockPropTypeKey = this.npcLibrary.blockPropTypeKey();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction)) {
            return;
        }
        Entity clicked = event.getRightClicked();
        String propType = clicked.getPersistentDataContainer().get(this.blockPropTypeKey, PersistentDataType.STRING);
        if (!BreachLootChestDisplayService.PROP_TYPE.equals(propType)) {
            return;
        }
        this.npcLibrary.readBlockPropAnchor(clicked).ifPresent(location -> {
            if (!isActiveBreachChest(event.getPlayer(), location)) {
                return;
            }
            event.setCancelled(true);
            this.guiService.open(event.getPlayer(), location);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }
        if (!this.isActiveBreachChest(event.getPlayer(), block.getLocation())) {
            return;
        }
        event.setCancelled(true);
        this.guiService.open(event.getPlayer(), block.getLocation());
    }

    private boolean isActiveBreachChest(Player player, org.bukkit.Location location) {
        if (this.engine.isSpectating(player)) {
            return false;
        }
        return engine.instanceFor(player)
                .filter(instance -> instance.state() == BreachState.ACTIVE)
                .filter(instance -> instance.world() != null && instance.world().equals(location.getWorld()))
                .isPresent()
                && guiService.registry().find(location.getWorld(), location)
                        .filter(state -> !state.isEmpty())
                        .isPresent();
    }
}
