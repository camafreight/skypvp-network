package network.skypvp.extraction.item;

import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/** Quest objective item for {@code stranded_pilot} — turn in at the hub pilot NPC. */
public final class FlightRecorderDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "flight_recorder");

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
        return Material.RECOVERY_COMPASS;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
