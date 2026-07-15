package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Craft recipes available at the armory workbench, grouped by category. */
public final class ArmoryCraftCatalog {

    public enum Category {
        ARMOR("Armor Sets", Material.NETHERITE_CHESTPLATE),
        SHIELD("Shield Modules", Material.HEAVY_CORE),
        MODULE("Armor Modules", Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
        MEDICAL("Medical Supplies", Material.SPLASH_POTION);

        private final String title;
        private final Material icon;

        Category(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }

        public String title() {
            return title;
        }

        public Material icon() {
            return icon;
        }
    }

    public record Recipe(
            String id,
            Category category,
            String displayName,
            Material icon,
            long coinCost,
            Function<CustomItemService, ItemStack> crafter
    ) {
    }

    private static final long[] RARITY_COSTS = {100L, 250L, 500L, 1000L, 2000L};

    private ArmoryCraftCatalog() {
    }

    public static List<Recipe> all() {
        List<Recipe> recipes = new ArrayList<>();
        for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
            for (ArmorSet set : ArmorSet.values()) {
                for (GearRarity rarity : GearRarity.values()) {
                    recipes.add(armorRecipe(piece, set, rarity));
                }
            }
        }
        for (GearRarity rarity : GearRarity.values()) {
            recipes.add(shieldRecipe(rarity));
        }
        for (ArmorModuleType type : ArmorModuleType.values()) {
            recipes.add(moduleRecipe(type));
        }
        for (MedicConsumableType type : MedicConsumableType.values()) {
            recipes.add(medicRecipe(type));
        }
        return List.copyOf(recipes);
    }

    public static List<Recipe> byCategory(Category category) {
        return all().stream().filter(recipe -> recipe.category() == category).toList();
    }

    private static Recipe armorRecipe(InfuseArmorPiece piece, ArmorSet set, GearRarity rarity) {
        long cost = Math.max(25L, Math.round(RARITY_COSTS[rarity.ordinal()] * piece.craftCostMultiplier()));
        return new Recipe(
                "armor_" + set.id() + "_" + piece.name().toLowerCase() + "_" + rarity.name().toLowerCase(),
                Category.ARMOR,
                set.displayName() + " " + piece.label() + " (" + rarity.displayName() + ")",
                piece.material(),
                cost,
                service -> ExtractionCustomItemProvider.createInfuseArmor(service, piece, rarity, set)
        );
    }

    private static Recipe shieldRecipe(GearRarity rarity) {
        long cost = Math.max(60L, RARITY_COSTS[rarity.ordinal()] / 2L);
        return new Recipe(
                "shield_" + rarity.name().toLowerCase(),
                Category.SHIELD,
                rarity.displayName() + " Shield Module",
                Material.HEAVY_CORE,
                cost,
                service -> ExtractionCustomItemProvider.createShieldModule(service, rarity)
        );
    }

    private static Recipe moduleRecipe(ArmorModuleType type) {
        long cost = type.overclock() ? 400L : 150L;
        return new Recipe(
                "module_" + type.id(),
                Category.MODULE,
                type.displayName(),
                type.material(),
                cost,
                service -> ExtractionCustomItemProvider.createArmorModule(service, type)
        );
    }

    private static Recipe medicRecipe(MedicConsumableType type) {
        return new Recipe(
                "medic_" + type.id(),
                Category.MEDICAL,
                type.displayName(),
                type.material(),
                ItemConfigOverrides.medicCoinCost(type),
                service -> ExtractionCustomItemProvider.createMedicConsumable(service, type)
        );
    }
}
