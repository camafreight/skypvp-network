package network.skypvp.extraction.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import network.skypvp.paper.gui.GuiDepositRequirements;
import network.skypvp.paper.gui.GuiDepositSlot;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Atomic deposit / auto-place / consume helpers for physical crafting material slots. */
public final class CraftingMaterialDepositHelper {

    public record SlotBinding(int slot, String materialId, int required) {

        public GuiDepositSlot asDepositSlot() {
            return new GuiDepositSlot(slot, CraftingMaterialItemFactory.requirement(materialId).amount(required));
        }
    }

    public record PlacementResult(boolean success, String message, Map<Integer, ItemStack> rollback) {
        public static PlacementResult ok() {
            return new PlacementResult(true, null, Map.of());
        }

        public static PlacementResult fail(String message, Map<Integer, ItemStack> rollback) {
            return new PlacementResult(false, message, rollback);
        }
    }

    private CraftingMaterialDepositHelper() {
    }

    public static List<SlotBinding> bindingsFor(BlueprintDefinition blueprint) {
        List<Integer> slotIndices = List.of(20, 21, 22, 23, 24);
        List<SlotBinding> bindings = new ArrayList<>();
        List<BlueprintDefinition.MaterialCost> costs = blueprint.materials();
        for (int i = 0; i < Math.min(slotIndices.size(), costs.size()); i++) {
            BlueprintDefinition.MaterialCost cost = costs.get(i);
            bindings.add(new SlotBinding(slotIndices.get(i), cost.materialId(), cost.amount()));
        }
        return List.copyOf(bindings);
    }

    public static List<GuiDepositSlot> depositSlots(List<SlotBinding> bindings) {
        return bindings.stream().map(SlotBinding::asDepositSlot).toList();
    }

    public static Map<Integer, ItemStack> snapshot(Inventory inventory, List<SlotBinding> bindings) {
        return GuiDepositRequirements.snapshot(inventory, depositSlots(bindings));
    }

    public static void restore(Inventory inventory, Map<Integer, ItemStack> snapshot) {
        GuiDepositRequirements.restore(inventory, snapshot);
    }

    public static int depositedAmount(CustomItemService service, ItemStack stack, String materialId) {
        return GuiDepositRequirements.depositedAmount(service, stack, CraftingMaterialItemFactory.requirement(materialId));
    }

    public static boolean requirementsMet(CustomItemService service, Inventory inventory, List<SlotBinding> bindings) {
        return GuiDepositRequirements.met(service, inventory, depositSlots(bindings));
    }

