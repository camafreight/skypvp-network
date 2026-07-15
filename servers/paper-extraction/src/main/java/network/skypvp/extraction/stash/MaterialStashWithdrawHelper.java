package network.skypvp.extraction.stash;

import java.util.Map;
import network.skypvp.extraction.crafting.MaterialStashConstants;
import network.skypvp.extraction.crafting.MaterialStashHelper;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Withdraws oversized stash stacks into normal player inventory slots (64 max each). */
final class MaterialStashWithdrawHelper {

    private MaterialStashWithdrawHelper() {
    }

    static int playerStackLimit(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 64;
        }
        return Math.max(1, stack.getMaxStackSize());
    }

    static int withdrawAllToInventory(
            CustomItemService service,
            Player player,
            MaterialStashHolder holder,
            int contentIndex
    ) {
        ItemStack stashStack = holder.get(contentIndex);
        if (stashStack == null || stashStack.getType().isAir()) {
            return 0;
        }
        return transferFromStash(service, player, holder, contentIndex, MaterialStashStackAmount.read(stashStack));
    }

    static int withdrawToCursor(
            CustomItemService service,
            Player player,
            MaterialStashHolder holder,
            int contentIndex,
            int requested
    ) {
        if (requested <= 0) {
            return 0;
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            return 0;
        }
        ItemStack stashStack = holder.get(contentIndex);
        if (stashStack == null || stashStack.getType().isAir()) {
            return 0;
        }
        int stored = MaterialStashStackAmount.read(stashStack);
        int take = Math.min(requested, stored);
        take = Math.min(take, playerStackLimit(stashStack));
        if (take <= 0) {
            return 0;
        }
        ItemStack picked = MaterialStashStackAmount.strip(stashStack);
        picked.setAmount(take);
        player.setItemOnCursor(picked);
        return reduceStashSlot(holder, contentIndex, stored, take);
    }

    static int depositFromCursor(
            CustomItemService service,
            Player player,
            MaterialStashHolder holder,
            int contentIndex,
            int rawSlot,
            int maxCapacity
    ) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir() || !MaterialStashHelper.isCraftingMaterial(service, cursor)) {
            return 0;
        }
        int depositAmount = depositToSlot(service, holder, contentIndex, cursor, maxCapacity);
        if (depositAmount <= 0) {
            return 0;
        }
        consumeCursor(player, cursor, depositAmount);
        return depositAmount;
    }

    static int depositToSlot(
            CustomItemService service,
            MaterialStashHolder holder,
            int contentIndex,
            ItemStack stack,
            int maxCapacity
    ) {
        if (stack == null || stack.getType().isAir() || !MaterialStashHelper.isCraftingMaterial(service, stack)) {
            return 0;
        }
        int allowed = MaterialStashAccess.depositableAmount(
                holder.usedCapacity(), maxCapacity, stack.getAmount());
        if (allowed <= 0) {
            return 0;
        }
        ItemStack existing = holder.get(contentIndex);
        int depositAmount;
        if (existing != null && !existing.getType().isAir()) {
            if (!MaterialStashHelper.sameMaterial(service, existing, stack)) {
                return 0;
            }
            int current = MaterialStashStackAmount.read(existing);
            int spaceInStack = Math.max(0, MaterialStashConstants.MAX_STACK_SIZE - current);
            depositAmount = Math.min(Math.min(allowed, stack.getAmount()), spaceInStack);
            if (depositAmount <= 0) {
                return 0;
            }
            holder.put(contentIndex, MaterialStashStackAmount.withAmount(existing, current + depositAmount));
        } else {
            depositAmount = Math.min(allowed, stack.getAmount());
            // Explicit logical amount — never trust a stray counter tag on the incoming player stack.
            holder.put(contentIndex, MaterialStashStackAmount.withAmount(stack, depositAmount));
        }
        return depositAmount;
    }

    private static int transferFromStash(
            CustomItemService service,
            Player player,
            MaterialStashHolder holder,
            int contentIndex,
            int requested
    ) {
        ItemStack stashStack = holder.get(contentIndex);
        if (stashStack == null || stashStack.getType().isAir() || requested <= 0) {
            return 0;
        }
        int stored = MaterialStashStackAmount.read(stashStack);
        int take = Math.min(requested, stored);
        ItemStack toGive = MaterialStashStackAmount.strip(stashStack);
        toGive.setAmount(take);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        int notMoved = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int moved = take - notMoved;
        if (moved <= 0) {
            return 0;
        }
        reduceStashSlot(holder, contentIndex, stored, moved);
        return moved;
    }

    private static int reduceStashSlot(
            MaterialStashHolder holder,
            int contentIndex,
            int storedAmount,
            int removed
    ) {
        int remaining = storedAmount - removed;
        if (remaining <= 0) {
            holder.remove(contentIndex);
        } else {
            ItemStack existing = holder.get(contentIndex);
            holder.put(contentIndex, MaterialStashStackAmount.withAmount(existing, remaining));
        }
        return removed;
    }

    private static void consumeCursor(Player player, ItemStack cursor, int amount) {
        int remaining = cursor.getAmount() - amount;
        if (remaining <= 0) {
            player.setItemOnCursor(null);
            return;
        }
        ItemStack updated = cursor.clone();
        updated.setAmount(remaining);
        player.setItemOnCursor(updated);
    }
}
