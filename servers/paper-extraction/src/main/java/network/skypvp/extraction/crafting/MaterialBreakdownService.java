package network.skypvp.extraction.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService.Output;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService.Recipe;
import network.skypvp.paper.gui.GuiCustomItemRequirement;
import network.skypvp.paper.item.CustomItemStacks;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.ItemStack;

/** Quotes and resolves material breakdown (higher tier → lower tier components). */
public final class MaterialBreakdownService {

    public record MaterialYield(String materialId, String displayName, int amount) {
    }

    public record BreakdownQuote(
            String inputName,
            int inputAmount,
            List<MaterialYield> outputs,
            String blockReason
    ) {
        public boolean blocked() {
            return blockReason != null && !blockReason.isBlank();
        }
    }

    private MaterialBreakdownService() {
    }

    public static Optional<BreakdownQuote> quote(
            CustomItemService service,
            CraftingConfigService craftingConfig,
            MaterialBreakdownConfigService breakdownConfig,
            ItemStack stack
    ) {
        if (service == null || craftingConfig == null || breakdownConfig == null || stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        Optional<String> materialId = CraftingMaterialItemFactory.materialIdOf(service, stack);
        if (materialId.isEmpty()) {
            return Optional.empty();
        }
        Optional<Recipe> recipe = breakdownConfig.recipeFor(materialId.get());
        if (recipe.isEmpty()) {
            return Optional.empty();
        }
        Recipe r = recipe.get();
        String inputName = CraftingMaterialDefinition.byId(r.inputId(), craftingConfig.materials())
                .map(CraftingMaterialDefinition::displayName)
                .orElse(r.inputId());
        int stackAmount = stack.getAmount();
        if (stackAmount < r.inputAmount()) {
            return Optional.of(new BreakdownQuote(
                    inputName,
                    stackAmount,
                    List.of(),
                    "Need at least " + r.inputAmount() + "x " + inputName + " to break down."
            ));
        }
        int batches = stackAmount / r.inputAmount();
        double efficiency = breakdownConfig.defaultEfficiency();
        List<MaterialYield> yields = new ArrayList<>();
        for (Output output : r.outputs()) {
            int scaled = scale(output.amount() * batches, efficiency);
            if (scaled <= 0) {
                continue;
            }
            String display = CraftingMaterialDefinition.byId(output.materialId(), craftingConfig.materials())
                    .map(CraftingMaterialDefinition::displayName)
                    .orElse(output.materialId());
            yields.add(new MaterialYield(output.materialId(), display, scaled));
        }
        if (yields.isEmpty()) {
            return Optional.of(new BreakdownQuote(inputName, stackAmount, List.of(), "Breakdown would yield nothing."));
        }
        return Optional.of(new BreakdownQuote(inputName, batches * r.inputAmount(), List.copyOf(yields), null));
    }

    public static boolean accepts(CustomItemService service, MaterialBreakdownConfigService breakdownConfig, ItemStack stack) {
        if (service == null || breakdownConfig == null || stack == null) {
            return false;
        }
        return CustomItemStacks.matches(service, stack, refinableMaterialRequirement(breakdownConfig));
    }

    public static GuiCustomItemRequirement refinableMaterialRequirement(MaterialBreakdownConfigService breakdownConfig) {
        return GuiCustomItemRequirement.require(CraftingMaterialItemDefinition.TYPE_ID)
                .match(instance -> {
                    String id = CraftingMaterialItemPayload.decode(instance.payloadCopy()).materialId();
                    return breakdownConfig.recipeFor(id).isPresent();
                });
    }

    public static List<ItemStack> createOutputStacks(
            CustomItemService service,
            CraftingConfigService craftingConfig,
            List<MaterialYield> yields
    ) {
        List<ItemStack> stacks = new ArrayList<>();
        for (MaterialYield yield : yields) {
            stacks.addAll(CraftingMaterialItemFactory.splitStacks(service, craftingConfig, yield.materialId(), yield.amount()));
        }
        return stacks;
    }

    private static int scale(int base, double efficiency) {
        return Math.max(1, (int) Math.floor(base * efficiency));
    }
}
