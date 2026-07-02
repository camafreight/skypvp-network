package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

final class PacketEntityTypes {

    private PacketEntityTypes() {
    }

    static com.github.retrooper.packetevents.protocol.entity.type.EntityType fromBukkit(org.bukkit.entity.EntityType type) {
        org.bukkit.entity.EntityType resolved = type == null ? org.bukkit.entity.EntityType.VILLAGER : type;
        String key = resolved.key().asString();
        com.github.retrooper.packetevents.protocol.entity.type.EntityType mapped = EntityTypes.getByName(key);
        if (mapped != null) {
            return mapped;
        }
        mapped = EntityTypes.getByName("minecraft:" + resolved.name().toLowerCase(java.util.Locale.ROOT));
        if (mapped != null) {
            return mapped;
        }
        return EntityTypes.VILLAGER;
    }
}
