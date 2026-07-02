package network.skypvp.extraction.gameplay;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachBossService {

    private final ServerPlatform scheduler;
    private final Logger logger;
    private final MythicMobsBridge mythicMobsBridge;
    private final Set<String> spawnedBossIds = new HashSet<>();

    public BreachBossService(JavaPlugin plugin, ServerPlatform scheduler, MythicMobsBridge mythicMobsBridge) {
        Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mythicMobsBridge = Objects.requireNonNull(mythicMobsBridge, "mythicMobsBridge");
        this.logger = plugin.getLogger();
    }

    public void reset() {
        this.mythicMobsBridge.despawnTracked();
        this.spawnedBossIds.clear();
    }

    public void tick(World world, BreachMapMeta mapMeta, int elapsedSeconds) {
        if (world == null || mapMeta == null || !mythicMobsBridge.isAvailable()) {
            return;
        }
        for (BreachMapMeta.BossSpawn spawn : mapMeta.bossSpawns()) {
            if (spawnedBossIds.contains(spawn.id())) {
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
            spawnedBossIds.add(spawn.id());
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
