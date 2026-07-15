package network.skypvp.extraction.integration;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

public final class MythicMobsBridge {

    private final Logger logger;
    private final boolean present;
    /** Concurrent: spawns register from Folia region threads, resets run on the global thread. */
    private final java.util.Set<UUID> spawnedEntityIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MythicMobsBridge(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.present = plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
        if (present) {
            logger.info("[Breach] MythicMobs bridge enabled.");
        } else {
            logger.warning("[Breach] MythicMobs not present; timed boss spawns will be skipped.");
        }
    }

    public boolean isAvailable() {
        return present;
    }

    public Optional<UUID> spawnMob(String mythicMobId, Location location, double level) {
        if (!present || mythicMobId == null || mythicMobId.isBlank() || location == null) {
            return Optional.empty();
        }

        try {
            ActiveMob activeMob = MythicBukkit.inst().getMobManager().spawnMob(mythicMobId.trim(), location, level);
            if (activeMob == null) {
                logger.warning("[Breach] MythicMobs returned null for mob '" + mythicMobId.trim() + "'");
                return Optional.empty();
            }
            UUID entityId = activeMob.getUniqueId();
            spawnedEntityIds.add(entityId);
            return Optional.of(entityId);
        } catch (RuntimeException ex) {
            logger.warning("[Breach] Failed to spawn MythicMob '" + mythicMobId.trim() + "': " + ex.getMessage());
            return Optional.empty();
        }
    }

    public void despawnTracked() {
        if (!present) {
            spawnedEntityIds.clear();
            return;
        }

        var mobManager = MythicBukkit.inst().getMobManager();
        for (UUID entityId : new ArrayList<>(spawnedEntityIds)) {
            try {
                mobManager.getActiveMob(entityId).ifPresent(ActiveMob::remove);
            } catch (IllegalStateException foreignRegion) {
                // Folia: entity owned by another region; its world unload will clean it up.
            }
        }
        spawnedEntityIds.clear();
    }

    /**
     * Despawns and untracks only the mobs living in {@code world}. Instance resets MUST use
     * this instead of {@link #despawnTracked()} — the global variant wiped every sibling
     * breach's mobs whenever one instance recycled.
     */
    public void despawnTrackedInWorld(org.bukkit.World world) {
        if (world == null) {
            return;
        }
        for (UUID entityId : new ArrayList<>(spawnedEntityIds)) {
            org.bukkit.entity.Entity entity = org.bukkit.Bukkit.getEntity(entityId);
            if (entity == null) {
                // Gone entirely; drop the stale id.
                spawnedEntityIds.remove(entityId);
                continue;
            }
            if (!world.equals(entity.getWorld())) {
                continue;
            }
            spawnedEntityIds.remove(entityId);
            if (present) {
                try {
                    MythicBukkit.inst().getMobManager().getActiveMob(entityId).ifPresent(ActiveMob::remove);
                } catch (IllegalStateException foreignRegion) {
                    // Folia: owned by another region thread; the imminent world unload removes it.
                }
            }
        }
    }
}
