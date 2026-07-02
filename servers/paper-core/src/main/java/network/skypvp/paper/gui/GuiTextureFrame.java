package network.skypvp.paper.gui;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * One animation frame: a sparse map of inventory slots to the item stacks shown on that frame.
 * Only include background / chrome slots here — interactive button slots are rendered separately.
 */
public record GuiTextureFrame(Map<Integer, ItemStack> items) {

    public GuiTextureFrame {
        Map<Integer, ItemStack> normalized = new HashMap<>();
        if (items != null) {
            items.forEach((slot, item) -> {
                if (slot != null && slot >= 0 && item != null && !item.getType().isAir()) {
                    normalized.put(slot, item.clone());
                }
            });
        }
        items = Map.copyOf(normalized);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Integer, ItemStack> items = new HashMap<>();

        public Builder slot(int slot, ItemStack item) {
            if (slot >= 0 && item != null && !item.getType().isAir()) {
                this.items.put(slot, item.clone());
            }
            return this;
        }

        public Builder chrome(ItemStack item, Iterable<Integer> slots) {
            if (item == null || item.getType().isAir()) {
                return this;
            }
            for (int slot : slots) {
                this.items.put(slot, item.clone());
            }
            return this;
        }

        public Builder uniform(ItemStack item, int... slots) {
            if (item == null || item.getType().isAir() || slots == null) {
                return this;
            }
            for (int slot : slots) {
                this.items.put(slot, item.clone());
            }
            return this;
        }

        public GuiTextureFrame build() {
            return new GuiTextureFrame(this.items);
        }
    }
}
