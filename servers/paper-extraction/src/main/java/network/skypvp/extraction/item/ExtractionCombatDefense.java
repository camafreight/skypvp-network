package network.skypvp.extraction.item;

import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Applies Infuse armor defense ({@link GearRarity#DEFENSE_STAT_KEY}) to incoming breach damage.
 */
public final class ExtractionCombatDefense {

    private static final double MAX_DEFENSE_FRACTION = 0.90D;

    private ExtractionCombatDefense() {
    }

    public record DamageAdjustment(
            double defenseFraction,
            double damageMultiplier,
            double damageBefore,
            double damageAfter,
            double infuseAbsorbed
    ) {
    }

    public static double defenseFraction(PaperCorePlugin core, Player player) {
        if (core == null || core.customItemService() == null || player == null) {
            return 0.0D;
        }
        double defense = core.customItemService().namedStat(player, GearRarity.DEFENSE_STAT_KEY);
        if (defense <= 0.0D) {
            return 0.0D;
        }
        return Math.min(defense, MAX_DEFENSE_FRACTION);
    }

    public static double damageMultiplier(PaperCorePlugin core, Player player) {
        return 1.0D - defenseFraction(core, player);
    }

    /**
     * Scales base damage so {@link EntityDamageEvent#getFinalDamage()} matches {@code targetFinalDamage}, preserving
     * armor/enchantment modifiers. Subtracting directly from {@link EntityDamageEvent#getDamage()} while reading
     * {@code getFinalDamage()} leaves residual health damage when modifiers are active.
     */
    public static void scaleDamageToFinal(EntityDamageEvent event, double targetFinalDamage) {
        if (event == null) {
            return;
        }
        double target = Math.max(0.0D, targetFinalDamage);
        double currentFinal = Math.max(0.0D, event.getFinalDamage());
        if (target <= 0.0D) {
            event.setDamage(0.0D);
            return;
        }
        if (currentFinal <= 0.0D) {
            return;
        }
        event.setDamage(Math.max(0.0D, event.getDamage() * (target / currentFinal)));
    }

    public static DamageAdjustment applyToDamage(PaperCorePlugin core, Player victim, EntityDamageEvent event) {
        double before = Math.max(0.0D, event.getFinalDamage());
        double defense = defenseFraction(core, victim);
        double multiplier = 1.0D - defense;
        if (multiplier >= 1.0D) {
            return new DamageAdjustment(defense, 1.0D, before, before, 0.0D);
        }
        scaleDamageToFinal(event, before * multiplier);
        double after = Math.max(0.0D, event.getFinalDamage());
        return new DamageAdjustment(
                defense,
                multiplier,
                before,
                after,
                Math.max(0.0D, before - after)
        );
    }
}
