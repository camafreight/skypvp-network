package network.skypvp.extraction.integration;

import java.util.concurrent.ThreadLocalRandom;
import network.skypvp.extraction.config.HitscanSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class HitscanVisualRenderer {

    /** How far (blocks) off the bullet line a player can be and still hear the round crack past them. */
    private static final double PASSBY_RADIUS = 3.0;
    /** Players within this distance of the muzzle are the shooter/point-blank and are skipped. */
    private static final double PASSBY_SHOOTER_SKIP = 2.5;
    /** WM pack event; falls back to arrow shoot if the client pack is missing. */
    private static final String PASSBY_SOUND_KEY = "minecraft:fx.whiz";
    private static final Sound PASSBY_SOUND_FALLBACK = Sound.ENTITY_ARROW_SHOOT;
    private static final float PASSBY_VOLUME = 0.85F;

    private final HitscanSettings settings;
    private final HitscanLaserBeamRenderer laserRenderer;

    HitscanVisualRenderer(HitscanSettings settings, HitscanLaserBeamRenderer laserRenderer) {
        this.settings = settings;
        this.laserRenderer = laserRenderer;
    }

    void render(World world, HitscanVisualJob job) {
        if (world == null || job == null) {
            return;
        }
        renderTracer(world, job);
        renderImpact(world, job);
        renderPassby(world, job);
    }

    /**
     * Plays a directional "whizz" to any player the round flies close past (but not the shooter). WM's own projectile
     * whistle never fires because the hitscan path removes the fake projectile before it ticks, so this restores it.
     */
    private void renderPassby(World world, HitscanVisualJob job) {
        Location start = job.start(world);
        Location end = job.end(world);
        Vector s = start.toVector();
        Vector se = end.toVector().subtract(s);
        double segLenSq = se.lengthSquared();
        if (segLenSq < 4.0) {
            return;
        }
        Location mid = new Location(
                world,
                (start.getX() + end.getX()) * 0.5,
                (start.getY() + end.getY()) * 0.5,
                (start.getZ() + end.getZ()) * 0.5
        );
        double searchRadius = Math.sqrt(segLenSq) * 0.5 + PASSBY_RADIUS + 1.0;
        double radiusSq = PASSBY_RADIUS * PASSBY_RADIUS;
        double shooterSkipSq = PASSBY_SHOOTER_SKIP * PASSBY_SHOOTER_SKIP;
        for (Player player : world.getNearbyPlayers(mid, searchRadius)) {
            if (!player.isOnline()) {
                continue;
            }
            Vector p = player.getEyeLocation().toVector();
            if (p.distanceSquared(s) <= shooterSkipSq) {
                continue;
            }
            double t = p.clone().subtract(s).dot(se) / segLenSq;
            if (t <= 0.0 || t >= 1.0) {
                continue;
            }
            Vector closest = s.clone().add(se.clone().multiply(t));
            if (closest.distanceSquared(p) > radiusSq) {
                continue;
            }
            Location soundAt = new Location(world, closest.getX(), closest.getY(), closest.getZ());
            float pitch = 0.95F + ThreadLocalRandom.current().nextFloat() * 0.25F;
            try {
                player.playSound(soundAt, PASSBY_SOUND_KEY, SoundCategory.PLAYERS, PASSBY_VOLUME, pitch);
            } catch (IllegalArgumentException ignored) {
                player.playSound(soundAt, PASSBY_SOUND_FALLBACK, SoundCategory.PLAYERS, PASSBY_VOLUME, pitch);
            }
        }
    }

    private void renderTracer(World world, HitscanVisualJob job) {
        if (settings.usesLaserTracer()) {
            if (laserRenderer != null) {
                laserRenderer.render(world, job);
            }
            return;
        }

        double[] xs = job.tracerX();
        if (xs.length == 0) {
            return;
        }
        double[] ys = job.tracerY();
        double[] zs = job.tracerZ();
        Particle particle = settings.tracerParticle();
        double viewRangeSq = settings.tracerViewRangeBlocks() * settings.tracerViewRangeBlocks();
        Location start = job.start(world);
        Location end = job.end(world);
        Location midpoint = lerp(start, end, 0.5);

        if (!hasNearbyViewer(world, midpoint, viewRangeSq, start, end)) {
            return;
        }

        for (int i = 0; i < xs.length; i++) {
            world.spawnParticle(particle, xs[i], ys[i], zs[i], 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void renderImpact(World world, HitscanVisualJob job) {
        if (!settings.impactEffectsEnabled() || job.travelled() < 0.05) {
            return;
        }

        Location end = job.end(world);
        Vector direction = job.direction();
        if (direction.lengthSquared() < 1.0E-8) {
            return;
        }

        Vector normal = direction.clone().normalize();
        Location probe = end.clone().subtract(normal.clone().multiply(0.05));
        Block block = probe.getBlock();
        double viewRange = settings.impactViewRangeBlocks();
        double viewRangeSq = viewRange * viewRange;

        if (isBlockImpact(block)) {
            Location surface = offsetToBlockFace(probe, blockFaceTowardShooter(probe, job.start(world)));
            if (!hasNearbyViewer(world, surface, viewRangeSq, job.start(world), end)) {
                return;
            }
            if (settings.impactBlockParticleCount() > 0) {
                world.spawnParticle(
                        Particle.BLOCK,
                        surface,
                        settings.impactBlockParticleCount(),
                        0.08,
                        0.08,
                        0.08,
                        0.01,
                        block.getBlockData()
                );
            }
            if (settings.impactBlockChipOverlay()) {
                Location blockLoc = block.getLocation();
                for (Player viewer : world.getNearbyPlayers(blockLoc, viewRange)) {
                    viewer.sendBlockDamage(blockLoc, settings.impactBlockChipDamage());
                }
            }
            return;
        }

        if (job.travelled() + 0.25 >= job.maxRange()) {
            return;
        }

        LivingEntity hitEntity = findHitEntity(world, end);
        if (hitEntity == null || settings.impactEntityParticleCount() <= 0) {
            return;
        }
        Location impact = hitEntity.getLocation().add(0.0, hitEntity.getHeight() * 0.5, 0.0);
        if (!hasNearbyViewer(world, impact, viewRangeSq, job.start(world), end)) {
            return;
        }
        world.spawnParticle(
                settings.impactEntityParticle(),
                impact,
                settings.impactEntityParticleCount(),
                0.12,
                0.12,
                0.12,
                0.02
        );
    }

    private static boolean isBlockImpact(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return !type.isAir() && type.isSolid();
    }

    private static LivingEntity findHitEntity(World world, Location around) {
        for (Entity entity : world.getNearbyEntities(around, 0.55, 0.55, 0.55)) {
            if (entity instanceof LivingEntity living && !living.isDead()) {
                return living;
            }
        }
        return null;
    }

    private static BlockFace blockFaceTowardShooter(Location surface, Location shooter) {
        if (shooter == null) {
            return BlockFace.UP;
        }
        Vector delta = shooter.toVector().subtract(surface.toVector());
        return faceFromVector(delta);
    }

    private static BlockFace faceFromVector(Vector vector) {
        double absX = Math.abs(vector.getX());
        double absY = Math.abs(vector.getY());
        double absZ = Math.abs(vector.getZ());
        if (absY >= absX && absY >= absZ) {
            return vector.getY() >= 0.0 ? BlockFace.UP : BlockFace.DOWN;
        }
        if (absX >= absZ) {
            return vector.getX() >= 0.0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return vector.getZ() >= 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static Location offsetToBlockFace(Location point, BlockFace face) {
        if (face == null || point == null) {
            return point;
        }
        Vector normal = face.getDirection();
        return point.clone().add(normal.getX() * 0.05, normal.getY() * 0.05, normal.getZ() * 0.05);
    }

    private static Location lerp(Location start, Location end, double t) {
        return new Location(
                start.getWorld(),
                start.getX() + (end.getX() - start.getX()) * t,
                start.getY() + (end.getY() - start.getY()) * t,
                start.getZ() + (end.getZ() - start.getZ()) * t
        );
    }

    private static boolean hasNearbyViewer(
            World world,
            Location anchor,
            double viewRangeSq,
            Location start,
            Location end
    ) {
        for (Player player : world.getNearbyPlayers(anchor, Math.sqrt(viewRangeSq))) {
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
}
