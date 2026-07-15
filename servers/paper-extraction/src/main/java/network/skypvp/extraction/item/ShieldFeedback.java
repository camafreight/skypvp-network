package network.skypvp.extraction.item;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Visual/audio feedback for shield events. Called from the victim's region thread (entity damage / interact events),
 * so direct particle + sound spawning is Folia-safe.
 */
public final class ShieldFeedback {

    private static final Particle.DustOptions AQUA_DUST =
            new Particle.DustOptions(Color.fromRGB(0, 200, 255), 1.2F);
    private static final Particle.DustOptions BLUE_DUST =
            new Particle.DustOptions(Color.fromRGB(40, 120, 255), 1.4F);

    private ShieldFeedback() {
    }

    public static void absorb(Player player, double absorbed) {
        if (player == null || absorbed <= 0.0D) {
            return;
        }
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        int count = Math.min(18, 4 + (int) Math.ceil(absorbed));
        player.getWorld().spawnParticle(Particle.DUST, center, count, 0.45D, 0.7D, 0.45D, 0.0D, AQUA_DUST);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.5F, 1.6F);
    }

    public static void depleted(Player player) {
        if (player == null) {
            return;
        }
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.getWorld().spawnParticle(Particle.DUST, center, 30, 0.5D, 0.9D, 0.5D, 0.0D, BLUE_DUST);
        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center, 20, 0.5D, 0.9D, 0.5D, 0.15D);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.9F, 1.35F);
        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 0.7F, 1.4F);
    }

    public static void destroyed(Player player) {
        if (player == null) {
            return;
        }
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.getWorld().spawnParticle(Particle.DUST, center, 45, 0.6D, 1.0D, 0.6D, 0.0D, BLUE_DUST);
        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center, 35, 0.6D, 1.0D, 0.6D, 0.2D);
        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0F, 0.75F);
        player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6F, 1.2F);
    }

    public static void recharged(Player player) {
        if (player == null) {
            return;
        }
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.getWorld().spawnParticle(Particle.DUST, center, 24, 0.45D, 0.8D, 0.45D, 0.0D, AQUA_DUST);
        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.8F, 1.5F);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5F, 1.8F);
    }

    public static void rechargeStarted(Player player) {
        if (player == null) {
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6F, 1.7F);
    }

    public static void rechargeTick(Player player) {
        if (player == null) {
            return;
        }
        Location center = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.getWorld().spawnParticle(Particle.DUST, center, 6, 0.4D, 0.7D, 0.4D, 0.0D, AQUA_DUST);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.25F, 1.9F);
    }
}
