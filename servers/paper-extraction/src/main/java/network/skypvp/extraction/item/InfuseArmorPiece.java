package network.skypvp.extraction.item;

import java.util.Locale;
import java.util.Optional;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import org.bukkit.Material;

/** Infuse armor slot identity; defense and set bonuses scale per piece so a full set reaches 100%. */
public enum InfuseArmorPiece {

    HELMET(
            InfuseHelmetDefinition.TYPE_ID,
            EquipmentSlotGroup.HEAD,
            Material.NETHERITE_HELMET,
            "Helmet",
            0.20D,
            0.25D,
            0.60D
    ),
    CHESTPLATE(
            InfuseChestplateDefinition.TYPE_ID,
            EquipmentSlotGroup.CHEST,
            Material.NETHERITE_CHESTPLATE,
            "Chestplate",
            0.40D,
            0.25D,
            1.00D
    ),
    LEGGINGS(
            InfuseLeggingsDefinition.TYPE_ID,
            EquipmentSlotGroup.LEGS,
            Material.NETHERITE_LEGGINGS,
            "Leggings",
            0.25D,
            0.25D,
            0.70D
    ),
    BOOTS(
            InfuseBootsDefinition.TYPE_ID,
            EquipmentSlotGroup.FEET,
            Material.NETHERITE_BOOTS,
            "Boots",
            0.15D,
            0.25D,
            0.50D
    );

    private final CustomItemTypeId typeId;
    private final EquipmentSlotGroup slot;
    private final Material material;
    private final String label;
    private final double defenseShare;
    private final double setBonusShare;
    private final double craftCostMultiplier;

    InfuseArmorPiece(
            CustomItemTypeId typeId,
            EquipmentSlotGroup slot,
            Material material,
            String label,
            double defenseShare,
            double setBonusShare,
            double craftCostMultiplier
    ) {
        this.typeId = typeId;
        this.slot = slot;
        this.material = material;
        this.label = label;
        this.defenseShare = defenseShare;
        this.setBonusShare = setBonusShare;
        this.craftCostMultiplier = craftCostMultiplier;
    }

    public CustomItemTypeId typeId() {
        return typeId;
    }

    public EquipmentSlotGroup slot() {
        return slot;
    }

    public Material material() {
        return material;
    }

    public String label() {
        return label;
    }

    public double defenseShare() {
        return defenseShare;
    }

    public double setBonusShare() {
        return setBonusShare;
    }

    public double craftCostMultiplier() {
        return craftCostMultiplier;
    }

    public String displayName() {
        return "Infuse " + label;
    }

    public boolean isChestplate() {
        return this == CHESTPLATE;
    }

    public static Optional<InfuseArmorPiece> byTypeId(CustomItemTypeId typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        for (InfuseArmorPiece piece : values()) {
            if (piece.typeId.equals(typeId)) {
                return Optional.of(piece);
            }
        }
        return Optional.empty();
    }

    public static boolean isInfuseArmorTypeId(CustomItemTypeId typeId) {
        return byTypeId(typeId).isPresent();
    }

    public static Optional<InfuseArmorPiece> byId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (InfuseArmorPiece piece : values()) {
            if (piece.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(piece);
            }
        }
        return Optional.empty();
    }
}
