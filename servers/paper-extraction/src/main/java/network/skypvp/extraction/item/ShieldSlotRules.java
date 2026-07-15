package network.skypvp.extraction.item;

import java.util.Optional;

/**
 * Validates dedicated shield socket insertions on Infuse armor.
 */
public final class ShieldSlotRules {

    public sealed interface Result {
        record Success(ShieldSocketReference socketed) implements Result {
        }

        record Failure(String message) implements Result {
        }
    }

    private ShieldSlotRules() {
    }

    public static Result validateSocket(InfuseArmorPayload armor, ShieldModulePayload shieldModule) {
        if (armor == null) {
            return new Result.Failure("Armor payload is missing.");
        }
        if (shieldModule == null) {
            return new Result.Failure("Shield module payload is missing.");
        }
        GearRarity armorRarity = armor.rarity();
        if (!armorRarity.hasShieldSlot()) {
            return new Result.Failure("This armor rarity has no shield socket.");
        }
        ArmorMark mark = armor.mark() == null ? ArmorMark.MK1 : armor.mark();
        if (mark.level() > armorRarity.maxMark().level()) {
            return new Result.Failure("Armor mark exceeds this rarity's maximum (" + armorRarity.maxMark().displayName() + ").");
        }
        GearRarity shieldRarity = shieldModule.shieldRarity();
        ArmorMark required = ArmorMark.requiredForShield(shieldRarity);
        if (!mark.isAtLeast(required)) {
            return new Result.Failure(
                    shieldRarity.displayName() + " shields require " + required.displayName()
                            + " (armor is " + mark.displayName() + ")."
            );
        }
        return new Result.Success(ShieldSocketReference.fromModule(shieldModule));
    }

    public static boolean isShieldSocketReference(String moduleId) {
        return ShieldSocketReference.decode(moduleId).isPresent();
    }

    public static Result validateGeneralModuleSlot(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return new Result.Success(null);
        }
        if (isShieldSocketReference(moduleId)) {
            return new Result.Failure("Shield modules must be installed in the dedicated shield socket.");
        }
        if (looksLikeShieldModuleItemRef(moduleId)) {
            return new Result.Failure("Shield modules must be installed in the dedicated shield socket.");
        }
        return new Result.Success(null);
    }

    public static Optional<ShieldSocketReference> equippedShield(InfuseArmorPayload armor) {
        if (armor == null || armor.shieldModule() == null || armor.shieldModule().isBlank()) {
            return Optional.empty();
        }
        Optional<ShieldSocketReference> decoded = ShieldSocketReference.decode(armor.shieldModule());
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        Result validation = validateSocket(armor, new ShieldModulePayload(decoded.get().shieldRarity(), decoded.get().variantId()));
        if (validation instanceof Result.Failure) {
            return Optional.empty();
        }
        return decoded;
    }

    public static Result validateMarkUpgrade(InfuseArmorPayload armor, ArmorMark targetMark) {
        if (armor == null || targetMark == null) {
            return new Result.Failure("Armor or mark is missing.");
        }
        if (targetMark.level() < ArmorMark.MK1.level()) {
            return new Result.Failure("Invalid mark.");
        }
        if (targetMark.level() > armor.rarity().maxMark().level()) {
            return new Result.Failure(
                    armor.rarity().displayName() + " armor cannot exceed " + armor.rarity().maxMark().displayName() + "."
            );
        }
        Optional<ShieldSocketReference> equipped = ShieldSocketReference.decode(
                armor.shieldModule() == null ? "" : armor.shieldModule()
        );
        if (equipped.isPresent() && targetMark.level() < ArmorMark.requiredForShield(equipped.get().shieldRarity()).level()) {
            return new Result.Failure("Remove the equipped shield before downgrading mark.");
        }
        return new Result.Success(equipped.orElse(null));
    }

    private static boolean looksLikeShieldModuleItemRef(String moduleId) {
        return moduleId.startsWith(ShieldModuleDefinition.TYPE_ID.uid());
    }
}
