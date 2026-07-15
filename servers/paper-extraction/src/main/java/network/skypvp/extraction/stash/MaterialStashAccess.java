package network.skypvp.extraction.stash;

import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** Capacity and slot limits for a player's current stash tier. */
public final class MaterialStashAccess {

    private MaterialStashAccess() {
    }

    public static int usedCapacity(Map<Integer, ItemStack> slots) {
        if (slots == null || slots.isEmpty()) {
            return 0;
        }
        long total = 0L;
        for (ItemStack stack : slots.values()) {
            if (stack != null && !stack.getType().isAir()) {
                total += MaterialStashStackAmount.read(stack);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static int capacityPercent(int used, int max) {
        if (max <= 0) {
            return 100;
        }
        return (int) Math.min(100L, Math.round((used * 100.0) / max));
    }

    public static boolean hasCapacityRoom(int used, int max, int incoming) {
        if (incoming <= 0) {
            return true;
        }
        return (long) used + incoming <= max;
    }

    public static int depositableAmount(int used, int max, int requested) {
        if (requested <= 0) {
            return 0;
        }
        long room = (long) max - used;
        if (room <= 0L) {
            return 0;
        }
        return (int) Math.min(requested, room);
    }
}
