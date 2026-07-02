package network.skypvp.extraction.integration;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/** Immutable hitscan cosmetic payload; tracer points may be filled asynchronously before queueing. */
final class HitscanVisualJob {

    private final UUID worldId;
    private final int chunkX;
    private final int chunkZ;
    private final double startX;
    private final double startY;
    private final double startZ;
    private final double endX;
    private final double endY;
    private final double endZ;
    private final double dirX;
    private final double dirY;
    private final double dirZ;
    private final double travelled;
    private final double maxRange;
    private final double[] tracerX;
    private final double[] tracerY;
    private final double[] tracerZ;

    private HitscanVisualJob(
            UUID worldId,
            int chunkX,
            int chunkZ,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            double dirX,
            double dirY,
            double dirZ,
            double travelled,
            double maxRange,
            double[] tracerX,
            double[] tracerY,
            double[] tracerZ
    ) {
        this.worldId = worldId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.dirX = dirX;
        this.dirY = dirY;
        this.dirZ = dirZ;
        this.travelled = travelled;
        this.maxRange = maxRange;
        this.tracerX = tracerX;
        this.tracerY = tracerY;
        this.tracerZ = tracerZ;
    }

    static HitscanVisualJob create(
            Location start,
            Location end,
            Vector direction,
            double travelled,
            double maxRange,
            double tracerSpacing,
            int maxTracerPoints
    ) {
        TracerPoints points = computeTracerPoints(start, end, tracerSpacing, maxTracerPoints);
        return new HitscanVisualJob(
                start.getWorld().getUID(),
                start.getBlockX() >> 4,
                start.getBlockZ() >> 4,
                start.getX(),
                start.getY(),
                start.getZ(),
                end.getX(),
                end.getY(),
                end.getZ(),
                direction.getX(),
                direction.getY(),
                direction.getZ(),
                travelled,
                maxRange,
                points.x(),
                points.y(),
                points.z()
        );
    }

    UUID worldId() {
        return worldId;
    }

    int chunkX() {
        return chunkX;
    }

    int chunkZ() {
        return chunkZ;
    }

    Location start(org.bukkit.World world) {
        return new Location(world, startX, startY, startZ);
    }

    Location end(org.bukkit.World world) {
        return new Location(world, endX, endY, endZ);
    }

    Vector direction() {
        return new Vector(dirX, dirY, dirZ);
    }

    double travelled() {
        return travelled;
    }

    double maxRange() {
        return maxRange;
    }

    double[] tracerX() {
        return tracerX;
    }

    double[] tracerY() {
        return tracerY;
    }

    double[] tracerZ() {
        return tracerZ;
    }

    private static TracerPoints computeTracerPoints(
            Location start,
            Location end,
            double spacing,
            int maxPoints
    ) {
        double distance = start.distance(end);
        if (distance < 0.05 || spacing <= 0.0 || maxPoints <= 0) {
            return new TracerPoints(new double[0], new double[0], new double[0]);
        }

        Vector step = end.toVector().subtract(start.toVector()).normalize().multiply(spacing);
        int capacity = Math.min(maxPoints, (int) Math.ceil(distance / spacing));
        double[] xs = new double[capacity];
        double[] ys = new double[capacity];
        double[] zs = new double[capacity];

        double x = start.getX();
        double y = start.getY();
        double z = start.getZ();
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            x += step.getX();
            y += step.getY();
            z += step.getZ();
            if (start.distance(new Location(start.getWorld(), x, y, z)) >= distance) {
                break;
            }
            xs[count] = x;
            ys[count] = y;
            zs[count] = z;
            count++;
        }
        if (count == capacity) {
            return new TracerPoints(xs, ys, zs);
        }
        double[] trimmedX = new double[count];
        double[] trimmedY = new double[count];
        double[] trimmedZ = new double[count];
        System.arraycopy(xs, 0, trimmedX, 0, count);
        System.arraycopy(ys, 0, trimmedY, 0, count);
        System.arraycopy(zs, 0, trimmedZ, 0, count);
        return new TracerPoints(trimmedX, trimmedY, trimmedZ);
    }

    private record TracerPoints(double[] x, double[] y, double[] z) {
    }
}
