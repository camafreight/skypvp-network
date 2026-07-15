package network.skypvp.extraction.gameplay;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Recurring MythicMob patrol spawns configured per map ({@code mobSpawns} in meta.json).
 *
 * <p>All bookkeeping is keyed by WORLD + spawn id: concurrent breach instances of the same
 * map share spawn-point ids and coordinates, and world-blind keys made instances fight over
 * the same alive-sets — the second breach spawned nothing while each world's prune pass
 * evicted the other's entries, ping-ponging between starved and over-spawned. Collections
 * are concurrent because spawn callbacks run on Folia region threads while the tick runs on
 * the global heartbeat.
 */
public final class BreachMobSpawnService {

    private final ServerPlatform scheduler;
    private final Logger logger;
    private final MythicMobsBridge mythicMobsBridge;
    private final Map<String, Set<UUID>> aliveBySpawnKey = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSpawnAtMillis = new ConcurrentHashMap<>();

    public BreachMobSpawnService(JavaPlugin plugin, ServerPlatform scheduler, MythicMobsBridge mythicMobsBridge) {
        Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mythicMobsBridge = Objects.requireNonNull(mythicMobsBridge, "mythicMobsBridge");
        this.logger = plugin.getLogger();
    }

    /** Full clear (plugin shutdown). Per-instance resets must use {@link #clearWorld(World)}. */
    public void reset() {
        this.aliveBySpawnKey.clear();
        this.lastSpawnAtMillis.clear();
    }

    /** Drops one breach world's bookkeeping without touching sibling instances. */
    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        String prefix = world.getUID() + ":";
        this.aliveBySpawnKey.keySet().removeIf(key -> key.startsWith(prefix));
        this.lastSpawnAtMillis.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Spawns initial mobs immediately for standby breach warm-up (ignores delay/interval gates). */
    public void warmInitialSpawns(World world, BreachMapMeta mapMeta) {
        if (world == null || mapMeta == null || !mythicMobsBridge.isAvailable() || mapMeta.mobSpawns().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int scheduled = 0;
        for (BreachMapMeta.MobSpawn spawn : mapMeta.mobSpawns()) {
            if (spawn.mythicMobId().isBlank()) {
                continue;
            }
            String spawnKey = spawnKey(world, spawn.id());
            Set<UUID> alive = aliveBySpawnKey.computeIfAbsent(spawnKey, ignored -> ConcurrentHashMap.newKeySet());
            int needed = Math.max(0, spawn.maxAlive() - alive.size());
            if (needed == 0) {
                continue;
            }
            Location location = new Location(world, spawn.x(), spawn.y(), spawn.z());
            for (int index = 0; index < needed; index++) {
                this.scheduler.runAtLocation(location, () -> this.spawnOne(spawn, spawnKey, location, alive, now));
                scheduled++;
            }
        }
        if (scheduled > 0) {
            this.logger.info("[Breach] Warmed " + scheduled + " initial mob spawn(s) in '" + world.getName() + "'.");
        }
    }

    /**
     * Folia-safe mob-threat probe for deploy spawn scoring: counts tracked live mobs whose configured
     * spawn point sits within {@code radiusBlocks} of the given position. Reads only static meta
     * coordinates and the concurrent alive-sets — no entity or world access — so it may be called from
     * any region thread. Patrol mobs hold near their spawn point, so spawn-point distance is a good
     * proxy; entries may briefly overcount until the next prune pass, which only errs toward caution.
     */
    public int countAliveNear(World world, BreachMapMeta mapMeta, double x, double y, double z, double radiusBlocks) {
        if (world == null || mapMeta == null || mapMeta.mobSpawns().isEmpty()) {
            return 0;
        }
        double radiusSquared = radiusBlocks * radiusBlocks;
        int count = 0;
        for (BreachMapMeta.MobSpawn spawn : mapMeta.mobSpawns()) {
            double dx = spawn.x() - x;
            double dy = spawn.y() - y;
            double dz = spawn.z() - z;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                continue;
            }
            Set<UUID> alive = aliveBySpawnKey.get(spawnKey(world, spawn.id()));
            if (alive != null) {
                count += alive.size();
            }
        }
        return count;
    }

    public void tick(World world, BreachMapMeta mapMeta, int elapsedSeconds) {
        if (world == null || mapMeta == null || !mythicMobsBridge.isAvailable() || mapMeta.mobSpawns().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (BreachMapMeta.MobSpawn spawn : mapMeta.mobSpawns()) {
            if (spawn.mythicMobId().isBlank()) {
                continue;
            }
            if (elapsedSeconds < spawn.delaySeconds()) {
                continue;
            }
            String spawnKey = spawnKey(world, spawn.id());
            pruneDead(world, spawnKey);
            Set<UUID> alive = aliveBySpawnKey.computeIfAbsent(spawnKey, ignored -> ConcurrentHashMap.newKeySet());
            if (alive.size() >= spawn.maxAlive()) {
                continue;
            }
            long lastSpawn = lastSpawnAtMillis.getOrDefault(spawnKey, 0L);
            long intervalMillis = spawn.intervalSeconds() * 1000L;
            if (lastSpawn > 0L && now - lastSpawn < intervalMillis) {
                continue;
            }
            Location location = new Location(world, spawn.x(), spawn.y(), spawn.z());
            this.scheduler.runAtLocation(location, () -> this.spawnOne(spawn, spawnKey, location, alive, now));
        }
    }

    private void spawnOne(BreachMapMeta.MobSpawn spawn, String spawnKey, Location location, Set<UUID> alive, long now) {
        if (alive.size() >= spawn.maxAlive()) {
            return;
        }
        mythicMobsBridge.spawnMob(spawn.mythicMobId(), location, spawn.level()).ifPresentOrElse(
                entityId -> {
                    alive.add(entityId);
                    lastSpawnAtMillis.put(spawnKey, now);
                    logger.fine("[Breach] Spawned mob '" + spawn.mythicMobId() + "' for point '" + spawn.id() + "'");
                },
                () -> logger.warning("[Breach] Failed to spawn mob point '" + spawn.id()
                        + "' (MythicMob '" + spawn.mythicMobId() + "')")
        );
    }

    private void pruneDead(World world, String spawnKey) {
        Set<UUID> alive = aliveBySpawnKey.get(spawnKey);
        if (alive == null || alive.isEmpty()) {
            return;
        }
        Iterator<UUID> iterator = alive.iterator();
        while (iterator.hasNext()) {
            UUID entityId = iterator.next();
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null || !entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                iterator.remove();
            }
        }
    }

    private static String spawnKey(World world, String spawnId) {
        return world.getUID() + ":" + spawnId;
    }
}
