package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/** Rare in-raid consumable that emergency-repairs a destroyed or critically broken socketed shield. */
public final class ShieldRepairKitDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "shield_repair_kit");

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
        return ItemCategory.CONSUMABLE;
    }

    @Override
    public Material displayMaterial() {
        return Material.NETHERITE_SCRAP;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
