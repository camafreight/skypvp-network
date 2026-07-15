package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import network.skypvp.extraction.crafting.BlueprintDefinition;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialDefinition;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.InfuseArmorPayload;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.item.ModuleSlotRules;
import network.skypvp.extraction.item.RechargerTier;
import network.skypvp.extraction.item.ShieldRechargerPayload;
import network.skypvp.extraction.item.ShieldModulePayload;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.ItemStack;

/** Salvage payouts — returns crafting materials from blueprint recipes; blocks attached gear. */
public final class ArmorySalvageService {

    private static final double SALVAGE_RETURN_RATIO = 0.55D;

    public record MaterialReturn(String materialId, int amount, String displayName) {
    }

    public record SalvageQuote(String summary, List<MaterialReturn> materials, String blockReason) {
        public boolean blocked() {
            return blockReason != null && !blockReason.isBlank();
        }
    }

    private ArmorySalvageService() {
    }

    public static Optional<SalvageQuote> quote(CustomItemService service, CraftingConfigService config, ItemStack stack) {
        if (service == null || config == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return Optional.empty();
        }
        Optional<CustomItemInstance> instance = service.resolve(stack);
        if (instance.isEmpty()) {
            return Optional.empty();
        }
        CustomItemInstance resolved = instance.get();
        Optional<InfuseArmorPiece> armorPiece = InfuseArmorPiece.byTypeId(resolved.typeId());
        if (armorPiece.isPresent()) {
            InfuseArmorPayload payload = InfuseArmorPayload.decode(resolved.payloadCopy());
            if (ModuleSlotRules.hasAttachments(payload, armorPiece.get())) {
                return Optional.of(new SalvageQuote(
                        payload.armorSet().displayName() + " " + payload.rarity().displayName() + " " + armorPiece.get().label(),
                        List.of(),
                        "Remove modules, overclocks, and shields before salvaging."
                ));
            }
            String recipeKey = "armor_" + payload.armorSet().id() + "_" + armorPiece.get().name().toLowerCase(Locale.ROOT)
                    + "_" + payload.rarity().name().toLowerCase(Locale.ROOT);
            String label = payload.armorSet().displayName() + " " + payload.rarity().displayName() + " " + armorPiece.get().label();
            return blueprintQuote(config, "armor_" + payload.armorSet().id() + "_" + armorPiece.get().name().toLowerCase(Locale.ROOT) + "_common", label)
                    .or(() -> blueprintQuote(config, recipeKey, label))
                    .or(() -> genericArmorQuote(config, label, payload.rarity().ordinal()));
        }
        Optional<MedicConsumableType> medic = MedicConsumableType.byTypeId(resolved.typeId());
        if (medic.isPresent()) {
            return blueprintQuote(config, "medic_" + medic.get().id(), medic.get().displayName());
        }
        if (InfuseArmorMutator.isShieldModule(service, stack)) {
            ShieldModulePayload payload = ShieldModulePayload.decode(resolved.payloadCopy());
            return blueprintQuote(config, "shield_" + payload.shieldRarity().name().toLowerCase(Locale.ROOT),
                    payload.shieldRarity().displayName() + " shield module");
        }
        Optional<ArmorModuleType> module = InfuseArmorMutator.moduleTypeOf(service, stack);
        if (module.isPresent()) {
            return blueprintQuote(config, "module_" + module.get().id(), module.get().displayName());
        }
        if (InfuseArmorMutator.isShieldRecharger(service, stack)) {
            RechargerTier tier = resolved.payloadCopy() == null
                    ? RechargerTier.FIELD
                    : ShieldRechargerPayload.decode(resolved.payloadCopy()).tier();
            return blueprintQuote(config, "recharger_" + tier.name().toLowerCase(Locale.ROOT), tier.displayName());
        }
        if (InfuseArmorMutator.isShieldRepairKit(service, stack)) {
            return blueprintQuote(config, "shield_repair_kit", "Field Shield Repair Kit");
        }
        return Optional.empty();
    }

    private static Optional<SalvageQuote> blueprintQuote(CraftingConfigService config, String blueprintId, String summary) {
        Optional<BlueprintDefinition> blueprint = config.blueprints().stream().filter(bp -> bp.id().equals(blueprintId)).findFirst();
        if (blueprint.isEmpty()) {
            return Optional.empty();
        }
        List<MaterialReturn> returns = new ArrayList<>();
        for (BlueprintDefinition.MaterialCost cost : blueprint.get().materials()) {
            int amount = Math.max(1, (int) Math.floor(cost.amount() * SALVAGE_RETURN_RATIO));
            String name = config.materials().stream()
                    .filter(mat -> mat.id().equals(cost.materialId()))
                    .map(CraftingMaterialDefinition::displayName)
                    .findFirst()
                    .orElse(cost.materialId());
            returns.add(new MaterialReturn(cost.materialId(), amount, name));
        }
        return Optional.of(new SalvageQuote(summary, List.copyOf(returns), null));
    }

    private static Optional<SalvageQuote> genericArmorQuote(CraftingConfigService config, String label, int rarityOrdinal) {
        List<MaterialReturn> returns = List.of(
                new MaterialReturn("metal_shards", 2 + rarityOrdinal, "Metal Shards"),
                new MaterialReturn("cloth_scrap", 1 + rarityOrdinal, "Cloth Scrap")
        );
        return Optional.of(new SalvageQuote(label, returns, null));
    }
}
