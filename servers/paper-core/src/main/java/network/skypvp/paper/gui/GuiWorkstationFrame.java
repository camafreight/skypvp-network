package network.skypvp.paper.gui;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/**
 * Per-render slot registration handed to {@link GuiWorkstationMenu#buildFrame}. Subclasses declare what each slot
 * looks like and does; the framework performs all the actual inventory painting and event handling. A frame is a
 * throwaway description rebuilt on every render, so it always reflects the current anchor state.
 */
public final class GuiWorkstationFrame {

    enum Kind {
        DECORATION,
        BUTTON,
        SOCKET
    }

    record Slot(Kind kind, ItemStack icon, Consumer<GuiClickContext> onClick, GuiWorkstationSocket socket) {
    }

    private final int size;
    private ItemStack filler;
    // TreeMap so slot iteration (e.g. shift-insert socket selection) is deterministic ascending by raw slot,
    // which makes shift-clicking fill the first/lowest matching socket instead of a hash-order-random one.
    private final Map<Integer, Slot> slots = new TreeMap<>();

    GuiWorkstationFrame(int size) {
        this.size = size;
    }

    /** Background item painted into every non-declared slot (except the anchor slot). */
    public GuiWorkstationFrame filler(ItemStack item) {
        this.filler = item;
        return this;
    }

    /** A purely visual, non-interactive icon. */
    public GuiWorkstationFrame decoration(int slot, ItemStack icon) {
        return put(slot, new Slot(Kind.DECORATION, icon, null, null));
    }

    /** A clickable button (close, back, info actions, etc.). */
    public GuiWorkstationFrame button(int slot, ItemStack icon, Consumer<GuiClickContext> onClick) {
        return put(slot, new Slot(Kind.BUTTON, icon, onClick, null));
    }

    /** A modifier socket that installs into / removes from the anchor item. */
    public GuiWorkstationFrame socket(int slot, GuiWorkstationSocket socket) {
        return put(slot, new Slot(Kind.SOCKET, socket.icon(), null, socket));
    }

    private GuiWorkstationFrame put(int slot, Slot value) {
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
