package network.skypvp.extraction.item;

import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.paper.item.api.CustomStatEffect;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.StatContributor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class InfuseArmorStatContributor implements StatContributor {

    @Override
    public List<CustomStatEffect> effects(LiveItemContext ctx) {
        InfuseArmorPayload payload = InfuseArmorPayload.decode(ctx.instance().payloadCopy());
        GearRarity rarity = payload.rarity();
        String instanceId = ctx.instance().instanceId().toString();
        InfuseArmorPiece piece = InfuseArmorPiece.byTypeId(ctx.definition().typeId()).orElse(InfuseArmorPiece.CHESTPLATE);
        List<CustomStatEffect> effects = new ArrayList<>();

        if (rarity.defensePercent() > 0.0D) {
            double defense = rarity.defensePercent() * piece.defenseShare()
                    * ItemConfigOverrides.defenseMultiplier(ctx.definition().typeId());
            if (defense > 0.0D) {
                effects.add(new CustomStatEffect.NamedEffect(
                        modifierId(instanceId, "defense"),
                        GearRarity.DEFENSE_STAT_KEY,
                        defense
                ));
            }
        }

        ArmorSet set = payload.armorSet() == null ? ArmorSet.VANGUARD : payload.armorSet();
        if (set.bonusAmountA() != 0.0D) {
            appendSetBonus(effects, instanceId, "set-a", set.bonusKeyA(), set.bonusAmountA() * piece.setBonusShare());
        }
        if (set.bonusAmountB() != 0.0D) {
            appendSetBonus(effects, instanceId, "set-b", set.bonusKeyB(), set.bonusAmountB() * piece.setBonusShare());
        }

        List<String> modules = payload.moduleSockets();
        for (int i = 0; i < modules.size(); i++) {
            String moduleId = modules.get(i);
            int socketIndex = i;
            ArmorModuleType.byId(moduleId).ifPresent(type ->
                    appendModuleEffects(effects, instanceId, "module-" + socketIndex + "-" + type.id(), type));
        }
        if (piece.isChestplate()) {
            ArmorModuleType.byId(payload.overclockModule()).ifPresent(type ->
                    appendModuleEffects(effects, instanceId, "overclock-" + type.id(), type));
        }

        return effects;
    }

    private static void appendSetBonus(
            List<CustomStatEffect> effects,
            String instanceId,
            String slotKey,
            String key,
            double amount
    ) {
        UUID id = modifierId(instanceId, slotKey);
        if (ExtractionStatKeys.MOVEMENT_SPEED_MULT.equals(key)) {
            effects.add(new CustomStatEffect.AttributeEffect(
                    id,
                    Attribute.MOVEMENT_SPEED,
                    amount,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1
            ));
        } else {
            effects.add(new CustomStatEffect.NamedEffect(id, key, amount));
        }
    }

    private static void appendModuleEffects(
            List<CustomStatEffect> effects,
            String instanceId,
            String slotKey,
            ArmorModuleType type
    ) {
        List<ArmorModuleType.ModuleEffect> moduleEffects = type.effects();
        for (int i = 0; i < moduleEffects.size(); i++) {
            ArmorModuleType.ModuleEffect effect = moduleEffects.get(i);
            UUID id = modifierId(instanceId, slotKey + "-" + i);
            if (effect.isNamed()) {
                effects.add(new CustomStatEffect.NamedEffect(id, effect.namedKey(), effect.amount()));
            } else {
                effects.add(new CustomStatEffect.AttributeEffect(id, effect.attribute(), effect.amount(), effect.operation()));
            }
        }
    }

    private static UUID modifierId(String instanceId, String suffix) {
        return UUID.nameUUIDFromBytes(("extraction-infuse-" + instanceId + "-" + suffix).getBytes(StandardCharsets.UTF_8));
    }
}
