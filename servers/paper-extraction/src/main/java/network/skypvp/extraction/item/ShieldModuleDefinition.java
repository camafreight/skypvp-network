package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

public final class ShieldModuleDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "shield_module");

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
        return ItemCategory.MISC;
    }

    @Override
    public Material displayMaterial() {
        return Material.HEAVY_CORE;
    }

    @Override
    public java.util.Optional<org.bukkit.NamespacedKey> itemModel() {
        // skypvp pack art assigned by infra/scripts/Assign-UnassignedItemArt.ps1.
        return java.util.Optional.of(new org.bukkit.NamespacedKey("skypvp", "shield_module"));
    }

    @Override
    public int schemaVersion() {
        return 2;
    }
}
