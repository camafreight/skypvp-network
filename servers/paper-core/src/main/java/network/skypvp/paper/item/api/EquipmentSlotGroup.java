package network.skypvp.paper.item.api;

import org.bukkit.inventory.EquipmentSlot;

/**
 * Equipment slots tracked by the custom item engine for equip/unequip lifecycle.
 */
public enum EquipmentSlotGroup {
    HEAD(EquipmentSlot.HEAD),
    CHEST(EquipmentSlot.CHEST),
    LEGS(EquipmentSlot.LEGS),
    FEET(EquipmentSlot.FEET),
    MAIN_HAND(EquipmentSlot.HAND),
    OFF_HAND(EquipmentSlot.OFF_HAND);

    private final EquipmentSlot bukkitSlot;

    EquipmentSlotGroup(EquipmentSlot bukkitSlot) {
        this.bukkitSlot = bukkitSlot;
    }

    public EquipmentSlot bukkitSlot() {
        return bukkitSlot;
    }

    public static EquipmentSlotGroup fromBukkit(EquipmentSlot slot) {
        if (slot == null) {
            return null;
        }
        return switch (slot) {
            case HEAD -> HEAD;
            case CHEST -> CHEST;
            case LEGS -> LEGS;
            case FEET -> FEET;
            case HAND -> MAIN_HAND;
            case OFF_HAND -> OFF_HAND;
            default -> null;
        };
    }
}
