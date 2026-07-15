package network.skypvp.paper.item;

import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemTypeId;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;

final class CustomItemCodec {

    private final CustomItemKeys keys;

    CustomItemCodec(CustomItemKeys keys) {
        this.keys = keys;
    }

    boolean isCustomItem(ItemStack stack) {
        return read(stack).isPresent();
    }

    Optional<CustomItemInstance> read(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String typeUid = pdc.get(keys.itemType, PersistentDataType.STRING);
        String instanceRaw = pdc.get(keys.itemInstance, PersistentDataType.STRING);
        Integer version = pdc.get(keys.itemVersion, PersistentDataType.INTEGER);
        if (typeUid == null || instanceRaw == null || version == null) {
            return Optional.empty();
        }
        byte[] payload = pdc.getOrDefault(keys.itemPayload, PersistentDataType.BYTE_ARRAY, new byte[0]);
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceRaw);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CustomItemInstance(instanceId, CustomItemTypeId.parse(typeUid), version, payload));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    void write(ItemStack stack, CustomItemInstance instance) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.itemType, PersistentDataType.STRING, instance.typeId().uid());
        pdc.set(keys.itemInstance, PersistentDataType.STRING, instance.instanceId().toString());
        pdc.set(keys.itemVersion, PersistentDataType.INTEGER, instance.schemaVersion());
        pdc.set(keys.itemPayload, PersistentDataType.BYTE_ARRAY, instance.payloadCopy());
        stack.setItemMeta(meta);
    }
}
