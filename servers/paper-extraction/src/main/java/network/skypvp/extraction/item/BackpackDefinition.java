package network.skypvp.extraction.item;

import java.util.Optional;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.Material;

/**
 * Tiered raid backpack worn in the offhand slot. Display material is inert {@link Material#PAPER}
 * with a per-tier/skin {@code skypvp:backpack_t*} item model — not {@link Material#BUNDLE}, because
 * a usable offhand item steals right-click from main-hand weapons (WeaponMechanics shooting).
 * Stored loot lives only in the custom payload.
 */
public final class BackpackDefinition implements CustomItemDefinition {

    public static final CustomItemTypeId TYPE_ID = new CustomItemTypeId("extraction", "backpack");
    public static final int MAX_TIER = 4;

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
    public Optional<EquipmentSlotGroup> requiredEquipmentSlot() {
        return Optional.of(EquipmentSlotGroup.OFF_HAND);
    }

    @Override
    public int schemaVersion() {
        // v2: display material migrated BUNDLE → PAPER (inert offhand; models unchanged).
        return 2;
    }
}
