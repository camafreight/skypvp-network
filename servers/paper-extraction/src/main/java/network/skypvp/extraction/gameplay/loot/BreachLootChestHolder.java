package network.skypvp.extraction.gameplay.loot;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class BreachLootChestHolder implements InventoryHolder {

    private final Location chestLocation;
    private Inventory inventory;

    public BreachLootChestHolder(Location chestLocation) {
        this.chestLocation = chestLocation.clone();
    }

    public Location chestLocation() {
        return chestLocation.clone();
    }

    void bindInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
