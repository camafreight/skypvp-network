package network.skypvp.paper.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Core custom-health system.
 *
 * <p>Modes enroll players with an arbitrary max-health pool (e.g. 40) while the vanilla heart bar is scaled to a
 * fixed 10 hearts via {@link Player#setHealthScale(double)} so it renders as a <em>percentage</em> of the pool.
 * Because the real (larger) health value keeps flowing through the vanilla damage/regen/death pipeline, no damage
 * interception is required — a lethal blow at 0 pool health triggers the normal death path, and existing combat
 * handlers that read {@link Player#getHealth()} automatically work against the larger pool.</p>
 */
public final class PlayerHealthService {

    /** Vanilla heart bar is always shown as this many half-heart units (20 = 10 hearts). */
    public static final double DISPLAY_SCALE = 20.0D;
    private static final double VANILLA_MAX = 20.0D;

    private final Set<UUID> managed = ConcurrentHashMap.newKeySet();

    /** Enroll the player into the custom pool at {@code maxHealth} and fully heal them. */
    public void enroll(Player player, double maxHealth) {
        apply(player, maxHealth, true);
    }

    /** Re-apply the pool max + heart scaling without healing (e.g. after a reconnect), preserving current health. */
    public void reapply(Player player, double maxHealth) {
        apply(player, maxHealth, false);
    }

    private void apply(Player player, double maxHealth, boolean fullHeal) {
        if (player == null || !player.isOnline()) {
            return;
        }
        double max = Math.max(1.0D, maxHealth);
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(max);
        }
        managed.add(player.getUniqueId());
        if (fullHeal) {
            player.setHealth(max);
        } else {
            player.setHealth(Math.min(Math.max(0.5D, player.getHealth()), max));
        }
        player.setHealthScale(DISPLAY_SCALE);
        player.setHealthScaled(true);
    }

    /** Restore the player to vanilla health (20 max, unscaled hearts). Safe to call when not enrolled. */
    public void unenroll(Player player) {
        if (player == null) {
            return;
        }
        managed.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(VANILLA_MAX);
        }
        player.setHealthScaled(false);
        if (player.getHealth() > VANILLA_MAX) {
            player.setHealth(VANILLA_MAX);
        }
    }

    public boolean isManaged(UUID playerId) {
        return playerId != null && managed.contains(playerId);
    }

    public boolean isManaged(Player player) {
        return player != null && isManaged(player.getUniqueId());
    }

    /** Effective max health (attribute value including modifiers). */
    public double maxHealth(Player player) {
        AttributeInstance attr = player == null ? null : player.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : VANILLA_MAX;
    }

    public double health(Player player) {
        return player == null ? 0.0D : Math.max(0.0D, player.getHealth());
    }

    /** Fraction of the pool remaining, in {@code [0,1]}. */
    public double percent(Player player) {
        if (player == null) {
            return 0.0D;
        }
        double max = maxHealth(player);
        if (max <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, player.getHealth() / max));
    }

    /** Add health up to the pool max. */
    public void heal(Player player, double amount) {
        if (player == null || !player.isOnline() || amount <= 0.0D) {
            return;
        }
        double max = maxHealth(player);
        player.setHealth(Math.min(max, Math.max(0.0D, player.getHealth()) + amount));
    }

    /** Drop tracking for a player who has left (does not touch their live state). */
    public void clear(UUID playerId) {
        if (playerId != null) {
            managed.remove(playerId);
        }
    }
}
