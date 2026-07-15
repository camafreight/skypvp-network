package network.skypvp.extraction.gameplay;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.crafting.BlueprintDefinition;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialItemDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialItemPayload;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.item.RechargerTier;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.ItemStack;

/** Creates extraction custom items for breach loot tables and mob drops. */
public final class ExtractionLootFactory {

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;

    public ExtractionLootFactory(PaperCorePlugin core, CraftingConfigService craftingConfig) {
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
    }

    public Optional<ItemStack> blueprintReceipt(String blueprintId, int amount) {
        if (blueprintId == null || blueprintId.isBlank()) {
            return Optional.empty();
        }
        Optional<BlueprintDefinition> blueprint = craftingConfig.blueprints().stream()
                .filter(entry -> entry.id().equals(blueprintId))
                .findFirst();
        if (blueprint.isEmpty()) {
            return Optional.empty();
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            return Optional.empty();
        }
        ItemStack stack = ExtractionCustomItemProvider.createBlueprintReceipt(service, blueprintId);
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        stack.setAmount(Math.max(1, amount));
        return Optional.of(stack);
    }

    /**
     * Resolves a human-readable custom item spec from breach loot config.
     * Examples: {@code material:cloth_scrap}, {@code medic:bandage_rag}, {@code module:sprinter},
     * {@code infuse:helmet:uncommon:vanguard}, {@code medic_bandage_rag}.
     */
    public Optional<ItemStack> customItem(String itemSpec, int amount) {
        if (itemSpec == null || itemSpec.isBlank()) {
            return Optional.empty();
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            return Optional.empty();
        }
        String normalized = itemSpec.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int stackAmount = Math.max(1, amount);

        if (lower.startsWith("material:")) {
            return materialStack(service, normalized.substring("material:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("medic:")) {
            return medicStack(service, normalized.substring("medic:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("module:")) {
            return moduleStack(service, normalized.substring("module:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("infuse:")) {
            return infuseStack(service, normalized.substring("infuse:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("recharger:")) {
            return rechargerStack(service, normalized.substring("recharger:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("blueprint:")) {
            return blueprintReceipt(normalized.substring("blueprint:".length()).trim(), stackAmount);
        }
        if (lower.startsWith("medic_")) {
            return medicStack(service, normalized.substring("medic_".length()), stackAmount);
        }
        if (lower.startsWith("module_")) {
            return moduleStack(service, normalized.substring("module_".length()), stackAmount);
        }
        if (lower.startsWith("infuse_")) {
            return infuseStack(service, normalized.substring("infuse_".length()), stackAmount);
        }
        if ("shield_repair_kit".equals(lower)) {
            return stackOf(ExtractionCustomItemProvider.createShieldRepairKit(service), stackAmount);
        }
        if ("flight_recorder".equals(lower) || "quest:flight_recorder".equals(lower)) {
            return stackOf(ExtractionCustomItemProvider.createFlightRecorder(service), stackAmount);
        }
        return materialStack(service, normalized, stackAmount);
    }

    private Optional<ItemStack> materialStack(CustomItemService service, String materialId, int amount) {
        if (materialId.isBlank()) {
            return Optional.empty();
        }
        boolean known = craftingConfig.materials().stream()
                .anyMatch(material -> material.id().equalsIgnoreCase(materialId));
        if (!known) {
            return Optional.empty();
        }
        // Route through the shared factory: it applies the material icon, display
        // presentation, and the per-material mat_<id> pack sprite consistently.
        ItemStack stack = network.skypvp.extraction.crafting.CraftingMaterialItemFactory.create(
                service, craftingConfig, materialId, Math.max(1, amount));
        return stackOf(stack, amount);
    }

    private Optional<ItemStack> medicStack(CustomItemService service, String medicId, int amount) {
        return MedicConsumableType.byId(medicId)
                .map(type -> stackOf(ExtractionCustomItemProvider.createMedicConsumable(service, type), amount))
                .orElse(Optional.empty());
    }

    private Optional<ItemStack> moduleStack(CustomItemService service, String moduleId, int amount) {
        return ArmorModuleType.byId(moduleId)
                .map(type -> stackOf(ExtractionCustomItemProvider.createArmorModule(service, type), amount))
                .orElse(Optional.empty());
    }

    private Optional<ItemStack> infuseStack(CustomItemService service, String spec, int amount) {
        String[] parts = spec.split(":");
        if (parts.length == 0 || parts[0].isBlank()) {
            return Optional.empty();
        }
        Optional<InfuseArmorPiece> piece = InfuseArmorPiece.byId(parts[0]);
        if (piece.isEmpty()) {
            return Optional.empty();
        }
        GearRarity rarity = parts.length >= 2
                ? parseRarity(parts[1]).orElse(GearRarity.UNCOMMON)
                : GearRarity.UNCOMMON;
        ArmorSet set = parts.length >= 3
                ? ArmorSet.parse(parts[2], ArmorSet.VANGUARD)
                : ArmorSet.VANGUARD;
        ItemStack stack = ExtractionCustomItemProvider.createInfuseArmor(service, piece.get(), rarity, set);
        return stackOf(stack, amount);
    }

    private Optional<ItemStack> rechargerStack(CustomItemService service, String tierId, int amount) {
        RechargerTier tier = RechargerTier.fromId(tierId);
        return stackOf(ExtractionCustomItemProvider.createShieldRecharger(service, tier), amount);
    }

    private static Optional<GearRarity> parseRarity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GearRarity.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ItemStack> stackOf(ItemStack stack, int amount) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        stack.setAmount(Math.max(1, amount));
        return Optional.of(stack);
    }
}
