package network.skypvp.extraction.ai.raider;

import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Central combat-target assignment for gunner AI. */
public final class RaiderCombatTargets {

    @FunctionalInterface
    public interface PlayerTargetGate {
        boolean allows(Player player);
    }

    private RaiderCombatTargets() {
    }

    public static boolean assign(Mob mob, Player player, PlayerTargetGate gate) {
        if (mob == null || player == null || gate == null || !gate.allows(player)) {
            return false;
        }
        mob.setTarget(player);
        return true;
    }
}
