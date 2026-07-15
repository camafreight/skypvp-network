package network.skypvp.paper.item.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime identity and mode-owned payload for one physical {@link org.bukkit.inventory.ItemStack}.
 */
public record CustomItemInstance(
        UUID instanceId,
        CustomItemTypeId typeId,
        int schemaVersion,
        byte[] payload
) {

    public CustomItemInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(typeId, "typeId");
        payload = payload == null ? new byte[0] : payload.clone();
    }

    public byte[] payloadCopy() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CustomItemInstance that)) {
            return false;
        }
        return instanceId.equals(that.instanceId)
                && typeId.equals(that.typeId)
                && schemaVersion == that.schemaVersion
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(instanceId, typeId, schemaVersion);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
