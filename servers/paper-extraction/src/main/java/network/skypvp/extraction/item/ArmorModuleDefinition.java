package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/**
 * One physical Infuse module item. There is a definition per {@link ArmorModuleType} so each module can carry its
 * own icon; the module identity lives entirely in the type id ({@code extraction:module_<id>}), no payload needed.
 */
public final class ArmorModuleDefinition implements CustomItemDefinition {

    private final ArmorModuleType type;

    public ArmorModuleDefinition(ArmorModuleType type) {
        this.type = type;
    }

    public ArmorModuleType moduleType() {
        return type;
    }

    @Override
    public CustomItemTypeId typeId() {
        return type.typeId();
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
        return type.material();
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
