package network.skypvp.extraction.gameplay;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Timed boss spawns from map meta. Spawn ids are keyed by WORLD + boss id: concurrent
 * instances of the same map share boss ids, and a world-blind set meant the second breach
 * never spawned its boss ("already spawned" by the first instance).
 */
public final class BreachBossService {

    private final ServerPlatform scheduler;
    private final Logger logger;
    private final MythicMobsBridge mythicMobsBridge;
    private final Set<String> spawnedBossKeys = ConcurrentHashMap.newKeySet();

    public BreachBossService(JavaPlugin plugin, ServerPlatform scheduler, MythicMobsBridge mythicMobsBridge) {
        Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mythicMobsBridge = Objects.requireNonNull(mythicMobsBridge, "mythicMobsBridge");
        this.logger = plugin.getLogger();
    }

    /** Full reset (plugin shutdown): despawns everything tracked across all instances. */
    public void reset() {
        this.mythicMobsBridge.despawnTracked();
        this.spawnedBossKeys.clear();
    }

    /**
     * Per-instance reset: clears ONLY this world's boss bookkeeping and despawns only its
     * tracked mobs. The old global {@code reset()} despawned every sibling breach's mobs
     * whenever one instance recycled.
     */
    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        String prefix = world.getUID() + ":";
        this.spawnedBossKeys.removeIf(key -> key.startsWith(prefix));
        this.mythicMobsBridge.despawnTrackedInWorld(world);
    }

    public void tick(World world, BreachMapMeta mapMeta, int elapsedSeconds) {
        if (world == null || mapMeta == null || !mythicMobsBridge.isAvailable()) {
            return;
        }
        for (BreachMapMeta.BossSpawn spawn : mapMeta.bossSpawns()) {
            String bossKey = world.getUID() + ":" + spawn.id();
            if (spawnedBossKeys.contains(bossKey)) {
                continue;
            }
            if (spawn.mythicMobId().isBlank()) {
                continue;
            }
            if (elapsedSeconds < spawn.delaySeconds()) {
                continue;
            }
            Location location = new Location(world, spawn.x(), spawn.y(), spawn.z());
            this.scheduler.runAtLocation(location, () -> this.spawnBossAt(location, spawn));
            spawnedBossKeys.add(bossKey);
        }
    }

    private void spawnBossAt(Location location, BreachMapMeta.BossSpawn spawn) {
        if (mythicMobsBridge.spawnMob(spawn.mythicMobId(), location, spawn.level()).isEmpty()) {
            this.logger.warning("[Breach] Failed to spawn boss '" + spawn.id()
                    + "' (MythicMob '" + spawn.mythicMobId() + "') at "
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
            return;
        }
        this.logger.info("[Breach] Spawned boss '" + spawn.id() + "' (MythicMob '"
                + spawn.mythicMobId() + "') at "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
    }
}
