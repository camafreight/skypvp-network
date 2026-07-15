package network.skypvp.paper.item;

import network.skypvp.paper.item.api.CustomItemBehavior;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemProvider;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.LoreSectionContributor;
import network.skypvp.paper.item.api.StatContributor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class CustomItemRegistry {

    record RegisteredType(
            CustomItemProvider provider,
            CustomItemDefinition definition
    ) {
    }

    private final Map<CustomItemTypeId, RegisteredType> byType = new HashMap<>();

    void registerProvider(CustomItemProvider provider) {
        for (CustomItemDefinition definition : provider.definitions()) {
            CustomItemTypeId typeId = definition.typeId();
            RegisteredType existing = byType.get(typeId);
            if (existing != null && existing.provider() != provider) {
                throw new IllegalStateException("Duplicate custom item type registration: " + typeId);
            }
            byType.put(typeId, new RegisteredType(provider, definition));
        }
    }

    Optional<RegisteredType> resolve(CustomItemTypeId typeId) {
        return Optional.ofNullable(byType.get(typeId));
    }

    Collection<RegisteredType> allTypes() {
        return byType.values();
    }

    Optional<CustomItemBehavior> behavior(CustomItemTypeId typeId) {
        return resolve(typeId).flatMap(type -> type.provider().behavior(typeId));
    }

    Optional<StatContributor> statContributor(CustomItemTypeId typeId) {
        return resolve(typeId).flatMap(type -> type.provider().statContributor(typeId));
    }

    Optional<LoreSectionContributor> loreContributor(CustomItemTypeId typeId) {
        return resolve(typeId).flatMap(type -> type.provider().loreContributor(typeId));
    }
}
