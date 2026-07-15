package network.skypvp.paper.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * GUI-library primitive that turns the player's own main inventory (36 slots: hotbar 0–8 +
 * storage 9–35) into a menu canvas, instead of opening a separate 54-slot container.
 *
 * <p>Armor (36–39) and offhand (40) are never touched. Callers snapshot the main grid, lay a
 * "canvas" of items over it (with locked slots masked by filler items), then later collect the
 * canvas back and restore the snapshot. All methods must run on the player's region thread.
 *
 * <p>Used by the extraction backpack view; any future feature that wants an in-place inventory
 * UI (kit previews, loadout editors) can reuse the same primitives.
 */
public final class PlayerInventoryCanvas {

    /** Main-grid slot count covered by the canvas (hotbar + 3 storage rows). */
    public static final int MAIN_SLOTS = 36;

    private PlayerInventoryCanvas() {
    }

    /** Copies main slots 0–35 (entries may be null). */
    public static ItemStack[] snapshotMain(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] snapshot = new ItemStack[MAIN_SLOTS];
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            snapshot[slot] = item == null ? null : item.clone();
        }
        return snapshot;
    }

    /**
     * Lays the canvas over the main grid: {@code contents} fill editable slots in ascending
     * slot order, locked slots get {@code filler}. Anything that doesn't fit the editable
     * area is returned so the caller can decide (drop, stash, …).
     */
    public static List<ItemStack> applyCanvas(Player player, List<ItemStack> contents, boolean[] editable, ItemStack filler) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> overflow = new ArrayList<>();
        int cursor = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            if (editable != null && slot < editable.length && editable[slot]) {
                ItemStack next = cursor < contents.size() ? contents.get(cursor++) : null;
                inventory.setItem(slot, next);
            } else {
                inventory.setItem(slot, filler == null ? null : filler.clone());
            }
        }
        for (; cursor < contents.size(); cursor++) {
            ItemStack leftover = contents.get(cursor);
            if (leftover != null && !leftover.getType().isAir()) {
                overflow.add(leftover);
            }
        }
        return overflow;
    }

    /** Collects every non-filler item from editable slots (canvas → storage) without clearing. */
    public static List<ItemStack> collectCanvas(Player player, boolean[] editable, Predicate<ItemStack> isFiller) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> collected = new ArrayList<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (isFiller != null && isFiller.test(item)) {
                continue;
            }
            if (editable == null || (slot < editable.length && editable[slot])) {
                collected.add(item.clone());
            } else {
                // A real item somehow sits on a locked slot (plugin gave it, pickup edge case)
                // — never destroy it, always collect.
                collected.add(item.clone());
            }
        }
        return collected;
    }

    /** Restores a {@link #snapshotMain} snapshot into the main grid, overwriting the canvas. */
    public static void restoreMain(Player player, ItemStack[] snapshot) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack item = snapshot != null && slot < snapshot.length ? snapshot[slot] : null;
            inventory.setItem(slot, item);
        }
    }

    /** Editable-slot mask for {@code rows} unlocked rows: storage rows top-down, hotbar last. */
    public static boolean[] rowMask(int rows) {
        boolean[] editable = new boolean[MAIN_SLOTS];
        int clamped = Math.max(0, Math.min(4, rows));
        // Row 1 → slots 9–17, row 2 → 18–26, row 3 → 27–35, row 4 → hotbar 0–8.
        for (int row = 0; row < clamped; row++) {
            if (row < 3) {
                for (int col = 0; col < 9; col++) {
                    editable[9 + row * 9 + col] = true;
                }
            } else {
                for (int col = 0; col < 9; col++) {
                    editable[col] = true;
                }
            }
        }
        return editable;
    }
}
