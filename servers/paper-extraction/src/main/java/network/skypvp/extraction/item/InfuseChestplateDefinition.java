package network.skypvp.extraction.item;

import java.util.Optional;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

public final class InfuseChestplateDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "infuse_chestplate");

    @Override
    public CustomItemTypeId typeId() {
        return TYPE_ID;
    }

    @Override
    public String modeKey() {
        return "extraction";
    }

    @Override
    public ItemCategory category() {
        return ItemCategory.ARMOR;
    }

    @Override
    public Material displayMaterial() {
        return Material.NETHERITE_CHESTPLATE;
    }

    @Override
    public int schemaVersion() {
        return 2;
    }

    @Override
    public Optional<EquipmentSlotGroup> requiredEquipmentSlot() {
        return Optional.of(EquipmentSlotGroup.CHEST);
    }
}
