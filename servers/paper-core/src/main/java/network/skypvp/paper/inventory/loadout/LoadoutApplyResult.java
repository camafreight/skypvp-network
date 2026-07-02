package network.skypvp.paper.inventory.loadout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import network.skypvp.paper.inventory.vault.VaultLayout;
import network.skypvp.paper.inventory.vault.VaultSlotAccess;
import network.skypvp.paper.library.ItemStackCodec;
import org.bukkit.inventory.ItemStack;

public final class LoadoutApplyResult {

    private final boolean success;
    private final List<String> detailLines;

    private LoadoutApplyResult(boolean success, List<String> detailLines) {
        this.success = success;
        this.detailLines = List.copyOf(detailLines);
    }

    public static LoadoutApplyResult success(int movedToVault) {
        List<String> lines = new ArrayList<>();
        lines.add("Loadout applied.");
        if (movedToVault > 0) {
            lines.add("Stored " + movedToVault + " extra item stack(s) in your vault.");
        }
        return new LoadoutApplyResult(true, lines);
    }

    public static LoadoutApplyResult missingItems(List<String> missingLines) {
        List<String> lines = new ArrayList<>();
        lines.add("Missing required items.");
        lines.addAll(missingLines);
        return new LoadoutApplyResult(false, lines);
    }

    public static LoadoutApplyResult failure(String message) {
        return new LoadoutApplyResult(false, List.of(message));
    }

    public boolean success() {
        return this.success;
    }

    public List<String> detailLines() {
        return this.detailLines;
    }

    public record Transaction(
            Map<Integer, ItemStack> appliedItems,
            Map<Integer, String> updatedVault,
            int extraStacksStored
    ) {
    }

    public static Transaction plan(
            Map<Integer, String> loadoutTemplate,
            Map<Integer, String> vaultSnapshot,
            List<InventoryStackRef> playerStacks
    ) {
        Map<Integer, ItemStack> template = decodeTemplate(loadoutTemplate);
        Map<Integer, String> vault = new HashMap<>(vaultSnapshot == null ? Map.of() : vaultSnapshot);
        List<InventoryStackRef> workingPlayerStacks = clonePlayerStacks(playerStacks);
        List<String> missing = new ArrayList<>();
        Map<Integer, ItemStack> applied = new HashMap<>();

        for (Map.Entry<Integer, ItemStack> requirement : template.entrySet()) {
            ItemStack needed = requirement.getValue();
            if (needed == null || needed.getType().isAir()) {
                continue;
            }
            ItemStack pulled = pullMatching(workingPlayerStacks, vault, needed);
            if (pulled == null || pulled.getAmount() < needed.getAmount()) {
                int have = pulled == null ? 0 : pulled.getAmount();
                missing.add(describeMissing(needed, have));
                continue;
            }
            applied.put(requirement.getKey(), pulled);
        }

        if (!missing.isEmpty()) {
            throw new LoadoutApplyException(LoadoutApplyResult.missingItems(missing));
        }

        int movedToVault = 0;
        for (InventoryStackRef ref : workingPlayerStacks) {
            ItemStack stack = ref.stack();
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (depositToVault(vault, stack)) {
                movedToVault++;
            }
        }

        return new Transaction(applied, vault, movedToVault);
    }

    private static Map<Integer, ItemStack> decodeTemplate(Map<Integer, String> loadoutTemplate) {
        Map<Integer, ItemStack> template = new HashMap<>();
        if (loadoutTemplate == null) {
            return template;
        }
        for (Map.Entry<Integer, String> entry : loadoutTemplate.entrySet()) {
            try {
                ItemStack item = ItemStackCodec.decode(entry.getValue());
                if (item != null && !item.getType().isAir()) {
                    template.put(entry.getKey(), item);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return template;
    }

    private static List<InventoryStackRef> clonePlayerStacks(List<InventoryStackRef> playerStacks) {
        List<InventoryStackRef> clones = new ArrayList<>();
        if (playerStacks == null) {
            return clones;
        }
        for (InventoryStackRef ref : playerStacks) {
            ItemStack stack = ref.stack();
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            clones.add(new InventoryStackRef(ref.playerSlot(), stack.clone()));
        }
        return clones;
    }

    private static ItemStack pullMatching(
            List<InventoryStackRef> playerStacks,
            Map<Integer, String> vault,
            ItemStack template
    ) {
        int needed = template.getAmount();
        ItemStack collected = null;

        for (InventoryStackRef ref : playerStacks) {
            ItemStack stack = ref.stack();
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(template)) {
                continue;
            }
            int take = Math.min(needed, stack.getAmount());
            collected = mergeStacks(collected, stack.asQuantity(take));
            stack.setAmount(stack.getAmount() - take);
            needed -= take;
            if (needed <= 0) {
                return collected;
            }
        }

        List<Integer> vaultIndexes = new ArrayList<>(vault.keySet());
        vaultIndexes.sort(Integer::compareTo);
        for (Integer vaultIndex : vaultIndexes) {
            ItemStack stack;
            try {
                stack = ItemStackCodec.decode(vault.get(vaultIndex));
            } catch (RuntimeException ignored) {
                continue;
            }
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(template)) {
                continue;
            }
            int take = Math.min(needed, stack.getAmount());
            ItemStack portion = stack.asQuantity(take);
            collected = mergeStacks(collected, portion);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) {
                vault.remove(vaultIndex);
            } else {
                vault.put(vaultIndex, ItemStackCodec.encode(stack));
            }
            needed -= take;
            if (needed <= 0) {
                return collected;
            }
        }

        return collected;
    }

    private static ItemStack mergeStacks(ItemStack left, ItemStack right) {
        if (right == null || right.getType().isAir()) {
            return left;
        }
        if (left == null || left.getType().isAir()) {
            return right.clone();
        }
        left.setAmount(left.getAmount() + right.getAmount());
        return left;
    }

    private static boolean depositToVault(Map<Integer, String> vault, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        int targetSlot = findFirstFreeVaultSlot(vault);
        if (targetSlot < 0) {
            throw new LoadoutApplyException(LoadoutApplyResult.failure("Your vault is full. Make room before loading this loadout."));
        }
        vault.put(targetSlot, ItemStackCodec.encode(stack));
        return true;
    }

    private static int findFirstFreeVaultSlot(Map<Integer, String> vault) {
        int highest = vault.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int limit = VaultSlotAccess.depositableLimit(highest);
        for (int slot = 0; slot < limit; slot++) {
            if (!vault.containsKey(slot)) {
                return slot;
            }
        }
        if (limit < VaultLayout.MAX_VAULT_SLOTS) {
            return limit;
        }
        return -1;
    }

    private static String describeMissing(ItemStack needed, int have) {
        String name = needed.hasItemMeta() && needed.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(needed.getItemMeta().displayName())
                : needed.getType().name().toLowerCase().replace('_', ' ');
        return name + " x" + needed.getAmount() + " (have " + have + ")";
    }

    public record InventoryStackRef(int playerSlot, ItemStack stack) {
    }

    public static final class LoadoutApplyException extends RuntimeException {
        private final LoadoutApplyResult result;

        public LoadoutApplyException(LoadoutApplyResult result) {
            super(result.detailLines().isEmpty() ? "Loadout apply failed" : result.detailLines().get(0));
            this.result = result;
        }

        public LoadoutApplyResult result() {
            return this.result;
        }
    }
}
