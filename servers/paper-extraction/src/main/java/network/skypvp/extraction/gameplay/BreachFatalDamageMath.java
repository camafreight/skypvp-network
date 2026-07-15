package network.skypvp.extraction.gameplay;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Determines whether an {@link EntityDamageEvent} would eliminate a live raider.
 */
public final class BreachFatalDamageMath {

    private static final double EPSILON = 1.0E-4D;

    private BreachFatalDamageMath() {
    }

    public static boolean wouldEliminate(Player player, EntityDamageEvent event) {
        if (player == null || event == null || event.isCancelled()) {
            return false;
        }
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return false;
        }
        if (player.isInvulnerable()) {
            return false;
        }
        double incoming = Math.max(0.0D, event.getFinalDamage());
        if (incoming <= EPSILON) {
            return false;
        }
        return remainingVitality(player) - incoming <= EPSILON;
    }

    public static double remainingVitality(Player player) {
        return Math.max(0.0D, player.getHealth()) + Math.max(0.0D, player.getAbsorptionAmount());
    }
}
