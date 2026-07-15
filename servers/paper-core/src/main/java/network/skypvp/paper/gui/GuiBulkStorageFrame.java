package network.skypvp.paper.gui;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/** Per-render chrome registration for {@link GuiBulkStorageMenu}. Rebuilt every render pass. */
public final class GuiBulkStorageFrame {

    public enum Kind {
        DECORATION,
        BUTTON
    }

    public record Slot(Kind kind, ItemStack icon, Consumer<GuiClickContext> onClick) {
    }

    private final int size;
    private ItemStack filler;
    private final Map<Integer, Slot> slots = new TreeMap<>();

    public GuiBulkStorageFrame(int size) {
        this.size = size;
    }

    public GuiBulkStorageFrame filler(ItemStack item) {
        this.filler = item;
        return this;
    }

    public GuiBulkStorageFrame decoration(int slot, ItemStack icon) {
        return put(slot, new Slot(Kind.DECORATION, icon, null));
    }

    public GuiBulkStorageFrame button(int slot, ItemStack icon, Consumer<GuiClickContext> onClick) {
        return put(slot, new Slot(Kind.BUTTON, icon, onClick));
    }

    private GuiBulkStorageFrame put(int slot, Slot value) {
        if (slot < 0 || slot >= this.size) {
            throw new IllegalArgumentException("Slot out of range: " + slot);
        }
        this.slots.put(slot, value);
        return this;
    }

    ItemStack filler() {
        return this.filler;
    }

    Map<Integer, Slot> slots() {
        return this.slots;
    }

    Slot slot(int rawSlot) {
        return this.slots.get(rawSlot);
    }
}
