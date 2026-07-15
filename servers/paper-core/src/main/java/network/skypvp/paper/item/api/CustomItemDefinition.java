package network.skypvp.paper.item.api;

import java.util.Optional;
import org.bukkit.Material;

/**
 * Static metadata for a custom item type. Behavior, stats, and lore are supplied by the registering mode.
 */
public interface CustomItemDefinition {

    CustomItemTypeId typeId();

    /** Owning gamemode key (e.g. {@code extraction}, {@code lobby}). */
    String modeKey();

    ItemCategory category();

    Material displayMaterial();

    /** Mode-owned payload schema version for migrations. */
    int schemaVersion();

    /** When set, the item only equips in this armor/hand slot. */
    default Optional<EquipmentSlotGroup> requiredEquipmentSlot() {
        return Optional.empty();
    }

    /**
     * Resource-pack item model applied to created stacks
     * ({@code assets/<ns>/items/<key>.json}); empty keeps the vanilla material look.
     */
    default Optional<org.bukkit.NamespacedKey> itemModel() {
        return Optional.empty();
    }

    /**
     * When {@code true}, all stacks with the same type and payload share one deterministic instance id so
     * vanilla {@link org.bukkit.inventory.ItemStack#isSimilar(org.bukkit.inventory.ItemStack)} merging works.
     */
    default boolean stackable() {
        return false;
    }
}
