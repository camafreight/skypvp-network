package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/** Physical loot item that unlocks a craft workbench recipe when studied. */
public final class BlueprintReceiptDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "blueprint_receipt");

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
        return Material.PAPER;
    }

    @Override
    public java.util.Optional<org.bukkit.NamespacedKey> itemModel() {
        // skypvp pack art assigned by infra/scripts/Assign-UnassignedItemArt.ps1.
        return java.util.Optional.of(new org.bukkit.NamespacedKey("skypvp", "blueprint"));
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
