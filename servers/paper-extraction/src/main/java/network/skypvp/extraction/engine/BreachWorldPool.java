package network.skypvp.extraction.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.world.BreachWorldManager;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachWorldPool {

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private final BreachWorldManager worldManager;
    private final BreachWorldGuardBridge worldGuardBridge;
    private final BreachGameplayCoordinator gameplayCoordinator;
    private final PaperCorePlugin core;
    private final Logger logger;
    private final ConcurrentMap<String, BreachInstance> instances = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<BreachInstance>> pendingCreations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BreachInstance> playerIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BreachInstance> worldIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BreachInstance> joinableByMapId = new ConcurrentHashMap<>();

    public BreachWorldPool(
            JavaPlugin plugin,
            ServerPlatform scheduler,
            BreachConfigService configService,
            BreachWorldManager worldManager,
            BreachWorldGuardBridge worldGuardBridge,
            BreachGameplayCoordinator gameplayCoordinator,
            PaperCorePlugin core
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
        this.worldGuardBridge = Objects.requireNonNull(worldGuardBridge, "worldGuardBridge");
        this.gameplayCoordinator = gameplayCoordinator;
        this.core = Objects.requireNonNull(core, "core");
        this.logger = plugin.getLogger();
    }

    public int activeCount() {
        return instances.size();
    }

    public int capacityRemaining() {
        return Math.max(0, configService.maxBreachesPerPod() - instances.size());
    }

    public List<BreachInstance> instancesSnapshot() {
        return List.copyOf(instances.values());
    }

    public Optional<BreachInstance> findByPlayer(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(playerIndex.get(playerId));
    }

    /** True when the player is in the extraction hub and not already seated in a breach instance. */
    public boolean isLobbyDeployable(org.bukkit.entity.Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        return !player.getWorld().getName().startsWith("breach_")
                && findByPlayer(player.getUniqueId()).isEmpty();
    }

    /**
     * Finds an active breach instance where the given party already has live raiders. When {@code mapId} is set the
     * instance map must match; otherwise any map hosting the party is returned.
     */
    public Optional<BreachInstance> findActiveInstanceForParty(UUID partyId, String mapId) {
        if (partyId == null) {
            return Optional.empty();
        }
        String normalizedMap = mapId == null || mapId.isBlank() ? null : mapId.trim().toLowerCase();
        BreachInstance fallback = null;
        for (BreachInstance instance : instances.values()) {
            if (!instance.hasActivePartyMember(partyId)) {
                continue;
            }
            if (normalizedMap != null) {
                if (instance.mapMeta().mapId().equalsIgnoreCase(normalizedMap)) {
                    return Optional.of(instance);
                }
                continue;
            }
            if (fallback == null) {
                fallback = instance;
            }
        }
        return Optional.ofNullable(fallback);
    }

    public Optional<BreachInstance> findByWorld(World world) {
        if (world == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(worldIndex.get(world.getUID()));
    }

    void trackParticipant(UUID playerId, BreachInstance instance) {
        if (playerId != null && instance != null) {
            playerIndex.put(playerId, instance);
        }
    }

    /** Removes the index entry only while it still points at {@code instance}. */
    void untrackParticipant(UUID playerId, BreachInstance instance) {
        if (playerId != null && instance != null) {
            playerIndex.remove(playerId, instance);
        }
    }

    void trackWorld(BreachInstance instance) {
        if (instance != null && instance.world() != null) {
            worldIndex.put(instance.world().getUID(), instance);
        }
    }

    void untrackWorld(BreachInstance instance) {
        if (instance != null && instance.world() != null) {
            worldIndex.remove(instance.world().getUID());
        }
    }

    void refreshJoinableIndex(BreachInstance instance) {
        if (instance == null) {
            return;
        }
        String mapId = instance.mapMeta().mapId().toLowerCase();
        if (instance.canAcceptPlayers()) {
            joinableByMapId.put(mapId, instance);
            return;
        }
        joinableByMapId.remove(mapId, instance);
    }

    void untrackInstance(BreachInstance instance) {
        if (instance == null) {
            return;
        }
        refreshJoinableIndex(instance);
        untrackWorld(instance);
        for (UUID playerId : instance.participantIdsSnapshot()) {
            playerIndex.remove(playerId, instance);
        }
    }

    public void prewarmStandbyWorlds() {
        List<String> templates = new ArrayList<>();
        for (BreachConfigService.BreachMapEntry entry : configService.enabledMapEntries()) {
            if (entry.template() != null && !entry.template().isBlank()) {
                templates.add(entry.template());
            }
        }
        worldManager.prewarmStandbyWorlds(templates);
    }

    public Optional<BreachInstance> findJoinableInstance(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return Optional.empty();
        }
        String normalized = mapId.toLowerCase();
        BreachInstance cached = joinableByMapId.get(normalized);
        if (cached != null && cached.canAcceptPlayers()) {
            return Optional.of(cached);
        }
        if (cached != null) {
            joinableByMapId.remove(normalized, cached);
        }
        for (BreachInstance instance : instances.values()) {
            if (!instance.mapMeta().mapId().equalsIgnoreCase(mapId)) {
                continue;
            }
            if (instance.canAcceptPlayers()) {
                joinableByMapId.put(normalized, instance);
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    public CompletableFuture<BreachInstance> acquireInstance(String mapId) {
        Optional<BreachInstance> joinable = findJoinableInstance(mapId);
        if (joinable.isPresent()) {
            return CompletableFuture.completedFuture(joinable.get());
        }
        return acquireFreshInstance(mapId);
    }

    /**
     * Always provisions a brand-new instance instead of handing back an existing joinable one. The party-join
     * retry loop uses this so a party that couldn't atomically reserve enough room in partially-filled instances
     * is guaranteed a fresh raid to try, rather than being handed the same full instance again (which would spin
     * forever). Concurrent requests for the same map still coalesce onto one pending world creation.
     */
    public CompletableFuture<BreachInstance> acquireFreshInstance(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("mapId is required"));
        }
        if (instances.size() >= configService.maxBreachesPerPod()) {
            return CompletableFuture.failedFuture(new IllegalStateException("All breach instances are in use."));
        }

        Optional<BreachConfigService.BreachMapEntry> entry = configService.mapEntry(mapId);
        if (entry.isEmpty() || !entry.get().enabled()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown or disabled map: " + mapId));
        }

        Optional<BreachMapMeta> meta = configService.mapMeta(mapId);
        if (meta.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Map meta unavailable: " + mapId));
        }

        String pendingKey = mapId.toLowerCase();
        CompletableFuture<BreachInstance> existing = pendingCreations.get(pendingKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<BreachInstance> future = new CompletableFuture<>();
        pendingCreations.put(pendingKey, future);

        String instanceId = UUID.randomUUID().toString();
        String templateId = entry.get().template();
        Optional<BreachWorldManager.StandbyWorld> warmWorld = worldManager.takeWarmWorld(templateId);
        if (warmWorld.isPresent()) {
            BreachWorldManager.StandbyWorld standby = warmWorld.get();
            // World setup touches the instance world's blocks (extract-zone beacons, map activation),
            // which on Folia must run on that world's owning region thread, not the global thread.
            scheduler.runAtLocation(instanceAnchor(standby.world(), meta.get()), () -> {
                completeInstanceCreation(future, pendingKey, instanceId, meta.get(), templateId, standby.worldName(), standby.world());
                worldManager.replenishWarmWorld(templateId);
            });
            return future;
        }

        String worldName = worldManager.createInstanceWorldName(templateId);
        worldManager.createInstanceWorld(templateId, worldName).whenComplete((world, error) -> {
            if (error != null) {
                pendingCreations.remove(pendingKey);
                future.completeExceptionally(error);
                return;
            }
            scheduler.runAtLocation(instanceAnchor(world, meta.get()), () -> {
                completeInstanceCreation(future, pendingKey, instanceId, meta.get(), templateId, worldName, world);
                worldManager.replenishWarmWorld(templateId);
            });
        });

        return future;
    }

    /**
     * Picks a location inside the instance world to schedule map setup on. On Folia this resolves the
     * region thread that owns the map area; coordinates come from the map meta (config) so we never read
     * world data off-thread. The whole map (spawns, extract zones, bosses, chests) sits in one region.
     */
    private Location instanceAnchor(World world, BreachMapMeta meta) {
        if (!meta.spawnPoints().isEmpty()) {
            BreachMapMeta.SpawnPoint spawn = meta.spawnPoints().get(0);
            return new Location(world, spawn.x(), spawn.y(), spawn.z());
        }
        if (!meta.extractZones().isEmpty()) {
            BreachMapMeta.ExtractZone zone = meta.extractZones().get(0);
            return new Location(world, zone.centerX(), zone.centerY(), zone.centerZ());
        }
        return new Location(world, 0, 64, 0);
    }

    public void releaseIfIdle(BreachInstance instance) {
        // Persistent breach maps run until their lifecycle timer expires; never tear down when empty.
    }

    public void shutdown() {
        List<BreachInstance> snapshot = new ArrayList<>(instances.values());
        instances.clear();
        playerIndex.clear();
        worldIndex.clear();
        joinableByMapId.clear();
        for (BreachInstance instance : snapshot) {
            worldManager.destroyWorld(instance.worldName());
        }
        worldManager.shutdown();
    }

    private void completeInstanceCreation(
            CompletableFuture<BreachInstance> future,
            String pendingKey,
            String instanceId,
            BreachMapMeta meta,
            String templateId,
            String worldName,
            World world
    ) {
        pendingCreations.remove(pendingKey);
        try {
            completeInstanceCreationInternal(future, instanceId, meta, templateId, worldName, world);
        } catch (Throwable ex) {
            logger.log(java.util.logging.Level.SEVERE,
                    "[Breach] completeInstanceCreation failed for world '" + worldName + "': " + ex.getMessage(), ex);
            future.completeExceptionally(ex);
        }
    }

    private void completeInstanceCreationInternal(
            CompletableFuture<BreachInstance> future,
            String instanceId,
            BreachMapMeta meta,
            String templateId,
            String worldName,
            World world
    ) {
        BreachInstance instance = new BreachInstance(
                plugin,
                scheduler,
                configService,
                worldManager,
                worldGuardBridge,
                gameplayCoordinator,
                core,
                this,
                instanceId,
                meta,
                templateId,
                worldName,
                world
        );
        instance.setRecycleListener(() -> logger.fine("[Breach] Instance '" + instanceId + "' recycled."));
        instances.put(instanceId, instance);
        trackWorld(instance);
        refreshJoinableIndex(instance);
        worldGuardBridge.applyRegions(world, meta.worldGuardRegions());
        if (gameplayCoordinator != null) {
            gameplayCoordinator.onWorldReady(world, meta);
        }
        instance.activateMap();
        if (gameplayCoordinator != null) {
            // Warm BEFORE handing the instance to matchmaking: loot force-activation and
            // initial mob spawns must land before any player is admitted. Standby instances
            // always warmed; fresh /breach play instances skipped this entirely and dropped
            // players into empty maps. The warming flag blocks canAcceptPlayers meanwhile
            // (the instance must already be registered so its warmed mobs bind as agents).
            instance.markWarming(true);
            gameplayCoordinator.warmStandbyInstance(world, meta, templateId, () -> {
                instance.markWarming(false);
                refreshJoinableIndex(instance);
                logger.info("[Breach] Instance '" + instanceId + "' warmed and open for players.");
                future.complete(instance);
            });
            return;
        }
        future.complete(instance);
    }
}
