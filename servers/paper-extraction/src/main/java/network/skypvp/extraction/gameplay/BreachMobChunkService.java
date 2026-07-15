package network.skypvp.extraction.gameplay;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Force-loads breach mob spawn areas and keeps chunks loaded around live gunners so MythicMobs, disguises,
 * and passenger nametags are not lost when raiders leave the area or spectators reconnect.
 */
public final class BreachMobChunkService {

    private static final int SPAWN_CHUNK_PADDING = 2;
    private static final int ENTITY_CHUNK_PADDING = 1;

    private final ServerPlatform scheduler;
    private final Logger logger;
    private final Map<UUID, Set<Long>> spawnChunksByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> entityChunksByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> loadedChunksByWorld = new ConcurrentHashMap<>();

    public BreachMobChunkService(JavaPlugin plugin, ServerPlatform scheduler) {
        Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = plugin.getLogger();
    }

    public void retainMatchChunks(World world, BreachMapMeta mapMeta) {
        if (world == null || mapMeta == null) {
            return;
        }
        Set<Long> keys = new HashSet<>();
        for (BreachMapMeta.MobSpawn spawn : mapMeta.mobSpawns()) {
            addChunkRadius(keys, spawn.x(), spawn.z(), SPAWN_CHUNK_PADDING);
        }
        for (BreachMapMeta.BossSpawn spawn : mapMeta.bossSpawns()) {
            addChunkRadius(keys, spawn.x(), spawn.z(), SPAWN_CHUNK_PADDING);
        }
        spawnChunksByWorld.put(world.getUID(), keys);
        reconcile(world);
        logger.info("[Breach] Tracking " + keys.size() + " spawn chunk(s) for mob retention in '"
                + world.getName() + "'.");
    }

    public void retainAroundEntity(LivingEntity entity) {
        if (entity == null || entity.getWorld() == null) {
            return;
        }
        Location location = entity.getLocation();
        Set<Long> keys = entityChunksByWorld.computeIfAbsent(entity.getWorld().getUID(), ignored -> new HashSet<>());
        addChunkRadius(keys, location.getX(), location.getZ(), ENTITY_CHUNK_PADDING);
        reconcile(entity.getWorld());
    }

    public void retainAroundLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Set<Long> keys = entityChunksByWorld.computeIfAbsent(location.getWorld().getUID(), ignored -> new HashSet<>());
        addChunkRadius(keys, location.getX(), location.getZ(), ENTITY_CHUNK_PADDING);
        reconcile(location.getWorld());
    }

    public void updateTrackedEntities(World world, Collection<LivingEntity> entities) {
        if (world == null) {
            return;
        }
        Set<Long> keys = new HashSet<>();
        if (entities != null) {
            for (LivingEntity entity : entities) {
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    continue;
                }
                if (!world.equals(entity.getWorld())) {
                    continue;
                }
                Location location = entity.getLocation();
                addChunkRadius(keys, location.getX(), location.getZ(), ENTITY_CHUNK_PADDING);
            }
        }
        entityChunksByWorld.put(world.getUID(), keys);
        reconcile(world);
    }

    /** Updates entity chunk retention from precomputed block coordinates (safe from global tick threads). */
    public void updateTrackedPositions(World world, Collection<PositionAnchor> anchors) {
        if (world == null) {
            return;
        }
        Set<Long> keys = new HashSet<>();
        if (anchors != null) {
            for (PositionAnchor anchor : anchors) {
                if (anchor == null) {
                    continue;
                }
                addChunkRadius(keys, anchor.x(), anchor.z(), ENTITY_CHUNK_PADDING);
            }
        }
        entityChunksByWorld.put(world.getUID(), keys);
        reconcile(world);
    }

    public void release(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        spawnChunksByWorld.remove(worldId);
        entityChunksByWorld.remove(worldId);
        Set<Long> loaded = loadedChunksByWorld.remove(worldId);
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        for (long packed : loaded) {
            int chunkX = unpackX(packed);
            int chunkZ = unpackZ(packed);
            setChunkForceLoaded(world, chunkX, chunkZ, false);
        }
        logger.fine("[Breach] Released force-loaded chunks for '" + world.getName() + "'.");
    }

    private void reconcile(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        Set<Long> desired = new HashSet<>();
        desired.addAll(spawnChunksByWorld.getOrDefault(worldId, Set.of()));
        desired.addAll(entityChunksByWorld.getOrDefault(worldId, Set.of()));
        Set<Long> current = loadedChunksByWorld.getOrDefault(worldId, Set.of());

        for (long packed : desired) {
            if (current.contains(packed)) {
                continue;
            }
            int chunkX = unpackX(packed);
            int chunkZ = unpackZ(packed);
            setChunkForceLoaded(world, chunkX, chunkZ, true);
            scheduler.runAtChunk(world, chunkX, chunkZ, () -> {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (!chunk.isLoaded()) {
                    chunk.load(true);
                }
            });
        }
        for (long packed : current) {
            if (desired.contains(packed)) {
                continue;
            }
            int chunkX = unpackX(packed);
            int chunkZ = unpackZ(packed);
            setChunkForceLoaded(world, chunkX, chunkZ, false);
        }
        loadedChunksByWorld.put(worldId, desired);
    }

    /** Folia requires force-load mutations on the global region thread. */
    private void setChunkForceLoaded(World world, int chunkX, int chunkZ, boolean loaded) {
        scheduler.runGlobal(() -> world.setChunkForceLoaded(chunkX, chunkZ, loaded));
    }

    public record PositionAnchor(double x, double z) {
    }

    private static void addChunkRadius(Set<Long> keys, double x, double z, int padding) {
        int centerX = Location.locToBlock(x) >> 4;
        int centerZ = Location.locToBlock(z) >> 4;
        for (int dx = -padding; dx <= padding; dx++) {
            for (int dz = -padding; dz <= padding; dz++) {
                keys.add(pack(centerX + dx, centerZ + dz));
            }
        }
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
