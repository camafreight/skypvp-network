package network.skypvp.extraction.stash;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Logical stash stack size (vanilla {@link ItemStack#getAmount()} caps at 64). */
public final class MaterialStashStackAmount {

    private static final NamespacedKey KEY = new NamespacedKey("skypvp", "stash_stack_amount");

    private MaterialStashStackAmount() {
    }

    public static int read(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Math.max(1, stack.getAmount());
        }
        Integer stored = meta.getPersistentDataContainer().get(KEY, PersistentDataType.INTEGER);
        if (stored != null && stored > 0) {
            return stored;
        }
        return Math.max(1, stack.getAmount());
    }

    /**
     * Internal-storage form: logical amount in the PDC, vanilla amount pinned to 1.
     * The existing PDC value wins over the vanilla amount so round-trips through
     * {@link #withAmount(ItemStack, int)} never collapse the stored count.
     */
    public static ItemStack normalize(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return stack;
        }
        ItemStack copy = stack.clone();
        write(copy, Math.max(1, read(copy)));
        return copy;
    }

    /**
     * Player-facing form: removes the stash counter so withdrawn items stack with clean
     * drops and can never re-deposit their old stored amount.
     */
    public static ItemStack strip(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return stack;
        }
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(KEY);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    public static void write(ItemStack stack, int amount) {
        if (stack == null) {
            return;
        }
        int safe = Math.max(0, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (safe <= 0) {
            meta.getPersistentDataContainer().remove(KEY);
            stack.setItemMeta(meta);
            stack.setAmount(1);
            return;
        }
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, safe);
        stack.setItemMeta(meta);
        stack.setAmount(1);
    }

    public static ItemStack withAmount(ItemStack stack, int amount) {
        if (stack == null) {
            return null;
        }
        ItemStack copy = stack.clone();
        write(copy, amount);
        return copy;
    }
}
