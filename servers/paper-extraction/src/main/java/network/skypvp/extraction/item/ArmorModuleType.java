package network.skypvp.extraction.item;

import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.item.api.CustomItemTypeId;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of installable Infuse armor modules. Modules are piece-specific ({@link #compatiblePieces()})
 * and may {@link #conflictsWith()} other modules on the same armor piece.
 */
public enum ArmorModuleType {

    BULWARK("bulwark", "Bulwark Module", NamedTextColor.GRAY, Material.NETHERITE_INGOT, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("sprinter", "lightweight", "tread"), List.of(
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, 0.08D, "8% Defense"),
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, -0.06D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "6% Movement Speed")
    )),
    SPRINTER("sprinter", "Sprinter Module", NamedTextColor.WHITE, Material.FEATHER, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("bulwark", "anchor", "fortress"), List.of(
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, 0.12D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "12% Movement Speed"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.05D, "5% Defense")
    )),
    BERSERKER("berserker", "Berserker Module", NamedTextColor.RED, Material.IRON_SWORD, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("field_medic", "cardio"), List.of(
            ModuleEffect.attribute(Attribute.ATTACK_DAMAGE, 2.0D, AttributeModifier.Operation.ADD_NUMBER, "2 Attack Damage"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.08D, "8% Defense")
    )),
    ANCHOR("anchor", "Anchor Module", NamedTextColor.DARK_GRAY, Material.ANVIL, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("sprinter", "tread", "overdrive"), List.of(
            ModuleEffect.attribute(Attribute.KNOCKBACK_RESISTANCE, 0.25D, AttributeModifier.Operation.ADD_NUMBER, "25% Knockback Resistance"),
            ModuleEffect.attribute(Attribute.ATTACK_SPEED, -0.10D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "10% Attack Speed")
    )),
    DUELIST("duelist", "Duelist Module", NamedTextColor.GOLD, Material.GOLDEN_SWORD, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("fortress", "visor_lock"), List.of(
            ModuleEffect.attribute(Attribute.ATTACK_SPEED, 0.15D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "15% Attack Speed"),
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, -0.05D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "5% Movement Speed")
    )),
    ENDURANCE("endurance", "Endurance Module", NamedTextColor.GREEN, Material.RABBIT_FOOT, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("marathon"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_MAX_MULT, 0.12D, "12% Stamina Pool"),
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, -0.04D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "4% Movement Speed")
    )),
    CARDIO("cardio", "Cardio Module", NamedTextColor.AQUA, Material.SUGAR, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("berserker"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_REGEN_MULT, 0.18D, "18% Stamina Regen"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.04D, "4% Defense")
    )),
    LIGHTWEIGHT("lightweight", "Lightweight Module", NamedTextColor.YELLOW, Material.PHANTOM_MEMBRANE, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("bulwark", "fortress"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_DRAIN_MULT, -0.15D, "15% Less Stamina Drain"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.06D, "6% Defense")
    )),
    FIELD_MEDIC("field_medic", "Field Medic Module", NamedTextColor.LIGHT_PURPLE, Material.POTION, false,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("berserker"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_REGEN_MULT, 0.10D, "10% Stamina Regen"),
            ModuleEffect.attribute(Attribute.MAX_HEALTH, 4.0D, AttributeModifier.Operation.ADD_NUMBER, "4 Max Health")
    )),

    TREAD("tread", "Tread Module", NamedTextColor.WHITE, Material.LEATHER_BOOTS, false,
            Set.of(InfuseArmorPiece.BOOTS), Set.of("anchor", "bulwark", "fortress"), List.of(
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, 0.10D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "10% Movement Speed"),
            ModuleEffect.named(ExtractionStatKeys.STAMINA_DRAIN_MULT, 0.08D, "8% More Stamina Drain")
    )),
    FLEX_WEAVE("flex_weave", "Flex Weave Module", NamedTextColor.AQUA, Material.PHANTOM_MEMBRANE, false,
            Set.of(InfuseArmorPiece.LEGGINGS), Set.of("bulwark"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_DRAIN_MULT, -0.12D, "12% Less Stamina Drain"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.05D, "5% Defense")
    )),
    VISOR_LOCK("visor_lock", "Visor Lock Module", NamedTextColor.GRAY, Material.IRON_HELMET, false,
            Set.of(InfuseArmorPiece.HELMET), Set.of("duelist"), List.of(
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, 0.06D, "6% Defense"),
            ModuleEffect.attribute(Attribute.ATTACK_SPEED, -0.08D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "8% Attack Speed")
    )),

    OVERDRIVE("overdrive", "Overdrive Overclock", NamedTextColor.LIGHT_PURPLE, Material.BLAZE_ROD, true,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("anchor"), List.of(
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, 0.15D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "15% Movement Speed"),
            ModuleEffect.attribute(Attribute.ATTACK_SPEED, 0.20D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "20% Attack Speed"),
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, -0.10D, "10% Defense")
    )),
    FORTRESS("fortress", "Fortress Overclock", NamedTextColor.AQUA, Material.NETHERITE_BLOCK, true,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("sprinter", "lightweight", "tread"), List.of(
            ModuleEffect.named(GearRarity.DEFENSE_STAT_KEY, 0.15D, "15% Defense"),
            ModuleEffect.attribute(Attribute.KNOCKBACK_RESISTANCE, 0.30D, AttributeModifier.Operation.ADD_NUMBER, "30% Knockback Resistance"),
            ModuleEffect.attribute(Attribute.MOVEMENT_SPEED, -0.10D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "10% Movement Speed"),
            ModuleEffect.attribute(Attribute.ATTACK_SPEED, -0.10D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, "10% Attack Speed")
    )),
    MARATHON("marathon", "Marathon Overclock", NamedTextColor.GREEN, Material.GOLDEN_CARROT, true,
            Set.of(InfuseArmorPiece.CHESTPLATE), Set.of("endurance"), List.of(
            ModuleEffect.named(ExtractionStatKeys.STAMINA_MAX_MULT, 0.20D, "20% Stamina Pool"),
            ModuleEffect.named(ExtractionStatKeys.STAMINA_REGEN_MULT, 0.12D, "12% Stamina Regen"),
            ModuleEffect.named(ExtractionStatKeys.STAMINA_DRAIN_MULT, 0.10D, "10% More Stamina Drain")
    ));

    private final String id;
    private final String displayName;
    private final NamedTextColor color;
    private final Material material;
    private final boolean overclock;
    private final Set<InfuseArmorPiece> compatiblePieces;
    private final Set<String> conflictsWith;
    private final List<ModuleEffect> effects;
    private final CustomItemTypeId typeId;

    ArmorModuleType(
            String id,
            String displayName,
            NamedTextColor color,
            Material material,
            boolean overclock,
            Set<InfuseArmorPiece> compatiblePieces,
            Set<String> conflictsWith,
            List<ModuleEffect> effects
    ) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.material = material;
        this.overclock = overclock;
        this.compatiblePieces = Set.copyOf(compatiblePieces);
        this.conflictsWith = Set.copyOf(conflictsWith);
        this.effects = List.copyOf(effects);
        this.typeId = new CustomItemTypeId("extraction", "module_" + id);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public NamedTextColor color() {
        return color;
    }

    public Material material() {
        return material;
    }

    public boolean overclock() {
        return overclock;
    }

    public Set<InfuseArmorPiece> compatiblePieces() {
        return compatiblePieces;
    }

    public Set<String> conflictsWith() {
        return conflictsWith;
    }

    public List<ModuleEffect> effects() {
        return effects;
    }

    public CustomItemTypeId typeId() {
        return typeId;
    }

    public static Optional<ArmorModuleType> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (ArmorModuleType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static Optional<ArmorModuleType> byTypeId(CustomItemTypeId typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        for (ArmorModuleType type : values()) {
            if (type.typeId.equals(typeId)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static boolean isModuleTypeId(CustomItemTypeId typeId) {
        return byTypeId(typeId).isPresent();
    }

    public record ModuleEffect(
            Attribute attribute,
            AttributeModifier.Operation operation,
            String namedKey,
            double amount,
            String label
    ) {
        public static ModuleEffect attribute(Attribute attribute, double amount, AttributeModifier.Operation operation, String label) {
            return new ModuleEffect(attribute, operation, null, amount, label);
        }

        public static ModuleEffect named(String key, double value, String label) {
            return new ModuleEffect(null, null, key, value, label);
        }

        public boolean isNamed() {
            return namedKey != null;
        }

        public boolean positive() {
            return amount >= 0.0D;
        }
    }
}
