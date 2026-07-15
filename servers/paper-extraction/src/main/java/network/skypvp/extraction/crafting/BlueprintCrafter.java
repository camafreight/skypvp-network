package network.skypvp.extraction.crafting;

import java.util.Locale;
import java.util.Optional;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.item.RechargerTier;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.ItemStack;

/** Resolves blueprint outputs into custom item stacks. */
public final class BlueprintCrafter {

    private BlueprintCrafter() {
    }

    public static Optional<ItemStack> craft(CustomItemService service, BlueprintDefinition blueprint) {
        return craft(service, blueprint, null);
    }

    public static Optional<ItemStack> craft(
            CustomItemService service,
            BlueprintDefinition blueprint,
            WeaponMechanicsBridge weaponBridge
    ) {
        if (service == null || blueprint == null || blueprint.output() == null) {
            return Optional.empty();
        }
        String key = blueprint.output().recipeKey();
        return switch (blueprint.output().type()) {
            case MEDIC -> MedicConsumableType.byId(key).map(type -> ExtractionCustomItemProvider.createMedicConsumable(service, type));
            case MODULE -> ArmorModuleType.byId(key.startsWith("module_") ? key.substring("module_".length()) : key)
                    .map(type -> ExtractionCustomItemProvider.createArmorModule(service, type));
            case RECHARGER -> Optional.of(ExtractionCustomItemProvider.createShieldRecharger(service, RechargerTier.fromId(key)));
            case SHIELD_REPAIR_KIT -> Optional.of(ExtractionCustomItemProvider.createShieldRepairKit(service));
            case WEAPON -> weaponBridge == null
                    ? Optional.empty()
                    : weaponBridge.generateWeapon(key, 1);
            case SHIELD -> {
                GearRarity rarity = GearRarity.valueOf(key.toUpperCase(Locale.ROOT));
                yield Optional.of(ExtractionCustomItemProvider.createShieldModule(service, rarity));
            }
            case ARMOR -> craftArmor(service, key);
        };
    }

    private static Optional<ItemStack> craftArmor(CustomItemService service, String key) {
        // armor_vanguard_helmet_common
        String[] parts = key.split("_");
        if (parts.length < 4) {
            return Optional.empty();
        }
        ArmorSet set = ArmorSet.byId(parts[1]).orElse(ArmorSet.VANGUARD);
        InfuseArmorPiece piece = InfuseArmorPiece.byId(parts[2]).orElse(InfuseArmorPiece.CHESTPLATE);
        GearRarity rarity = GearRarity.valueOf(parts[parts.length - 1].toUpperCase(Locale.ROOT));
        return Optional.of(ExtractionCustomItemProvider.createInfuseArmor(service, piece, rarity, set));
    }
}
