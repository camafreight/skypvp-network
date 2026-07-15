package network.skypvp.paper.item;

import java.util.Optional;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import network.skypvp.paper.item.api.ItemCategory;

final class CustomItemEquipRules {

    private CustomItemEquipRules() {
    }

    static boolean acceptsSlot(CustomItemDefinition definition, EquipmentSlotGroup slot) {
        if (definition == null || slot == null) {
            return false;
        }
        Optional<EquipmentSlotGroup> required = definition.requiredEquipmentSlot();
        if (required.isPresent() && required.get() != slot) {
            return false;
        }
        return switch (definition.category()) {
            case ARMOR -> slot == EquipmentSlotGroup.HEAD
                    || slot == EquipmentSlotGroup.CHEST
                    || slot == EquipmentSlotGroup.LEGS
                    || slot == EquipmentSlotGroup.FEET;
            case WEAPON_PROXY, TOOL, CONSUMABLE -> slot == EquipmentSlotGroup.MAIN_HAND
                    || slot == EquipmentSlotGroup.OFF_HAND;
            case MISC -> true;
        };
    }
}
