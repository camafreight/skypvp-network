package network.skypvp.extraction.integration;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import network.skypvp.extraction.ai.raider.RaiderCombatStyle;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * WeaponMechanics combat helpers for MythicMobs gunner AI (shoot, equip, reload).
 *
 * <p>AI shots intentionally do <strong>not</strong> aim at eyes with a tiny world-space jitter.
 * That path produced terminator headshots because {@code WeaponMechanicsAPI.shoot(..., Location)}
 * builds a perfect direction and the Vector shoot overload skips weapon {@code Spread}.
 * Instead we aim at the torso and apply true angular cone error before shooting.
 */
public final class WeaponMechanicsCombatBridge {

    private static final double BODY_AIM_HEIGHT_FRACTION = 0.55D;
    private static final double HEADSHOT_AIM_CHANCE = 0.14D;
    private static final double SHOTGUN_EXTRA_CONE_DEGREES = 10.0D;

    /** Hostile AI bolts render red so players read incoming fire instantly; player color stays config-driven. */
    private static final org.bukkit.Color AI_TRACER_COLOR = org.bukkit.Color.fromRGB(0xFF2E2E);
    /** Per-shooter shot counter for tracer thinning: every 2nd AI shot renders a bolt (tracer rounds). */
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.concurrent.atomic.AtomicInteger> aiShotCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Logger logger;
    private final boolean present;
    private final WeaponMechanicsBridge weaponBridge;
    private final Method shootAtLocationMethod;
    private final Method shootWithDirectionMethod;
    private final Method tryReloadMethod;

    /** Own-gunfire wiring (tracer visuals + threaded damage); bound after construction. */
    private ServerPlatform platform;
    private HitscanSettings hitscanSettings;
    private HitscanLaserBeamRenderer tracer;

    /**
     * Enables the self-contained AI gunfire path: server-side hitscan with our own damage,
     * laser tracer, and shot sound. AI shots never call {@code WeaponMechanicsAPI.shoot} —
     * WM 4.3.1 throws internally on Folia (broken lateinit visible at PlayerJoin), which
     * made every AI shot a silent no-op.
     */
    public void bindOwnGunfire(ServerPlatform platform, HitscanSettings settings) {
        this.platform = platform;
        this.hitscanSettings = settings;
        if (platform != null && settings != null) {
            this.tracer = new HitscanLaserBeamRenderer(platform, settings);
        }
    }

