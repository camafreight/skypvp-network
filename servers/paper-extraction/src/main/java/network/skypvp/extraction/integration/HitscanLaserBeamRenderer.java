package network.skypvp.extraction.integration;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Spawns a short-lived {@link ItemDisplay} laser bolt that travels along a server-computed ray.
 * Pack model is a unit-length cylinder on local +Z; this class scales Z to bolt length and
 * rotates so +Z matches the shot direction. The entity itself teleports from muzzle to impact
 * with {@link ItemDisplay#setTeleportDuration(int)} so the client interpolates travel.
 *
 * <p>Travel timeout follows WeaponMechanics' projectile lifespan equation:
 * {@code lifespanSeconds = travelDistance / velocityBlocksPerSecond}
 * (equivalently max timeout uses max weapon range). Bolts are removed on arrival (impact)
 * or when that timeout elapses.
 *
 * <p>Muzzle (weapon tip) cannot be read from client first-person item matrices. We approximate
 * tip in eye-space: {@code eye + look*forward + right*right + up*up}.
 */
final class HitscanLaserBeamRenderer {

    private static final int MAX_ACTIVE_BEAMS = 28;
    private static final AtomicInteger ACTIVE_BEAMS = new AtomicInteger();
    private static final Vector3f LOCAL_FORWARD = new Vector3f(0.0F, 0.0F, 1.0F);
    private static final Vector WORLD_UP = new Vector(0.0, 1.0, 0.0);
    private static final double TICKS_PER_SECOND = 20.0;

    private final ServerPlatform scheduler;
    private final HitscanSettings settings;

    HitscanLaserBeamRenderer(ServerPlatform scheduler, HitscanSettings settings) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    void render(World world, HitscanVisualJob job) {
        if (world == null || job == null) {
            return;
        }
        renderDirect(
                world,
                job.start(world),
                job.end(world),
                settings.laserGlowColor(),
                settings.laserThickness(),
                settings.laserLifetimeTicks(),
                false
        );
    }

    void renderDirect(
            World world,
            Location start,
            Location end,
            Color tint,
            double thicknessBlocks,
            long lingerTicks,
            boolean skipViewerCulling
    ) {
        renderCore(world, start, end, tint, thicknessBlocks, lingerTicks, skipViewerCulling, true, 1.0);
    }

    /** AI bolts fly this much faster than player bolts — miss rays otherwise crawl across the sky. */
    private static final double AI_TRACER_VELOCITY_MULTIPLIER = 2.5;

    /**
     * AI tracer: {@code muzzle} is authoritative (the shooter's gun; the first-person
     * viewmodel offsets from {@link #estimateMuzzle} are meaningless for a mob) and the bolt
     * flies at {@link #AI_TRACER_VELOCITY_MULTIPLIER}x speed so spray fire reads as gunfire
     * instead of slow rods hanging in the air.
     */
    void renderAiTracer(
            World world,
            Location muzzle,
            Location end,
            Color tint,
            double thicknessBlocks,
            long lingerTicks
    ) {
        renderCore(world, muzzle, end, tint, thicknessBlocks, lingerTicks, false, false, AI_TRACER_VELOCITY_MULTIPLIER);
    }

    private void renderCore(
            World world,
            Location start,
            Location end,
            Color tint,
            double thicknessBlocks,
            long lingerTicks,
            boolean skipViewerCulling,
            boolean applyViewmodelMuzzle,
            double velocityMultiplier
    ) {
        if (world == null || start == null || end == null) {
            return;
        }
        if (ACTIVE_BEAMS.get() >= MAX_ACTIVE_BEAMS) {
            return;
        }

        Vector delta = end.toVector().subtract(start.toVector());
        double length = delta.length();
        if (length < 0.15) {
            return;
        }

        Vector direction = delta.clone().multiply(1.0 / length);
        Location muzzle = applyViewmodelMuzzle ? estimateMuzzle(start, direction) : start.clone();
        Vector tipToEnd = end.toVector().subtract(muzzle.toVector());
        double remaining = tipToEnd.length();
        if (remaining < 0.2) {
            return;
        }
        Vector shotDir = tipToEnd.multiply(1.0 / remaining);
        double travelDistance = Math.min(remaining, settings.laserMaxLengthBlocks());
        if (travelDistance < 0.2) {
            return;
        }

        double boltLength = Math.max(0.35, Math.min(settings.laserBoltLengthBlocks(), travelDistance));
        double velocity = Math.max(1.0, settings.laserVelocityBlocksPerSecond() * velocityMultiplier);
        // Lifespan = distance / velocity (seconds) → ticks. Cap with max-range equation.
        double maxRange = Math.max(settings.maxRangeBlocks(), settings.laserMaxLengthBlocks());
        int maxLifetimeTicks = Math.max(1, (int) Math.ceil((maxRange / velocity) * TICKS_PER_SECOND));
        int minTravelTicks = applyViewmodelMuzzle ? 5 : 2;
        int travelTicks = Math.max(minTravelTicks, (int) Math.ceil((travelDistance / velocity) * TICKS_PER_SECOND));
        travelTicks = Math.min(travelTicks, maxLifetimeTicks);
        long linger = Math.max(0L, lingerTicks);
        long removeAt = travelTicks + linger;

        // Entity origin sits at the bolt's rear; tip extends +boltLength along shotDir.
        Location boltStart = muzzle.clone();
        Location boltArrival = muzzle.clone().add(shotDir.clone().multiply(Math.max(0.0, travelDistance - boltLength)));
        Location tipAtEnd = muzzle.clone().add(shotDir.clone().multiply(travelDistance));
        Location midForCull = boltStart.clone().add(shotDir.clone().multiply(travelDistance * 0.5));
        if (!skipViewerCulling && !hasNearbyViewer(world, midForCull, boltStart, tipAtEnd)) {
            return;
        }

        float thickness = (float) Math.max(0.08, thicknessBlocks);
        Quaternionf facing = rotationAlong(shotDir);
        ItemStack laserItem = createLaserItem(settings.laserItemModel(), tint);
        int startChunkX = boltStart.getBlockX() >> 4;
        int startChunkZ = boltStart.getBlockZ() >> 4;
        int endChunkX = tipAtEnd.getBlockX() >> 4;
        int endChunkZ = tipAtEnd.getBlockZ() >> 4;

        if (ACTIVE_BEAMS.incrementAndGet() > MAX_ACTIVE_BEAMS) {
            ACTIVE_BEAMS.decrementAndGet();
            return;
        }

        ItemDisplay display;
        try {
            display = world.spawn(boltStart, ItemDisplay.class, entity -> {
                entity.setItemStack(laserItem);
                entity.setItemDisplayTransform(ItemDisplayTransform.FIXED);
                entity.setBillboard(Display.Billboard.FIXED);
                // Emissive override: item displays ignore the model's light_emission, so
                // without this the bolt is lit by AMBIENT light — it faded to nothing at
                // night or in shadow, which read as "no glow at all".
                entity.setBrightness(new Display.Brightness(15, 15));
                // Vanilla spectral outline; tint must match the bolt dye or it reads as a white bloom.
                // Config: hitscan.laser.glowing + hitscan.laser.color (glow-color fallback).
                boolean glowing = settings.laserGlowing();
                entity.setGlowing(glowing);
                if (glowing && tint != null) {
                    entity.setGlowColorOverride(tint);
                }
                entity.setPersistent(false);
                entity.setInterpolationDuration(0);
                entity.setTeleportDuration(0);
                entity.setViewRange((float) Math.max(16.0, settings.laserViewRangeBlocks()) / 64.0F);
                entity.setRotation(0.0F, 0.0F);
                entity.setTransformation(stretchedAlong(facing, thickness, (float) boltLength));
            });
        } catch (RuntimeException ex) {
            ACTIVE_BEAMS.decrementAndGet();
            throw ex;
        }

        // Kick travel on the next tick so the spawn packet is out before teleportDuration applies.
        int travel = travelTicks;
        Location arrival = boltArrival;
        scheduler.runAtChunkLater(world, startChunkX, startChunkZ, () -> {
            if (!display.isValid() || display.isDead()) {
                return;
            }
            display.setTeleportDuration(travel);
            display.teleportAsync(arrival);
        }, 1L);

        scheduler.runAtChunkLater(world, endChunkX, endChunkZ, () -> {
            try {
                if (display.isValid() && !display.isDead()) {
                    display.remove();
                }
            } finally {
                ACTIVE_BEAMS.decrementAndGet();
            }
        }, 1L + removeAt);
    }

    /**
     * Approximates the held-weapon tip in world space.
     *
     * <p>True tip lives in the client FP item matrix (model tip + display translation/scale), which
     * the server never sees. Eye-space basis is the stable substitute: tune
     * {@code hitscan.laser.muzzle-*-blocks} until the bolt leaves the visible muzzle.
     */
    Location estimateMuzzle(Location eye, Vector lookDirection) {
        Vector forward = lookDirection.clone();
        if (forward.lengthSquared() < 1.0E-8) {
            return eye.clone();
        }
        forward.normalize();

        Vector right = forward.clone().crossProduct(WORLD_UP);
        if (right.lengthSquared() < 1.0E-6) {
            // Looking straight up/down — pick an arbitrary lateral axis.
            right = forward.clone().crossProduct(new Vector(1.0, 0.0, 0.0));
            if (right.lengthSquared() < 1.0E-6) {
                right = new Vector(0.0, 0.0, 1.0);
            }
        }
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();

        return eye.clone()
                .add(forward.multiply(settings.laserMuzzleForwardBlocks()))
                .add(right.multiply(settings.laserMuzzleRightBlocks()))
                .add(up.multiply(settings.laserMuzzleUpBlocks()));
    }

    /**
     * Transformation for a bolt of {@code lengthBlocks} whose back edge sits on the entity
     * origin (the muzzle) and whose tip extends along the rotated +Z.
     */
    private static Transformation stretchedAlong(Quaternionf facing, float thickness, float lengthBlocks) {
        Vector3f alongBeam = new Quaternionf(facing).transform(new Vector3f(0.0F, 0.0F, lengthBlocks * 0.5F));
        return new Transformation(
                alongBeam,
                new Quaternionf(facing),
                new Vector3f(thickness, thickness, lengthBlocks),
                new Quaternionf()
        );
    }

    /**
     * Builds a quaternion that maps local model +Z onto the hitscan direction.
     */
    private static Quaternionf rotationAlong(Vector direction) {
        Vector3f target = new Vector3f(
                (float) direction.getX(),
                (float) direction.getY(),
                (float) direction.getZ()
        );
        if (target.lengthSquared() < 1.0E-8F) {
            return new Quaternionf();
        }
        target.normalize();
        // rotationTo fails when vectors are opposite; handle that explicitly.
        float dot = LOCAL_FORWARD.dot(target);
        if (dot < -0.9999F) {
            return new Quaternionf().rotationXYZ((float) Math.PI, 0.0F, 0.0F);
        }
        return new Quaternionf().rotationTo(LOCAL_FORWARD, target);
    }

    private boolean hasNearbyViewer(World world, Location midpoint, Location start, Location end) {
        double viewRange = settings.laserViewRangeBlocks();
        double viewRangeSq = viewRange * viewRange;
        for (org.bukkit.entity.Player player : world.getNearbyPlayers(midpoint, viewRange)) {
            if (!player.isOnline()) {
                continue;
            }
            double nearest = Math.min(
                    player.getLocation().distanceSquared(start),
                    player.getLocation().distanceSquared(end)
            );
            if (nearest <= viewRangeSq) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack createLaserItem(String itemModelKey, Color tint) {
        Color resolved = tint != null ? tint : Color.fromRGB(0x40F0FF);
        ItemStack item = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        item.editMeta(meta -> {
            NamespacedKey key = parseItemModel(itemModelKey);
            if (key != null) {
                meta.setItemModel(key);
            }
            if (meta instanceof LeatherArmorMeta leather) {
                leather.setColor(resolved);
            }
        });
        return item;
    }

    private static NamespacedKey parseItemModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return new NamespacedKey("skypvp", "laser_beam");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized);
        return key != null ? key : new NamespacedKey("skypvp", "laser_beam");
    }
}
