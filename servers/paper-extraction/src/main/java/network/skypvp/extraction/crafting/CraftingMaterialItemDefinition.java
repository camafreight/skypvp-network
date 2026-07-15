package network.skypvp.extraction.crafting;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/** Physical crafting material item — amount on stack; material id in payload. */
public final class CraftingMaterialItemDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "crafting_material");

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
    public int schemaVersion() {
        return 1;
    }

    @Override
    public boolean stackable() {
        return true;
    }
}
