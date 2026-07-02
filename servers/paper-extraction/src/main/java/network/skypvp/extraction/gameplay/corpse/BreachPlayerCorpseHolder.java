package network.skypvp.extraction.gameplay.corpse;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class BreachPlayerCorpseHolder implements InventoryHolder {

    private final UUID corpseId;
    private Inventory inventory;

    public BreachPlayerCorpseHolder(UUID corpseId) {
        this.corpseId = corpseId;
    }

    public UUID corpseId() {
        return this.corpseId;
    }

    void bindInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
