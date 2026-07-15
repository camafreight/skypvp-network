package network.skypvp.extraction.gameplay;

import network.skypvp.paper.service.PlayerHealthService;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class BreachPlayerVitality {

    /** Health pool a live raider spawns with. Vanilla hearts render this as a 10-heart percentage bar. */
    public static final double RAID_MAX_HEALTH = 40.0D;

    private static volatile PlayerHealthService healthService;

    private BreachPlayerVitality() {
    }

    /** Bind the core health service once at bootstrap so enroll/restore can route through the custom pool. */
    public static void bind(PlayerHealthService service) {
        healthService = service;
    }

    /**
     * Prepare a player to spawn/live inside a raid: reset transient state and enroll them into the 40-health pool
     * (full heal, hearts scaled to a percentage bar).
     */
    public static void replenish(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        resetTransient(player);
        PlayerHealthService service = healthService;
        if (service != null) {
            service.enroll(player, RAID_MAX_HEALTH);
        } else {
            fullHealVanilla(player);
        }
    }

    /**
     * Return a player to normal vanilla vitality when they leave the raid (extract, eliminate, abandon, match end,
     * disconnect return). Resets the pool back to 20 max with unscaled hearts.
     */
    public static void restore(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        resetTransient(player);
        PlayerHealthService service = healthService;
        if (service != null) {
            service.unenroll(player);
        } else {
            fullHealVanilla(player);
        }
    }

    private static void resetTransient(Player player) {
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
    }

    private static void fullHealVanilla(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0D);
    }
}
