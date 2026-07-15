package network.skypvp.extraction.crafting;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import network.skypvp.extraction.stash.MaterialStashAccess;
import network.skypvp.extraction.stash.MaterialStashStackAmount;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.ItemStack;

/** Merge / count helpers for physical crafting material stash slots. */
public final class MaterialStashHelper {

    private MaterialStashHelper() {
    }

    static int countMaterial(Map<Integer, ItemStack> slots, CustomItemService service, String materialId) {
        if (slots == null || service == null || materialId == null || materialId.isBlank()) {
            return 0;
        }
        String normalized = materialId.trim().toLowerCase(Locale.ROOT);
        int total = 0;
        for (ItemStack stack : slots.values()) {
            if (CraftingMaterialItemFactory.isMaterial(service, stack, normalized)) {
                total += MaterialStashStackAmount.read(stack);
            }
        }
        return total;
    }

    public static boolean isCraftingMaterial(CustomItemService service, ItemStack stack) {
        return CraftingMaterialItemFactory.materialIdOf(service, stack).isPresent();
    }

    static Optional<String> materialId(CustomItemService service, ItemStack stack) {
        return CraftingMaterialItemFactory.materialIdOf(service, stack);
    }

    public static boolean sameMaterial(CustomItemService service, ItemStack left, ItemStack right) {
        Optional<String> a = materialId(service, left);
        Optional<String> b = materialId(service, right);
        return a.isPresent() && a.equals(b);
    }

    public static boolean deposit(
            Map<Integer, ItemStack> slots,
            CustomItemService service,
            ItemStack incoming,
            int unlockedSlots,
            int maxCapacity
    ) {
        if (slots == null || service == null || incoming == null || incoming.getType().isAir()) {
            return false;
        }
        if (!isCraftingMaterial(service, incoming)) {
            return false;
        }
        int used = MaterialStashAccess.usedCapacity(slots);
        int allowed = MaterialStashAccess.depositableAmount(used, maxCapacity, incoming.getAmount());
        if (allowed <= 0) {
            return false;
        }
        int remaining = allowed;
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            if (entry.getKey() >= unlockedSlots) {
                continue;
            }
            ItemStack existing = entry.getValue();
            if (!sameMaterial(service, existing, incoming)) {
                continue;
            }
            long merged = (long) MaterialStashStackAmount.read(existing) + remaining;
            int placed = (int) Math.min(MaterialStashConstants.MAX_STACK_SIZE, merged);
            slots.put(entry.getKey(), MaterialStashStackAmount.withAmount(existing, placed));
            return true;
        }
        for (int index = 0; index < unlockedSlots; index++) {
            if (slots.containsKey(index)) {
                continue;
            }
            int placed = Math.min(remaining, MaterialStashConstants.MAX_STACK_SIZE);
            slots.put(index, MaterialStashStackAmount.withAmount(incoming, placed));
            return true;
        }
        return false;
    }

    public static boolean deposit(Map<Integer, ItemStack> slots, CustomItemService service, ItemStack incoming) {
        return deposit(slots, service, incoming, MaterialStashConstants.MAX_SLOTS, Integer.MAX_VALUE);
    }

    static boolean withdraw(Map<Integer, ItemStack> slots, CustomItemService service, Map<String, Integer> costs) {
        if (slots == null || service == null || costs == null || costs.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Integer> cost : costs.entrySet()) {
            if (countMaterial(slots, service, cost.getKey()) < cost.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Integer> cost : costs.entrySet()) {
            int stillNeed = cost.getValue();
            String materialId = cost.getKey();
            for (int index = 0; index < MaterialStashConstants.MAX_SLOTS && stillNeed > 0; index++) {
                ItemStack stack = slots.get(index);
                if (stack == null || !CraftingMaterialItemFactory.isMaterial(service, stack, materialId)) {
                    continue;
                }
                int take = Math.min(stillNeed, MaterialStashStackAmount.read(stack));
                stillNeed -= take;
                int left = MaterialStashStackAmount.read(stack) - take;
                if (left <= 0) {
                    slots.remove(index);
                } else {
                    slots.put(index, MaterialStashStackAmount.withAmount(stack, left));
                }
            }
        }
        return true;
    }

    static Map<Integer, ItemStack> copy(Map<Integer, ItemStack> source) {
        Map<Integer, ItemStack> copy = new HashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((index, stack) -> {
            if (stack != null && !stack.getType().isAir()) {
                copy.put(index, stack.clone());
            }
        });
        return copy;
    }
}
