package network.skypvp.paper.inventory.vault;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/** Smart vault deposit: merge stacks and fill slots without forcing page jumps. */
final class VaultDepositHelper {

    private VaultDepositHelper() {
    }

    /**
     * Deposits as much of {@code stack} as possible into unlocked vault slots.
     * Prefers the current page, then the rest of the vault. Merges similar stacks before using empty slots.
     *
     * @param preferredVaultIndex optional target slot ({@code >= 0}); tried first when depositable
     * @return amount deposited
     */
    static int deposit(VaultHolder holder, ItemStack stack, int preferredVaultIndex) {
        if (holder == null || stack == null || stack.getType().isAir()) {
            return 0;
        }
        int remaining = stack.getAmount();
        if (remaining <= 0) {
            return 0;
        }
        int limit = holder.depositableSlotLimit();
        int page = holder.page();

        if (preferredVaultIndex >= 0 && holder.isDepositableVaultIndex(preferredVaultIndex)) {
            remaining -= depositIntoIndex(holder, stack, preferredVaultIndex, remaining);
            if (remaining <= 0) {
                return stack.getAmount();
            }
        }

        remaining -= mergeIntoIndices(holder, stack, indicesForPage(holder, page, limit), remaining);
        if (remaining <= 0) {
            return stack.getAmount();
        }
        remaining -= fillEmptyIndices(holder, stack, indicesForPage(holder, page, limit), remaining);
        if (remaining <= 0) {
            return stack.getAmount();
        }

        List<Integer> otherPages = indicesOutsidePage(holder, page, limit);
        remaining -= mergeIntoIndices(holder, stack, otherPages, remaining);
        if (remaining <= 0) {
            return stack.getAmount();
        }
        remaining -= fillEmptyIndices(holder, stack, otherPages, remaining);
        return stack.getAmount() - remaining;
    }

    private static int depositIntoIndex(VaultHolder holder, ItemStack stack, int vaultIndex, int maxAmount) {
        if (maxAmount <= 0) {
            return 0;
        }
        ItemStack existing = holder.get(vaultIndex);
        if (existing == null || existing.getType().isAir()) {
            int place = Math.min(maxAmount, stack.getAmount());
            ItemStack placed = stack.clone();
            placed.setAmount(place);
            holder.put(vaultIndex, placed);
            return place;
        }
        if (!canMerge(existing, stack)) {
            return 0;
        }
        int space = existing.getMaxStackSize() - existing.getAmount();
        if (space <= 0) {
            return 0;
        }
        int add = Math.min(maxAmount, Math.min(space, stack.getAmount()));
        ItemStack merged = existing.clone();
        merged.setAmount(existing.getAmount() + add);
        holder.put(vaultIndex, merged);
        return add;
    }

    private static int mergeIntoIndices(VaultHolder holder, ItemStack stack, List<Integer> indices, int maxAmount) {
        int deposited = 0;
        for (int index : indices) {
            if (maxAmount - deposited <= 0) {
                break;
            }
            ItemStack existing = holder.get(index);
            if (existing == null || existing.getType().isAir() || !canMerge(existing, stack)) {
                continue;
            }
            deposited += depositIntoIndex(holder, stack, index, maxAmount - deposited);
        }
        return deposited;
    }

    private static int fillEmptyIndices(VaultHolder holder, ItemStack stack, List<Integer> indices, int maxAmount) {
        int deposited = 0;
        for (int index : indices) {
            if (maxAmount - deposited <= 0) {
                break;
            }
            if (holder.get(index) != null) {
                continue;
            }
            deposited += depositIntoIndex(holder, stack, index, maxAmount - deposited);
        }
        return deposited;
    }

    private static List<Integer> indicesForPage(VaultHolder holder, int page, int limit) {
        List<Integer> indices = new ArrayList<>();
        for (int contentIndex = 0; contentIndex < VaultLayout.CONTENT_SLOTS.length; contentIndex++) {
            int vaultIndex = VaultLayout.vaultIndexForContentSlot(page, contentIndex);
            if (vaultIndex >= limit) {
                continue;
            }
            if (holder.isDepositableVaultIndex(vaultIndex)) {
                indices.add(vaultIndex);
            }
        }
        return indices;
    }

    private static List<Integer> indicesOutsidePage(VaultHolder holder, int page, int limit) {
        List<Integer> indices = new ArrayList<>();
        for (int vaultIndex = 0; vaultIndex < limit; vaultIndex++) {
            if (!holder.isDepositableVaultIndex(vaultIndex)) {
                continue;
            }
            if (holder.pageForVaultIndex(vaultIndex) == page) {
                continue;
            }
            indices.add(vaultIndex);
        }
        return indices;
    }

    private static boolean canMerge(ItemStack existing, ItemStack incoming) {
        return existing != null
                && incoming != null
                && existing.isSimilar(incoming)
                && existing.getAmount() < existing.getMaxStackSize();
    }
}
