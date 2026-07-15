package network.skypvp.paper.item;

import network.skypvp.paper.item.api.CustomStatEffect;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ActiveEffectManager {

    private final Map<UUID, PlayerEffects> byPlayer = new HashMap<>();

    double namedStat(Player player, String key) {
        PlayerEffects effects = byPlayer.get(player.getUniqueId());
        if (effects == null) {
            return 0.0D;
        }
        return effects.namedStats.getOrDefault(key, 0.0D);
    }

    void apply(Player player, EquipmentSlotGroup slot, List<CustomStatEffect> effects) {
        clear(player, slot);
        if (effects == null || effects.isEmpty()) {
            return;
        }
        PlayerEffects playerEffects = byPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerEffects());
        SlotEffects slotEffects = new SlotEffects();
        for (CustomStatEffect effect : effects) {
            if (effect instanceof CustomStatEffect.AttributeEffect attributeEffect) {
                AttributeInstance attribute = player.getAttribute(attributeEffect.attribute());
                if (attribute == null) {
                    continue;
                }
                AttributeModifier modifier = new AttributeModifier(
                        attributeEffect.modifierId(),
                        "skypvp-custom-item-" + slot.name().toLowerCase(),
                        attributeEffect.amount(),
                        attributeEffect.operation()
                );
                attribute.addModifier(modifier);
                slotEffects.attributeModifiers.add(new AppliedAttribute(attributeEffect.attribute(), modifier));
            } else if (effect instanceof CustomStatEffect.NamedEffect namedEffect) {
                slotEffects.namedStats.merge(namedEffect.key(), namedEffect.value(), Double::sum);
                playerEffects.namedStats.merge(namedEffect.key(), namedEffect.value(), Double::sum);
            }
        }
        playerEffects.bySlot.put(slot, slotEffects);
    }

    void clear(Player player, EquipmentSlotGroup slot) {
        PlayerEffects playerEffects = byPlayer.get(player.getUniqueId());
        if (playerEffects == null) {
            return;
        }
        SlotEffects slotEffects = playerEffects.bySlot.remove(slot);
        if (slotEffects == null) {
            return;
        }
        for (AppliedAttribute applied : slotEffects.attributeModifiers) {
            AttributeInstance attribute = player.getAttribute(applied.attribute());
            if (attribute != null) {
                attribute.removeModifier(applied.modifier());
            }
        }
        for (Map.Entry<String, Double> entry : slotEffects.namedStats.entrySet()) {
            playerEffects.namedStats.computeIfPresent(entry.getKey(), (key, current) -> {
                double next = current - entry.getValue();
                return next == 0.0D ? null : next;
            });
        }
        if (playerEffects.bySlot.isEmpty() && playerEffects.namedStats.isEmpty()) {
            byPlayer.remove(player.getUniqueId());
        }
    }

    void clearAll(Player player) {
        PlayerEffects playerEffects = byPlayer.remove(player.getUniqueId());
        if (playerEffects == null) {
            return;
        }
        for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
            SlotEffects slotEffects = playerEffects.bySlot.get(slot);
            if (slotEffects == null) {
                continue;
            }
            for (AppliedAttribute applied : slotEffects.attributeModifiers) {
                AttributeInstance attribute = player.getAttribute(applied.attribute());
                if (attribute != null) {
                    attribute.removeModifier(applied.modifier());
                }
            }
        }
    }

    private record AppliedAttribute(Attribute attribute, AttributeModifier modifier) {
    }

    private static final class PlayerEffects {
        private final EnumMap<EquipmentSlotGroup, SlotEffects> bySlot = new EnumMap<>(EquipmentSlotGroup.class);
        private final Map<String, Double> namedStats = new HashMap<>();
    }

    private static final class SlotEffects {
        private final List<AppliedAttribute> attributeModifiers = new java.util.ArrayList<>();
        private final Map<String, Double> namedStats = new HashMap<>();
    }
}