    public static Map<String, Integer> deficits(CustomItemService service, Inventory inventory, List<SlotBinding> bindings) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (SlotBinding binding : bindings) {
            ItemStack deposited = inventory.getItem(binding.slot());
            int have = depositedAmount(service, deposited, binding.materialId());
            int need = binding.required() - have;
            if (need > 0) {
                missing.merge(binding.materialId(), need, Integer::sum);
            }
        }
        return missing;
    }

    /**
     * Pulls materials from player inventory then material stash, placing stacks into bound slots.
     * Rolls back inventory slots and stash debits on failure.
     */
    public static PlacementResult autoPlace(
            CustomItemService service,
            CraftingConfigService config,
            CraftingMaterialService stash,
            Player player,
            Inventory inventory,
            List<SlotBinding> bindings
    ) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(stash, "stash");
        Map<Integer, ItemStack> before = snapshot(inventory, bindings);
        Map<String, Integer> deficits = deficits(service, inventory, bindings);
        if (deficits.isEmpty()) {
            return PlacementResult.ok();
        }

        Map<String, Integer> stashDebits = new HashMap<>();
        Map<String, Integer> invTaken = new HashMap<>();

        try {
            for (Map.Entry<String, Integer> entry : deficits.entrySet()) {
                String materialId = entry.getKey();
                int stillNeed = entry.getValue();

                int fromInv = CraftingMaterialItemFactory.takeFromInventory(service, player, materialId, stillNeed);
                stillNeed -= fromInv;
                if (fromInv > 0) {
                    invTaken.merge(materialId, fromInv, Integer::sum);
                }

                if (stillNeed > 0) {
                    int stashHave = stash.balance(player.getUniqueId(), materialId);
                    if (stashHave < stillNeed) {
                        throw new PlacementAbort("Not enough " + materialLabel(config, materialId) + ".");
                    }
                    if (!stash.trySpend(player.getUniqueId(), Map.of(materialId, stillNeed))) {
                        throw new PlacementAbort("Stash withdrawal failed for " + materialLabel(config, materialId) + ".");
                    }
                    stashDebits.merge(materialId, stillNeed, Integer::sum);
                }
            }

            for (SlotBinding binding : bindings) {
                ItemStack current = inventory.getItem(binding.slot());
                int have = depositedAmount(service, current, binding.materialId());
                int need = binding.required() - have;
                if (need <= 0) {
                    continue;
                }
                int slotCapacity = Math.max(0, 64 - have);
                int placeAmount = Math.min(need, slotCapacity);
                if (placeAmount <= 0) {
                    throw new PlacementAbort("Deposit slot is full.");
                }
                List<ItemStack> created = CraftingMaterialItemFactory.splitStacks(service, config, binding.materialId(), placeAmount);
                if (created.isEmpty()) {
                    throw new PlacementAbort("Could not create material items.");
                }
                ItemStack placed = mergeStacks(created, placeAmount);
                if (current != null && depositedAmount(service, current, binding.materialId()) > 0) {
                    placed.setAmount(Math.min(64, current.getAmount() + placed.getAmount()));
                }
                inventory.setItem(binding.slot(), placed);
            }

            if (!requirementsMet(service, inventory, bindings)) {
                throw new PlacementAbort("Could not fill all deposit slots.");
            }
            return PlacementResult.ok();
        } catch (PlacementAbort abort) {
            rollbackPlacement(stash, service, config, player, inventory, before, stashDebits, invTaken);
            return PlacementResult.fail(abort.getMessage(), before);
        } catch (RuntimeException unexpected) {
            rollbackPlacement(stash, service, config, player, inventory, before, stashDebits, invTaken);
            throw unexpected;
        }
    }

    private static void rollbackPlacement(
            CraftingMaterialService stash,
            CustomItemService service,
            CraftingConfigService config,
            Player player,
            Inventory inventory,
            Map<Integer, ItemStack> before,
            Map<String, Integer> stashDebits,
            Map<String, Integer> invTaken
    ) {
        rollbackStash(stash, player.getUniqueId(), stashDebits);
        restoreInventoryMaterials(service, config, player, invTaken);
        restore(inventory, before);
    }

    /** Consumes exact required amounts from deposit slots (caller must verify requirements first). */
    public static void consume(CustomItemService service, Inventory inventory, List<SlotBinding> bindings) {
        GuiDepositRequirements.consume(service, inventory, depositSlots(bindings));
    }

    public static void returnDeposits(
            CustomItemService service,
            Player player,
            Inventory inventory,
            List<SlotBinding> bindings
    ) {
        GuiDepositRequirements.returnDeposits(service, player, inventory, depositSlots(bindings));
    }

    private static void rollbackStash(CraftingMaterialService stash, java.util.UUID playerId, Map<String, Integer> debits) {
        for (Map.Entry<String, Integer> entry : debits.entrySet()) {
            stash.grant(playerId, entry.getKey(), entry.getValue());
        }
    }

    private static void restoreInventoryMaterials(
            CustomItemService service,
            CraftingConfigService config,
            Player player,
            Map<String, Integer> taken
    ) {
        for (Map.Entry<String, Integer> entry : taken.entrySet()) {
            for (ItemStack stack : CraftingMaterialItemFactory.splitStacks(service, config, entry.getKey(), entry.getValue())) {
                player.getInventory().addItem(stack).values()
                        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        }
    }

    private static ItemStack mergeStacks(List<ItemStack> stacks, int maxAmount) {
        ItemStack base = stacks.get(0).clone();
        int target = Math.min(64, Math.max(1, maxAmount));
        for (int i = 1; i < stacks.size() && base.getAmount() < target; i++) {
            int room = target - base.getAmount();
            base.setAmount(base.getAmount() + Math.min(room, stacks.get(i).getAmount()));
        }
        return base;
    }

    private static String materialLabel(CraftingConfigService config, String materialId) {
        return CraftingMaterialDefinition.byId(materialId, config.materials())
                .map(CraftingMaterialDefinition::displayName)
                .orElse(materialId);
    }

    private static final class PlacementAbort extends RuntimeException {
        PlacementAbort(String message) {
            super(message);
        }
    }
}
