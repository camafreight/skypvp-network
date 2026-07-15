package network.skypvp.extraction.gameplay;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plays a per-shot "hit marker" sound to the attacker so they get instant feedback on what they hit:
 * a shield block, a headshot/critical, or a normal body hit.
 *
 * <p>Headshots are sourced from WeaponMechanics' {@code WeaponDamageEntityEvent} (reflectively, so the mode still
 * compiles/loads without WM) which fires just before the Bukkit damage event; the {@code HEAD} damage point is
 * cached against the shooter for a few ticks and consumed when the marker is resolved. Vanilla bows/melee fall back
 * to arrow crit flags, projectile impact height, and the vanilla melee-crit heuristic.</p>
 */
public final class BreachHitMarkerService implements Listener {

    private static final double EPSILON = 1.0E-4D;
    private static final long HEADSHOT_TTL_MILLIS = 350L;

    public enum HitType {
        HEALTH,
        CRIT,
        HEADSHOT,
        SHIELD
    }

    private final ConcurrentHashMap<UUID, Long> headshotShooters = new ConcurrentHashMap<>();

    /** Registers the reflective WeaponMechanics headshot listener (no-op when WM is absent). */
    public void registerWeaponMechanics(JavaPlugin plugin) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass =
                    (Class<? extends Event>) Class.forName(
                            "me.deecaad.weaponmechanics.weapon.weaponevents.WeaponDamageEntityEvent");
            Method getShooter = eventClass.getMethod("getShooter");
            Method getPoint = eventClass.getMethod("getPoint");
            EventExecutor executor = (listener, event) -> {
                if (!eventClass.isInstance(event)) {
                    return;
                }
                try {
                    Object point = getPoint.invoke(event);
                    if (point == null || !"HEAD".equalsIgnoreCase(point.toString())) {
                        return;
                    }
                    if (getShooter.invoke(event) instanceof Player shooter) {
                        markHeadshot(shooter.getUniqueId());
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            };
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, this, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().info("[Breach] Hit-marker headshot detection wired to WeaponMechanics.");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().fine("[Breach] WeaponMechanics headshot event unavailable; using vanilla fallback.");
        }
    }

    public void markHeadshot(UUID shooterId) {
        if (shooterId != null) {
            headshotShooters.put(shooterId, System.currentTimeMillis() + HEADSHOT_TTL_MILLIS);
        }
    }

    private boolean consumeHeadshot(UUID shooterId) {
        if (shooterId == null) {
            return false;
        }
        Long expiry = headshotShooters.remove(shooterId);
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    /**
     * Resolve the hit type and play the marker to {@code attacker}. {@code healthDamage} is the post-shield damage
     * that reached the health pool; {@code shieldAbsorbed} is what the shield soaked this hit.
     */
    public HitType resolveAndPlay(
            Player attacker,
            LivingEntity victim,
            EntityDamageEvent event,
            double healthDamage,
            double shieldAbsorbed
    ) {
        if (attacker == null || !attacker.isOnline()) {
            headshotShooters.remove(attacker == null ? null : attacker.getUniqueId());
            return null;
        }
        boolean headshot = consumeHeadshot(attacker.getUniqueId());
        HitType type = classify(attacker, victim, event, healthDamage, shieldAbsorbed, headshot);
        if (type != null) {
            play(attacker, type);
        }
        return type;
    }

    private HitType classify(
            Player attacker,
            LivingEntity victim,
            EntityDamageEvent event,
            double healthDamage,
            double shieldAbsorbed,
            boolean headshot
    ) {
        boolean reachedHealth = healthDamage > EPSILON;
        boolean shieldBlocked = shieldAbsorbed > EPSILON;
        if (victim instanceof Player && !reachedHealth && shieldBlocked) {
            return HitType.SHIELD;
        }
        if (!reachedHealth && !shieldBlocked) {
            return null;
        }
        if (headshot || isVanillaHeadshot(event, victim)) {
            return HitType.HEADSHOT;
        }
        if (isCritical(attacker, event)) {
            return HitType.CRIT;
        }
        return HitType.HEALTH;
    }

    private static boolean isVanillaHeadshot(EntityDamageEvent event, LivingEntity victim) {
        if (victim == null || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        if (byEntity.getDamager() instanceof Projectile projectile) {
            double impactY = projectile.getLocation().getY();
            double feetY = victim.getLocation().getY();
            double height = Math.max(1.0D, victim.getHeight());
            return (impactY - feetY) >= height * 0.80D;
        }
        return false;
    }

    private static boolean isCritical(Player attacker, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof AbstractArrow arrow) {
            return arrow.isCritical();
        }
        if (byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && attacker != null) {
            return attacker.getFallDistance() > 0.0F
                    && !attacker.isOnGround()
                    && !attacker.isSprinting()
                    && !attacker.isInWater()
                    && !attacker.isClimbing()
                    && attacker.getVehicle() == null;
        }
        return false;
    }

    private static void play(Player attacker, HitType type) {
        var at = attacker.getLocation();
        switch (type) {
            // Shield block: metallic clink so it reads clearly different from a flesh hit.
            case SHIELD -> attacker.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.8F, 1.5F);
            // Headshot: brightest, layered marker so it stands apart from a normal crit.
            case HEADSHOT -> {
                attacker.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 2.0F);
                attacker.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6F, 1.7F);
            }
            // Crit: single rising ping.
            case CRIT -> attacker.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9F, 1.6F);
            // Body hit: crisp, short tick — the classic hit-marker.
            case HEALTH -> attacker.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.85F, 1.9F);
        }
    }

    /** Satisfying two-layer confirmation played to the attacker the moment they eliminate a target. */
    public void playElimination(Player attacker) {
        if (attacker == null || !attacker.isOnline()) {
            return;
        }
        var at = attacker.getLocation();
        attacker.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8F, 1.2F);
        attacker.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.8F);
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            headshotShooters.remove(playerId);
        }
    }

    /** Drops stale headshot flags (called opportunistically; the TTL guards correctness regardless). */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        headshotShooters.values().removeIf(expiry -> expiry < now);
    }
}
