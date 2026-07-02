package network.skypvp.extraction.gameplay.loot;

import java.util.Arrays;
import org.bukkit.inventory.ItemStack;

public final class BreachLootChestState {

    private final String tier;
    private final ItemStack[] loot;
    private volatile boolean opened;

    public BreachLootChestState(String tier) {
        this.tier = tier == null || tier.isBlank() ? "common" : tier.trim().toLowerCase();
        this.loot = new ItemStack[BreachLootChestLayout.LOOT_SLOTS.length];
    }

    public String tier() {
        return tier;
    }

    public boolean opened() {
        return opened;
    }

    public void markOpened() {
        this.opened = true;
    }

    public boolean isEmpty() {
        for (ItemStack item : loot) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    public ItemStack[] lootSnapshot() {
        ItemStack[] copy = new ItemStack[loot.length];
        for (int i = 0; i < loot.length; i++) {
            copy[i] = loot[i] == null ? null : loot[i].clone();
        }
        return copy;
    }

    public void replaceLoot(ItemStack[] next) {
        Arrays.fill(loot, null);
        if (next == null) {
            return;
        }
        for (int i = 0; i < loot.length && i < next.length; i++) {
            loot[i] = next[i] == null || next[i].getType().isAir() ? null : next[i].clone();
        }
    }

    public void populateIfEmpty(ItemStack[] rolled) {
        if (!isEmpty() || rolled == null) {
            return;
        }
        for (int i = 0; i < loot.length && i < rolled.length; i++) {
            if (rolled[i] != null && !rolled[i].getType().isAir()) {
                loot[i] = rolled[i].clone();
            }
        }
    }
}
