package network.skypvp.paper.library.npc;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Server-authoritative player-like NPC backed by a real {@link LivingEntity}.
 * <p>
 * These entities participate in combat, equipment, AI (MythicMobs), and death events.
 * Player appearance is applied externally — typically MythicMobs {@code Disguise: player}
 * with LibsDisguises on the underlying mob type (for example {@code HUSK}).
 * <p>
 * Contrast with {@link FakePlayerNpc}, which sends client-only player packets and cannot
 * take damage or run WeaponMechanics skills.
 */
public final class RealPlayerNpc {

    private RealPlayerNpc() {
    }

    /** Applies baseline survival traits for outdoor player-disguised combat NPCs. */
    public static void applySurvivalTraits(LivingEntity entity) {
        entity.setVisualFire(false);
        entity.setFireTicks(0);
        entity.addPotionEffect(new PotionEffect(
                PotionEffectType.FIRE_RESISTANCE,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                true
        ));
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
    }

    /** Clears daylight combustion on undead-backed player NPCs. */
    public static void cancelCombust(LivingEntity entity) {
        entity.setFireTicks(0);
        entity.setVisualFire(false);
    }
}