    public WeaponMechanicsCombatBridge(JavaPlugin plugin, WeaponMechanicsBridge weaponBridge) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.weaponBridge = Objects.requireNonNull(weaponBridge, "weaponBridge");
        Method shootLocation = null;
        Method shootDirection = null;
        Method reload = null;
        boolean available = weaponBridge.isAvailable();
        if (available) {
            try {
                Class<?> api = Class.forName("me.deecaad.weaponmechanics.WeaponMechanicsAPI");
                shootLocation = api.getMethod("shoot", LivingEntity.class, String.class, Location.class);
                try {
                    shootDirection = api.getMethod("shoot", LivingEntity.class, String.class, Vector.class);
                } catch (NoSuchMethodException ignored) {
                    // Fall back to Location aim-point shots.
                }
                reload = api.getMethod("tryReload", LivingEntity.class);
            } catch (ReflectiveOperationException exception) {
                available = false;
                logger.warning("[Breach] WeaponMechanics combat bridge unavailable: " + exception.getMessage());
            }
        }
        this.present = available;
        this.shootAtLocationMethod = shootLocation;
        this.shootWithDirectionMethod = shootDirection;
        this.tryReloadMethod = reload;
    }

    public boolean isAvailable() {
        return present;
    }

    public boolean equipWeapon(LivingEntity entity, String weaponTitle) {
        if (!present || entity == null || weaponTitle == null || weaponTitle.isBlank()) {
            return false;
        }
        return weaponBridge.generateWeapon(weaponTitle.trim(), 1).map(item -> {
            EntityEquipment equipment = entity.getEquipment();
            if (equipment == null) {
                return false;
            }
            equipment.setItemInMainHand(item);
            equipment.setItemInMainHandDropChance(0.0F);
            return true;
        }).orElse(false);
    }

    public boolean shootAt(LivingEntity shooter, String weaponTitle, LivingEntity target, double spreadDegrees) {
        return shootAt(shooter, weaponTitle, target, spreadDegrees, 0, null);
    }

    /**
     * @param shotIndexInBurst 0-based index within the current burst (drives climb / walk patterns)
     * @param style optional doctrine for spray shape; null = uniform cone
     */
    public boolean shootAt(
            LivingEntity shooter,
            String weaponTitle,
            LivingEntity target,
            double spreadDegrees,
            int shotIndexInBurst,
            RaiderCombatStyle style
    ) {
        if (!present || shooter == null || target == null || weaponTitle == null || weaponTitle.isBlank()) {
            return false;
        }
        Location muzzle;
        Location aimPoint;
        try {
            // Match sight LOS height so canSeeTarget clearance and the shot ray agree.
            // Bukkit eye height on disguised MythicMobs can sit inside a block and instantly
            // "hit" the floor/ceiling before the target ray is considered.
            muzzle = network.skypvp.extraction.ai.raider.RaiderSightSupport.eyeLocation(shooter);
            aimPoint = bodyAimPoint(target);
            if (ThreadLocalRandom.current().nextDouble() < HEADSHOT_AIM_CHANCE) {
                aimPoint = network.skypvp.extraction.ai.raider.RaiderSightSupport.eyeLocation(target);
            }
        } catch (IllegalStateException foreignRegion) {
            return false;
        }
        Vector direction = aimPoint.toVector().subtract(muzzle.toVector());
        if (direction.lengthSquared() < 1.0E-6D) {
            return false;
        }
        direction.normalize();
        // Nudge the ray origin forward so a muzzle buried in a slab/head-hitbox cannot
        // register a zero-length floor/wall hit that discards every shot.
        muzzle = muzzle.clone().add(direction.clone().multiply(0.20D));
        double cone = Math.max(0.0D, spreadDegrees);
        String title = weaponTitle.trim();
        if (isAiShotgun(title)) {
            cone += SHOTGUN_EXTRA_CONE_DEGREES;
        }
        Vector noisy = applyPatternSpread(direction, cone, Math.max(0, shotIndexInBurst), style);
        return fireOwnHitscan(shooter, title, target, muzzle, noisy);
    }

    private static final double OWN_GUNFIRE_RANGE = 64.0D;

    /**
     * Self-contained AI shot: raytrace blocks, ray-test the intended target's hitbox,
     * render the laser tracer, play a shot crack, and apply damage on the victim's thread.
     * Deterministic and Folia-safe — no WeaponMechanics involvement.
     */
    private boolean fireOwnHitscan(
            LivingEntity shooter,
            String weaponTitle,
            LivingEntity target,
            Location muzzle,
            Vector direction
    ) {
        World world = shooter.getWorld();
        RayTraceResult blockHit = world.rayTraceBlocks(
                muzzle, direction, OWN_GUNFIRE_RANGE, FluidCollisionMode.NEVER, true);
        double blockDistance = blockHit != null
                ? blockHit.getHitPosition().distance(muzzle.toVector())
                : OWN_GUNFIRE_RANGE;
        // Extremely short block hits are almost always local geometry (feet slab, door lip).
        // Prefer the entity ray when the intended target is still along the shot.
        if (blockHit != null && blockDistance < 0.85D) {
            blockDistance = OWN_GUNFIRE_RANGE;
        }

        RayTraceResult targetHit;
        try {
            // Use an explicit player/body AABB — never rely on getBoundingBox() alone in case
            // passenger nametag TextDisplays or other attachments inflate the reported box upward.
            targetHit = bodyHitbox(target)
                    .expand(0.12D)
                    .rayTrace(muzzle.toVector(), direction, OWN_GUNFIRE_RANGE);
        } catch (IllegalStateException foreignRegion) {
            return false;
        }
        double targetDistance = targetHit != null
                ? targetHit.getHitPosition().distance(muzzle.toVector())
                : Double.MAX_VALUE;
        boolean hit = targetHit != null && targetDistance <= blockDistance + 0.01D;

        double endDistance = Math.max(0.5D, hit ? targetDistance : blockDistance);

        // Each rendered bolt costs an entity spawn + metadata + remove packet per viewer.
        // During squad sprays that entity churn was a real chunk of the client packet load,
        // so AI fire renders tracer-style: every 2nd round draws a bolt, sound plays on all.
        if (aiShotCounters.size() > 256) {
            // Counters only carry shot parity; dropping them on overflow just re-syncs
            // tracer phase, so a wholesale clear beats tracking mob lifecycles here.
            aiShotCounters.clear();
        }
        int shotIndex = aiShotCounters
                .computeIfAbsent(shooter.getUniqueId(), ignored -> new java.util.concurrent.atomic.AtomicInteger())
                .getAndIncrement();
        playGunshot(world, muzzle, weaponTitle, shotIndex);
        if (tracer != null && hitscanSettings != null && (shotIndex & 1) == 0) {
            // MISS rays used to render their full block-ray (up to 64 blocks) — long red
            // rods slowly climbing into the sky. Visually, a shot fired AT the player dies
            // just past them; the damage ray below still uses the true distances.
            double pastTargetCap = target.getLocation().toVector().distance(muzzle.toVector()) + 6.0D;
            double visualDistance = hit ? endDistance : Math.min(endDistance, pastTargetCap);
            Location visualEnd = muzzle.clone().add(direction.clone().multiply(visualDistance));
            tracer.renderAiTracer(
                    world,
                    muzzle,
                    visualEnd,
                    AI_TRACER_COLOR,
                    hitscanSettings.laserThickness(),
                    hitscanSettings.laserLifetimeTicks()
            );
        }
        if (hit) {
            applyGunDamage(shooter, target, weaponTitle, endDistance);
        }
        return true;
    }

    private void applyGunDamage(LivingEntity shooter, LivingEntity target, String weaponTitle, double distance) {
        double damage = baseGunDamage(weaponTitle) * damageFalloff(weaponTitle, distance);
        if (damage <= 0.05D) {
            return;
        }
        Runnable apply = () -> {
            if (!target.isValid() || target.isDead()) {
                return;
            }
            try {
                target.damage(damage, shooter);
            } catch (IllegalStateException foreignRegion) {
                // Attacker straddles a region boundary: land the hit unattributed.
                target.damage(damage);
            }
        };
        // Always land damage on the victim's owning region — never mutate a foreign-region entity
        // from the shooter's AI/region tick (Folia freezes / IllegalStateException).
        if (platform != null) {
            platform.runOwned(target, apply);
        } else {
            apply.run();
        }
    }

    /** Pre-armor damage per AI weapon class; balance lives here, not in WM configs. */
    private static double baseGunDamage(String weaponTitle) {
        String title = weaponTitle.toLowerCase(Locale.ROOT);
        if (title.contains("origin") || title.contains("shotgun") || title.contains("dp12")) {
            return 9.0D;
        }
        if (title.contains("ak")) {
            return 5.5D;
        }
        if (title.contains("uzi")) {
            return 3.2D;
        }
        if (title.contains("mg34") || title.contains("mg-34")) {
            return 6.0D;
        }
        if (title.contains("magnum") || title.contains("357")) {
            return 5.0D;
        }
        return 4.5D;
    }

    /** Shotguns fall off hard past ~6 blocks; rifles keep partial damage to max range. */
    private static double damageFalloff(String weaponTitle, double distance) {
        boolean shotgun = isAiShotgun(weaponTitle);
        double start = shotgun ? 6.0D : 16.0D;
        double end = shotgun ? 20.0D : 56.0D;
        double floor = shotgun ? 0.10D : 0.45D;
        if (distance <= start) {
            return 1.0D;
        }
        if (distance >= end) {
            return floor;
        }
        double progress = (distance - start) / (end - start);
        return 1.0D - (1.0D - floor) * progress;
    }

    private static void playGunshot(World world, Location muzzle, String weaponTitle, int shotIndex) {
        float pitch = isAiShotgun(weaponTitle) ? 0.85F : 1.6F;
        world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.1F, pitch);
        // The metallic texture layer reads fine at a third of the rate; halves the per-spray
        // sound packet count without changing how the gun feels.
        if (shotIndex % 3 == 0) {
            world.playSound(muzzle, Sound.ENTITY_IRON_GOLEM_HURT, 0.3F, 1.9F);
        }
    }

    public boolean shootAtLocation(LivingEntity shooter, String weaponTitle, Location targetLocation) {
        if (!present || shooter == null || targetLocation == null || weaponTitle == null || weaponTitle.isBlank()) {
            return false;
        }
        try {
            Object result = shootAtLocationMethod.invoke(null, shooter, weaponTitle.trim(), targetLocation);
            return result == null || (result instanceof Boolean bool && bool);
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics shoot failed for " + weaponTitle, exception);
            return false;
        }
    }

    private boolean shootWithDirection(LivingEntity shooter, String weaponTitle, Vector direction) {
        try {
            Object result = shootWithDirectionMethod.invoke(null, shooter, weaponTitle, direction);
            return result == null || (result instanceof Boolean bool && bool);
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics directional shoot failed for " + weaponTitle, exception);
            return false;
        }
    }

    public boolean tryReload(LivingEntity entity) {
        if (!present || entity == null || tryReloadMethod == null) {
            return false;
        }
        try {
            Object result = tryReloadMethod.invoke(null, entity);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.FINE, "[Breach] WeaponMechanics reload failed", exception);
            return false;
        }
    }

    /**
     * Performs a melee hit with swing animation and direct entity damage.
     * WeaponMechanics {@code shoot} is for guns — using it on knives returns success without damaging players.
     */
    public boolean meleeStrike(LivingEntity attacker, LivingEntity target, String weaponTitle, double damage) {
        if (attacker == null || target == null || target.isDead() || !target.isValid()) {
            return false;
        }
        if (damage <= 0.0D) {
            return false;
        }
        Runnable apply = () -> {
            if (!target.isValid() || target.isDead()) {
                return;
            }
            // Vanilla i-frames silently ignore damage <= the previous hit for the first half
            // of the invulnerability window (10 ticks). Interleaved squad gunfire / a second
            // knife rusher keeps breach victims inside that window, and the melee cooldown
            // (10 ticks) plus cross-region scheduling jitter lands strikes right on its edge —
            // so the knife swung and played its sound but roughly half the hits never applied.
            // Trim the window before striking so AI melee connects deterministically.
            if (target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2) {
                target.setNoDamageTicks(target.getMaximumNoDamageTicks() / 2);
            }
            try {
                target.damage(damage, attacker);
            } catch (IllegalStateException foreignRegion) {
                // Folia: attributed damage reads the attacker across a region boundary.
                target.damage(damage);
            }
        };
        if (platform != null) {
            platform.runOwned(target, apply);
        } else {
            apply.run();
        }
        return true;
    }

    public void playMeleeSwing(LivingEntity attacker) {
        LibsDisguisesBridge.playSwingAnimation(attacker);
    }

    public ItemStack heldWeapon(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        EntityEquipment equipment = entity.getEquipment();
        return equipment == null ? null : equipment.getItemInMainHand();
    }

    private static Location bodyAimPoint(LivingEntity target) {
        Location base = target.getLocation().clone();
        double height = Math.max(0.9D, target.getHeight() * BODY_AIM_HEIGHT_FRACTION);
        return base.add(0.0D, height, 0.0D);
    }

    /** Feet-origin body box only — excludes head-passenger nametag displays from the hit volume. */
    private static org.bukkit.util.BoundingBox bodyHitbox(LivingEntity target) {
        Location feet = target.getLocation();
        double halfWidth = Math.max(0.3D, target.getWidth() * 0.5D);
        double height = Math.max(0.9D, target.getHeight());
        return new org.bukkit.util.BoundingBox(
                feet.getX() - halfWidth,
                feet.getY(),
                feet.getZ() - halfWidth,
                feet.getX() + halfWidth,
                feet.getY() + height,
                feet.getZ() + halfWidth
        );
    }

    private static boolean isAiShotgun(String weaponTitle) {
        String title = weaponTitle.toLowerCase();
        return title.contains("origin_12") || title.contains("shotgun") || title.contains("dp12");
    }

    /**
     * Applies yaw/pitch cone error in degrees around {@code direction} (already normalized).
     */
    static Vector applyAngularSpread(Vector direction, double spreadDegrees) {
        return applyPatternSpread(direction, spreadDegrees, 0, null);
    }

    /**
     * Doctrine-aware spray: rifles climb vertically, SMGs walk horizontally, pistols open on the
     * second tap, breachers stay wide and flat.
     */
    static Vector applyPatternSpread(
            Vector direction,
            double spreadDegrees,
            int shotIndexInBurst,
            RaiderCombatStyle style
    ) {
        if (direction == null || direction.lengthSquared() < 1.0E-8D) {
            return new Vector(0, 0, 1);
        }
        Vector dir = direction.clone().normalize();
        if (spreadDegrees <= 0.0D && style != RaiderCombatStyle.RIFLE && style != RaiderCombatStyle.CLOSE_ASSAULT) {
            return dir;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double yaw = Math.atan2(-dir.getX(), dir.getZ());
        double pitch = Math.asin(Math.max(-1.0D, Math.min(1.0D, dir.getY())));

        double yawJitter;
        double pitchJitter;
        RaiderCombatStyle doctrine = style == null ? RaiderCombatStyle.RIFLE : style;
        switch (doctrine) {
            case RIFLE -> {
                // First round tight; then controlled vertical climb with light drift.
                // Positive pitch raises the muzzle (math pitch = asin(y)); the old negative
                // sign walked every burst into the floor and made mid-spray a guaranteed miss.
                double open = shotIndexInBurst == 0 ? 0.55D : 1.0D;
                yawJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * 0.30D * open;
                pitchJitter = shotIndexInBurst * (spreadDegrees * 0.28D)
                        + (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * 0.20D * open;
            }
            case CLOSE_ASSAULT -> {
                // SMG / pistol: tight opener, then horizontal walk + mild climb.
                double open = shotIndexInBurst == 0 ? 0.55D : (shotIndexInBurst >= 3 ? 1.25D : 1.0D);
                yawJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * open;
                pitchJitter = shotIndexInBurst * (spreadDegrees * 0.14D)
                        + (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * 0.40D;
            }
            case BREACHER -> {
                // Twin-slug pulse: wide flat cone; second pellet kicks slightly wider.
                double open = shotIndexInBurst >= 1 ? 1.20D : 1.0D;
                yawJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * open;
                pitchJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees * 0.30D;
            }
            default -> {
                yawJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees;
                pitchJitter = (rng.nextDouble() * 2.0D - 1.0D) * spreadDegrees;
            }
        }

        yaw += Math.toRadians(yawJitter);
        pitch += Math.toRadians(pitchJitter);
        pitch = Math.max(-Math.PI / 2.0D + 1.0E-3D, Math.min(Math.PI / 2.0D - 1.0E-3D, pitch));
        double cosPitch = Math.cos(pitch);
        return new Vector(
                -Math.sin(yaw) * cosPitch,
                Math.sin(pitch),
                Math.cos(yaw) * cosPitch
        );
    }
}
