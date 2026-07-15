package network.skypvp.extraction.gameplay.loot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachLootChestGuiService {

    private final JavaPlugin plugin;
    private final BreachConfigService configService;
    private final BreachLootChestRegistry registry;
    private final BreachLootChestDisplayService displayService;
    private final GuiManager guiManager;
    private final PaperCorePlugin core;

    public BreachLootChestGuiService(
            JavaPlugin plugin,
            PaperCorePlugin core,
            BreachConfigService configService,
            BreachLootChestRegistry registry,
            BreachLootChestDisplayService displayService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.displayService = Objects.requireNonNull(displayService, "displayService");
        this.guiManager = core.guiManager();
    }

    public boolean open(Player player, Location chestLocation) {
        if (player == null || chestLocation == null || chestLocation.getWorld() == null) {
            return false;
        }
        return registry.find(chestLocation.getWorld(), chestLocation)
                .filter(state -> !state.isEmpty())
                .map(state -> {
            state.markOpened();
            displayService.refreshAppearance(chestLocation);
            BreachLootChestMenu menu = new BreachLootChestMenu(
                    plugin,
                    this,
                    state,
                    chestLocation,
                    core.coreHotbarService()
            );
            guiManager.open(player, menu);
            return true;
        }).orElse(false);
    }

    public void handleClose(Inventory inventory, Location chestLocation) {
        if (inventory == null || chestLocation == null || chestLocation.getWorld() == null) {
            return;
        }
        registry.find(chestLocation.getWorld(), chestLocation).ifPresent(state -> {
            BreachLootChestLayout.syncLootFromInventory(inventory, state);
            displayService.refreshAppearance(chestLocation);
        });
    }

    public void lootAll(Player player, Inventory inventory, BreachLootChestState state, Location chestLocation) {
        if (player == null || inventory == null || state == null || chestLocation == null) {
            return;
        }
        ItemStack[] loot = state.lootSnapshot();
        for (int i = 0; i < loot.length; i++) {
            ItemStack item = loot[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
            if (leftover.isEmpty()) {
                loot[i] = null;
            } else {
                loot[i] = leftover.values().iterator().next().clone();
            }
        }
        state.replaceLoot(loot);
        BreachLootChestLayout.fillLoot(inventory, state);
        displayService.refreshAppearance(chestLocation);
    }

    public BreachLootChestRegistry registry() {
        return registry;
    }
}
