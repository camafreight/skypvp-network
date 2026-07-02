package network.skypvp.extraction.integration;

import network.skypvp.extraction.config.HitscanSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class HitscanVisualRenderer {

    private final HitscanSettings settings;

    HitscanVisualRenderer(HitscanSettings settings) {
        this.settings = settings;
    }

    void render(World world, HitscanVisualJob job) {
        if (world == null || job == null) {
            return;
        }
        renderTracer(world, job);
        renderImpact(world, job);
    }

    private void renderTracer(World world, HitscanVisualJob job) {
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
