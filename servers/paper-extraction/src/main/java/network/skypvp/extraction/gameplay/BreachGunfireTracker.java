package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;

public final class BreachGunfireTracker {

    private static final long DEFAULT_WINDOW_MS = 15_000L;

    private final ConcurrentHashMap<UUID, List<GunfirePing>> pingsByWorld = new ConcurrentHashMap<>();

    public void record(Location location) {
        record(location, null);
    }

    public void record(Location location, UUID shooterId) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        GunfirePing ping = new GunfirePing(location.getX(), location.getY(), location.getZ(), now, shooterId);
        pingsByWorld.compute(location.getWorld().getUID(), (worldId, pings) -> {
            List<GunfirePing> bucket = pings == null ? new ArrayList<>() : pings;
            bucket.add(ping);
            prune(bucket, now, DEFAULT_WINDOW_MS);
            return bucket;
        });
    }

    public int countRecent(Location center, double radiusBlocks, long windowMs) {
        if (center == null || center.getWorld() == null) {
            return 0;
        }
        World world = center.getWorld();
        List<GunfirePing> pings = pingsByWorld.get(world.getUID());
        if (pings == null || pings.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(1L, windowMs);
        double radiusSquared = radiusBlocks * radiusBlocks;
        int count = 0;
        Iterator<GunfirePing> iterator = pings.iterator();
        while (iterator.hasNext()) {
            GunfirePing ping = iterator.next();
            if (ping.timestampMillis() < cutoff) {
                iterator.remove();
                continue;
            }
            double dx = ping.x() - center.getX();
            double dy = ping.y() - center.getY();
            double dz = ping.z() - center.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public Optional<GunfirePing> nearestRecent(Location center, double radiusBlocks, long windowMs) {
        if (center == null || center.getWorld() == null) {
            return Optional.empty();
        }
        List<GunfirePing> pings = pingsByWorld.get(center.getWorld().getUID());
        if (pings == null || pings.isEmpty()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(1L, windowMs);
        double radiusSquared = radiusBlocks * radiusBlocks;
        GunfirePing nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        Iterator<GunfirePing> iterator = pings.iterator();
        while (iterator.hasNext()) {
            GunfirePing ping = iterator.next();
            if (ping.timestampMillis() < cutoff) {
                iterator.remove();
                continue;
            }
            double dx = ping.x() - center.getX();
            double dy = ping.y() - center.getY();
            double dz = ping.z() - center.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > radiusSquared) {
                continue;
            }
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearest = ping;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        pingsByWorld.remove(world.getUID());
    }

    private static void prune(List<GunfirePing> pings, long now, long windowMs) {
        long cutoff = now - windowMs;
        pings.removeIf(ping -> ping.timestampMillis() < cutoff);
    }

    public record GunfirePing(double x, double y, double z, long timestampMillis, UUID shooterId) {

        public Location location(World world) {
            return new Location(world, x, y, z);
        }
    }
}
