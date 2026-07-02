package network.skypvp.extraction.gameplay.loot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import network.skypvp.extraction.config.BreachConfigService;
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

    public BreachLootChestGuiService(
            JavaPlugin plugin,
            BreachConfigService configService,
            BreachLootChestRegistry registry,
            BreachLootChestDisplayService displayService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.displayService = Objects.requireNonNull(displayService, "displayService");
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
            BreachLootChestHolder holder = new BreachLootChestHolder(chestLocation);
            Inventory inventory = BreachLootChestLayout.createInventory(
                    plugin,
                    holder,
                    state,
                    configService.lootChestFx(state.tier()),
                    player
            );
            holder.bindInventory(inventory);
            player.openInventory(inventory);
            return true;
        }).orElse(false);
    }

    public void handleClose(Inventory inventory) {
        if (!(inventory.getHolder() instanceof BreachLootChestHolder holder)) {
            return;
        }
        Location location = holder.chestLocation();
        registry.find(location.getWorld(), location).ifPresent(state -> {
                BreachLootChestLayout.syncLootFromInventory(inventory, state);
                displayService.refreshAppearance(location);
        });
    }

    public void lootAll(Player player, Inventory inventory) {
        if (!(inventory.getHolder() instanceof BreachLootChestHolder holder)) {
            return;
        }
        Location location = holder.chestLocation();
        registry.find(location.getWorld(), location).ifPresent(state -> {
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
            displayService.refreshAppearance(location);
        });
    }

    public BreachLootChestRegistry registry() {
        return registry;
    }
}
