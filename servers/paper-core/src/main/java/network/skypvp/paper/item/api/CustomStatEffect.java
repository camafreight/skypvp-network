package network.skypvp.paper.item.api;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

import java.util.List;
import java.util.UUID;

/**
 * Describes a stat effect applied while an item is equipped.
 */
public sealed interface CustomStatEffect permits CustomStatEffect.AttributeEffect, CustomStatEffect.NamedEffect {

    UUID modifierId();

    record AttributeEffect(
            UUID modifierId,
            Attribute attribute,
            double amount,
            AttributeModifier.Operation operation
    ) implements CustomStatEffect {
    }

    record NamedEffect(UUID modifierId, String key, double value) implements CustomStatEffect {
    }
}
