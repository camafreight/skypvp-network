package network.skypvp.extraction.crafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.item.RechargerTier;
import org.bukkit.Material;

/** Generates the full material-based blueprint catalog; JSON entries override by id at load time. */
public final class BlueprintCatalogFactory {

    private BlueprintCatalogFactory() {
    }

    public static List<BlueprintDefinition> generatedCatalog() {
        List<BlueprintDefinition> catalog = new ArrayList<>();
        for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
            for (ArmorSet set : ArmorSet.values()) {
                for (GearRarity rarity : GearRarity.values()) {
                    catalog.add(armorBlueprint(piece, set, rarity));
                }
            }
        }
        for (GearRarity rarity : GearRarity.values()) {
            catalog.add(shieldBlueprint(rarity));
        }
        for (ArmorModuleType type : ArmorModuleType.values()) {
            catalog.add(moduleBlueprint(type));
        }
        for (MedicConsumableType type : MedicConsumableType.values()) {
            catalog.add(medicBlueprint(type));
        }
        for (RechargerTier tier : RechargerTier.values()) {
            catalog.add(rechargerBlueprint(tier));
        }
        catalog.add(shieldRepairKitBlueprint());
        for (WeaponRecipe weapon : WeaponRecipe.values()) {
            catalog.add(weaponBlueprint(weapon));
        }
        return List.copyOf(catalog);
    }

    private enum WeaponRecipe {
        COMBAT_KNIFE("Combat_Knife", "Combat Knife", Material.IRON_SWORD, 1, false),
        MAGNUM("357_Magnum", ".357 Magnum", Material.GOLDEN_HOE, 1, false),
        UZI("Uzi", "Uzi", Material.CROSSBOW, 2, true),
        STIM("Stim", "Stim", Material.GLASS_BOTTLE, 2, true),
        GRENADE("Grenade", "Grenade", Material.TNT, 2, false),
        AK_47("AK_47", "AK-47", Material.IRON_HOE, 3, false),
        M4A1("M4A1", "M4A1", Material.IRON_HOE, 3, false),
        ORIGIN_12("Origin_12", "Origin 12", Material.IRON_AXE, 3, false),
        KAR98K("Kar98k", "Kar98k", Material.BOW, 3, false),
        GS_50("50_GS", ".50 GS", Material.GOLDEN_HOE, 3, false),
        SEMTEX("Semtex", "Semtex", Material.TNT, 3, false),
        AX_50("AX_50", "AX-50", Material.BOW, 4, false),
        RPG_7("RPG_7", "RPG-7", Material.FIREWORK_ROCKET, 4, false),
        MG34("MG34", "MG34", Material.IRON_AXE, 4, false),
        FN_FAL("FN_FAL", "FN FAL", Material.IRON_HOE, 4, false);

        private final String title;
        private final String display;
        private final Material icon;
        private final int tier;
        private final boolean starter;

        WeaponRecipe(String title, String display, Material icon, int tier, boolean starter) {
            this.title = title;
            this.display = display;
            this.icon = icon;
            this.tier = tier;
            this.starter = starter;
        }
    }

    private static BlueprintDefinition weaponBlueprint(WeaponRecipe weapon) {
        String id = "weapon_" + weapon.title.toLowerCase(Locale.ROOT);
        return new BlueprintDefinition(
                id,
                BlueprintCategory.WEAPONS,
                weapon.display,
                weapon.icon,
                weapon.starter,
                weaponMaterials(weapon.tier),
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.WEAPON, weapon.title)
        );
    }

    private static List<BlueprintDefinition.MaterialCost> weaponMaterials(int tier) {
        List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
        switch (tier) {
            case 1 -> {
                addScaled(costs, "metal_shards", 8, 1.0D);
                addScaled(costs, "cloth_scrap", 3, 1.0D);
            }
            case 2 -> {
                addScaled(costs, "metal_shards", 10, 1.0D);
                addScaled(costs, "polymer_sheet", 4, 1.0D);
                addScaled(costs, "capacitor_cell", 1, 1.0D);
            }
            case 3 -> {
                addScaled(costs, "alloy_plate", 4, 1.0D);
                addScaled(costs, "polymer_sheet", 4, 1.0D);
                addScaled(costs, "capacitor_cell", 2, 1.0D);
            }
            default -> {
                addScaled(costs, "alloy_plate", 6, 1.0D);
                addScaled(costs, "aether_resin", 2, 1.0D);
                addScaled(costs, "quantum_gel", 1, 1.0D);
            }
        }
        return List.copyOf(costs);
    }

    private static BlueprintDefinition armorBlueprint(InfuseArmorPiece piece, ArmorSet set, GearRarity rarity) {
        String id = "armor_" + set.id() + "_" + piece.name().toLowerCase(Locale.ROOT) + "_" + rarity.name().toLowerCase(Locale.ROOT);
        boolean starter = set == ArmorSet.VANGUARD && rarity == GearRarity.COMMON;
        List<BlueprintDefinition.MaterialCost> costs = armorMaterials(piece, set, rarity);
        return new BlueprintDefinition(
                id,
                BlueprintCategory.ARMOR,
                set.displayName() + " " + piece.label() + " (" + rarity.displayName() + ")",
                piece.material(),
                starter,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.ARMOR, id)
        );
    }

    private static List<BlueprintDefinition.MaterialCost> armorMaterials(InfuseArmorPiece piece, ArmorSet set, GearRarity rarity) {
        double scale = piece.craftCostMultiplier() * (1.0D + set.ordinal() * 0.12D);
        List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
        switch (rarity) {
            case COMMON -> {
                addScaled(costs, "metal_shards", 6, scale);
                addScaled(costs, "cloth_scrap", 4, scale);
            }
            case UNCOMMON -> {
                addScaled(costs, "metal_shards", 8, scale);
                addScaled(costs, "polymer_sheet", 3, scale);
                addScaled(costs, "fiber_bundle", 2, scale);
            }
            case RARE -> {
                addScaled(costs, "metal_shards", 10, scale);
                addScaled(costs, "polymer_sheet", 5, scale);
                addScaled(costs, "alloy_plate", 2, scale);
            }
            case EPIC -> {
                addScaled(costs, "alloy_plate", 6, scale);
                addScaled(costs, "polymer_sheet", 4, scale);
                addScaled(costs, "aether_resin", 2, scale);
            }
            case LEGENDARY -> {
                addScaled(costs, "alloy_plate", 8, scale);
                addScaled(costs, "quantum_gel", 2, scale);
                addScaled(costs, "aether_resin", 3, scale);
            }
        }
        return List.copyOf(costs);
    }

    private static BlueprintDefinition shieldBlueprint(GearRarity rarity) {
        String id = "shield_" + rarity.name().toLowerCase(Locale.ROOT);
        List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
        int tier = rarity.ordinal() + 1;
        addScaled(costs, "capacitor_cell", 2 + tier, 1.0D);
        addScaled(costs, "metal_shards", 3 + tier * 2, 1.0D);
        if (rarity.ordinal() >= GearRarity.UNCOMMON.ordinal()) {
            addScaled(costs, "polymer_sheet", tier, 1.0D);
        }
        if (rarity.ordinal() >= GearRarity.RARE.ordinal()) {
            addScaled(costs, "alloy_plate", tier, 1.0D);
        }
        if (rarity.ordinal() >= GearRarity.EPIC.ordinal()) {
            addScaled(costs, "aether_resin", 1 + tier / 2, 1.0D);
        }
        return new BlueprintDefinition(
                id,
                BlueprintCategory.MODULE,
                rarity.displayName() + " Shield Module",
                Material.HEAVY_CORE,
                false,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.SHIELD, rarity.name().toLowerCase(Locale.ROOT))
        );
    }

    private static BlueprintDefinition moduleBlueprint(ArmorModuleType type) {
        String id = "module_" + type.id();
        List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
        if (type.overclock()) {
            addScaled(costs, "alloy_plate", 4, 1.0D);
            addScaled(costs, "capacitor_cell", 4, 1.0D);
            addScaled(costs, "aether_resin", 2, 1.0D);
        } else if (type.compatiblePieces().contains(InfuseArmorPiece.BOOTS)
                || type.compatiblePieces().contains(InfuseArmorPiece.LEGGINGS)
                || type.compatiblePieces().contains(InfuseArmorPiece.HELMET)) {
            addScaled(costs, "polymer_sheet", 3, 1.0D);
            addScaled(costs, "fiber_bundle", 4, 1.0D);
        } else {
            addScaled(costs, "polymer_sheet", 4, 1.0D);
            addScaled(costs, "capacitor_cell", 2, 1.0D);
        }
        return new BlueprintDefinition(
                id,
                BlueprintCategory.MODULE,
                type.displayName(),
                type.material(),
                false,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.MODULE, "module_" + type.id())
        );
    }

    private static BlueprintDefinition shieldRepairKitBlueprint() {
        List<BlueprintDefinition.MaterialCost> costs = List.of(
                new BlueprintDefinition.MaterialCost("alloy_plate", 4),
                new BlueprintDefinition.MaterialCost("capacitor_cell", 3),
                new BlueprintDefinition.MaterialCost("aether_resin", 2)
        );
        return new BlueprintDefinition(
                "shield_repair_kit",
                BlueprintCategory.MISC,
                "Field Shield Repair Kit",
                Material.NETHERITE_SCRAP,
                false,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.SHIELD_REPAIR_KIT, "field")
        );
    }

    private static BlueprintDefinition rechargerBlueprint(RechargerTier tier) {
        String tierId = tier.name().toLowerCase(Locale.ROOT);
        List<BlueprintDefinition.MaterialCost> costs = rechargerMaterials(tier);
        Material icon = switch (tier) {
            case FIELD -> Material.POTION;
            case TACTICAL -> Material.SPLASH_POTION;
            case MILITARY -> Material.LINGERING_POTION;
            case QUANTUM -> Material.DRAGON_BREATH;
        };
        return new BlueprintDefinition(
                "recharger_" + tierId,
                BlueprintCategory.MISC,
                tier.displayName(),
                icon,
                tier == RechargerTier.FIELD,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.RECHARGER, tierId)
        );
    }

    private static List<BlueprintDefinition.MaterialCost> rechargerMaterials(RechargerTier tier) {
        return switch (tier) {
            case FIELD -> List.of(
                    new BlueprintDefinition.MaterialCost("capacitor_cell", 2),
                    new BlueprintDefinition.MaterialCost("stim_compound", 1)
            );
            case TACTICAL -> List.of(
                    new BlueprintDefinition.MaterialCost("capacitor_cell", 4),
                    new BlueprintDefinition.MaterialCost("aether_resin", 2)
            );
            case MILITARY -> List.of(
                    new BlueprintDefinition.MaterialCost("capacitor_cell", 6),
                    new BlueprintDefinition.MaterialCost("alloy_plate", 3)
            );
            case QUANTUM -> List.of(
                    new BlueprintDefinition.MaterialCost("quantum_gel", 2),
                    new BlueprintDefinition.MaterialCost("capacitor_cell", 8)
            );
        };
    }

    private static BlueprintDefinition medicBlueprint(MedicConsumableType type) {
        String id = "medic_" + type.id();
        BlueprintCategory category = type.isSyringe() ? BlueprintCategory.STAMINA : BlueprintCategory.HEALING;
        boolean starter = type == MedicConsumableType.BANDAGE_RAG
                || type == MedicConsumableType.STERILE_BANDAGE
                || type == MedicConsumableType.ADRENALINE_SHOT;
        List<BlueprintDefinition.MaterialCost> costs = medicMaterials(type);
        return new BlueprintDefinition(
                id,
                category,
                type.displayName(),
                type.material(),
                starter,
                costs,
                new BlueprintDefinition.BlueprintOutput(BlueprintDefinition.OutputType.MEDIC, type.id())
        );
    }

    private static List<BlueprintDefinition.MaterialCost> medicMaterials(MedicConsumableType type) {
        List<BlueprintDefinition.MaterialCost> costs = new ArrayList<>();
        switch (type) {
            case BANDAGE_RAG -> {
                costs.add(new BlueprintDefinition.MaterialCost("cloth_scrap", 4));
                costs.add(new BlueprintDefinition.MaterialCost("field_suture", 1));
            }
            case STERILE_BANDAGE -> {
                costs.add(new BlueprintDefinition.MaterialCost("fiber_bundle", 3));
                costs.add(new BlueprintDefinition.MaterialCost("field_suture", 2));
            }
            case MEDKIT -> {
                costs.add(new BlueprintDefinition.MaterialCost("field_suture", 4));
                costs.add(new BlueprintDefinition.MaterialCost("fiber_bundle", 2));
                costs.add(new BlueprintDefinition.MaterialCost("stim_compound", 1));
            }
            case SURGICAL_KIT -> {
                costs.add(new BlueprintDefinition.MaterialCost("aether_resin", 2));
                costs.add(new BlueprintDefinition.MaterialCost("field_suture", 3));
                costs.add(new BlueprintDefinition.MaterialCost("alloy_plate", 1));
            }
            case ADRENALINE_SHOT -> {
                costs.add(new BlueprintDefinition.MaterialCost("stim_compound", 2));
                costs.add(new BlueprintDefinition.MaterialCost("aether_resin", 1));
            }
            case STAMINA_STABILIZER -> {
                costs.add(new BlueprintDefinition.MaterialCost("stim_compound", 3));
                costs.add(new BlueprintDefinition.MaterialCost("polymer_sheet", 2));
            }
            case OVERDRIVE_SERUM -> {
                costs.add(new BlueprintDefinition.MaterialCost("aether_resin", 3));
                costs.add(new BlueprintDefinition.MaterialCost("quantum_gel", 1));
            }
        }
        return List.copyOf(costs);
    }

    private static void addScaled(List<BlueprintDefinition.MaterialCost> costs, String materialId, int base, double scale) {
        int amount = Math.max(1, (int) Math.round(base * scale));
        for (BlueprintDefinition.MaterialCost existing : costs) {
            if (existing.materialId().equals(materialId)) {
                costs.remove(existing);
                amount += existing.amount();
                break;
            }
        }
        costs.add(new BlueprintDefinition.MaterialCost(materialId, amount));
    }
}
