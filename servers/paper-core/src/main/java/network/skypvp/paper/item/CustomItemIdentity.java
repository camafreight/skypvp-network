package network.skypvp.paper.item;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import network.skypvp.paper.item.api.CustomItemTypeId;

/** Stable instance ids for stackable custom item types. */
final class CustomItemIdentity {

    private CustomItemIdentity() {
    }

    static UUID createInstanceId(boolean stackable, CustomItemTypeId typeId, byte[] payload, UUID explicit) {
        if (stackable) {
            return stackableInstanceId(typeId, payload);
        }
        return explicit != null ? explicit : UUID.randomUUID();
    }

    static UUID stackableInstanceId(CustomItemTypeId typeId, byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        String materialKey = typeId.uid() + "\0" + java.util.Base64.getEncoder().encodeToString(safePayload);
        return UUID.nameUUIDFromBytes(("skypvp:stackable:" + materialKey).getBytes(StandardCharsets.UTF_8));
    }
}
