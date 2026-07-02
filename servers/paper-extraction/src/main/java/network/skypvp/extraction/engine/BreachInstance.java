package network.skypvp.extraction.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachSpawnSafety;
import network.skypvp.extraction.gameplay.ExtractZonePlayerView;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.world.BreachWorldManager;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachInstance {

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private final BreachWorldManager worldManager;
    private final BreachWorldGuardBridge worldGuardBridge;
    private final BreachGameplayCoordinator gameplayCoordinator;
    private final PaperCorePlugin core;
    private final BreachWorldPool worldPool;
    private final String instanceId;
    private final BreachMapMeta mapMeta;
    private final String mapTemplateId;
    private final String worldName;
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Set<UUID> extractedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> eliminatedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Location> hubReturnLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> breachAnchorLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> joinCountdowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> playerPartyIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> partyMemberIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> partySpawnLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sessionKills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sessionDeaths = new ConcurrentHashMap<>();
    private volatile BreachState state = BreachState.WAITING;
    private volatile World world;
    private volatile int countdownSeconds;
    private volatile int remainingSeconds;
    private volatile int elapsedActiveSeconds;
    private volatile int cachedActiveParticipantCount;
    private volatile List<Player> cachedExtractZoneViewers = List.of();
    private volatile boolean extractZoneViewersDirty = true;
    private volatile int extractVisualCycle;
    private volatile long stateChangedAtMillis = System.currentTimeMillis();
    private Runnable onRecycleComplete;

    public BreachInstance(
            JavaPlugin plugin,
            ServerPlatform scheduler,
            BreachConfigService configService,
            BreachWorldManager worldManager,
            BreachWorldGuardBridge worldGuardBridge,
            BreachGameplayCoordinator gameplayCoordinator,
            PaperCorePlugin core,
            BreachWorldPool worldPool,
            String instanceId,
            BreachMapMeta mapMeta,
            String mapTemplateId,
            String worldName,
            World world
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
        this.worldGuardBridge = Objects.requireNonNull(worldGuardBridge, "worldGuardBridge");
        this.gameplayCoordinator = gameplayCoordinator;
        this.core = core;
        this.worldPool = Objects.requireNonNull(worldPool, "worldPool");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.mapMeta = Objects.requireNonNull(mapMeta, "mapMeta");
        this.mapTemplateId = Objects.requireNonNull(mapTemplateId, "mapTemplateId");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.world = world;
        this.remainingSeconds = mapMeta.durationSeconds();
        this.countdownSeconds = configService.joiningCountdownSeconds();
    }

    public String instanceId() {
        return instanceId;
    }

    public BreachMapMeta mapMeta() {
        return mapMeta;
    }

    public String mapTemplateId() {
        return mapTemplateId;
    }

    public String worldName() {
        return worldName;
    }

    public World world() {
        return world;
    }

    public BreachState state() {
        return state;
    }

    public int remainingSeconds() {
        return remainingSeconds;
    }

    public int elapsedActiveSeconds() {
        return elapsedActiveSeconds;
    }

    public int durationSeconds() {
        return mapMeta.durationSeconds();
    }

    public long stateChangedAtMillis() {
        return stateChangedAtMillis;
    }

    public int extractedCount() {
        return extractedPlayers.size();
    }

    public int eliminatedCount() {
        return eliminatedPlayers.size();
    }

    public int pendingJoinCount() {
        return joinCountdowns.size();
    }

    public int playersInBreachWorld() {
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (UUID playerId : participants) {
            if (extractedPlayers.contains(playerId) || eliminatedPlayers.contains(playerId)) {
                continue;
            }
            if (joinCountdowns.containsKey(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && player.getWorld().equals(world)) {
                count++;
            }
        }
        return count;
    }

    public String formattedRemainingTime() {
        return formatDuration(Math.max(0, remainingSeconds));
    }

    public int countdownSeconds() {
        return countdownSeconds;
    }

    public int participantCount() {
        return participants.size();
    }

    public int sessionKills(UUID playerId) {
        return playerId == null ? 0 : sessionKills.getOrDefault(playerId, 0);
    }

    public int sessionDeaths(UUID playerId) {
        return playerId == null ? 0 : sessionDeaths.getOrDefault(playerId, 0);
    }

    public void recordSessionDeath(UUID victimId, UUID killerId) {
        if (victimId != null) {
            sessionDeaths.merge(victimId, 1, Integer::sum);
        }
        if (killerId != null && !killerId.equals(victimId)) {
            sessionKills.merge(killerId, 1, Integer::sum);
        }
    }

    public void clearSessionStats(UUID playerId) {
        if (playerId == null) {
            return;
        }
        sessionKills.remove(playerId);
        sessionDeaths.remove(playerId);
    }

    public int activeParticipantCount() {
        return cachedActiveParticipantCount;
    }

    public Set<UUID> participantIdsSnapshot() {
        return Set.copyOf(participants);
    }

    private void refreshActiveParticipantCount() {
        int count = 0;
        for (UUID playerId : participants) {
            if (extractedPlayers.contains(playerId) || eliminatedPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                count++;
            }
        }
        cachedActiveParticipantCount = count;
    }

    public boolean isEliminated(UUID playerId) {
        return playerId != null && eliminatedPlayers.contains(playerId);
    }

    /** True when the player is in the custom soft-spectator state (replaces GameMode.SPECTATOR checks). */
    public boolean isSpectating(UUID playerId) {
        return playerId != null
                && gameplayCoordinator != null
                && gameplayCoordinator.spectatorService().isSpectating(playerId);
    }

    public boolean containsPlayer(UUID playerId) {
        return participants.contains(playerId);
    }

    public boolean hasExtracted(UUID playerId) {
        return extractedPlayers.contains(playerId);
    }

    public Optional<BreachInstance> setRecycleListener(Runnable onRecycleComplete) {
        this.onRecycleComplete = onRecycleComplete;
        return Optional.of(this);
    }

    public boolean canAcceptPlayers() {
        return canAcceptPlayers(1);
    }

    public boolean canAcceptPlayers(int additionalPlayers) {
        int joining = Math.max(1, additionalPlayers);
        return state == BreachState.ACTIVE && participants.size() + joining <= mapMeta.maxPlayers();
    }

    public boolean isPendingJoin(UUID playerId) {
        return playerId != null && joinCountdowns.containsKey(playerId);
    }

    public int joinCountdownSeconds(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        return Math.max(0, joinCountdowns.getOrDefault(playerId, 0));
    }

    public boolean join(Player player) {
        return join(player, null);
    }

    public boolean join(Player player, UUID partyId) {
        Objects.requireNonNull(player, "player");
        if (!canAcceptPlayers(1)) {
            return false;
        }
        hubReturnLocations.put(player.getUniqueId(), BreachHubLocations.capture(core, player));
        participants.add(player.getUniqueId());
        if (partyId != null) {
            playerPartyIds.put(player.getUniqueId(), partyId);
            partyMemberIds.computeIfAbsent(partyId, ignored -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
        }
        worldPool.trackParticipant(player.getUniqueId(), this);
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        worldPool.refreshJoinableIndex(this);
        if (gameplayCoordinator != null) {
            gameplayCoordinator.inventoryBridge().onJoinRaid(player);
        }
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.replenish(player);
        int countdown = configService.joiningCountdownSeconds();
        joinCountdowns.put(player.getUniqueId(), countdown);
        BreachMessages.infoHighlightKey(
                player,
                "extraction.breach.join.countdown_chat_prefix",
                mapMeta.displayName(),
                "extraction.breach.join.countdown_chat_suffix",
                countdown
        );
        return true;
    }

    public UUID partyIdFor(UUID playerId) {
        return playerId == null ? null : playerPartyIds.get(playerId);
    }

    public Set<UUID> partyMembers(UUID partyId) {
        if (partyId == null) {
            return Set.of();
        }
        Set<UUID> members = partyMemberIds.get(partyId);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    public void clearPartyTracking(UUID playerId) {
        if (playerId == null) {
            return;
        }
        UUID partyId = playerPartyIds.remove(playerId);
        if (partyId == null) {
            return;
        }
        Set<UUID> members = partyMemberIds.get(partyId);
        if (members != null) {
            members.remove(playerId);
            if (members.isEmpty()) {
                partyMemberIds.remove(partyId);
                partySpawnLocations.remove(partyId);
            }
        }
    }

    public Optional<Location> hubReturnLocation(UUID playerId) {
        Location location = hubReturnLocations.get(playerId);
        return location == null ? Optional.empty() : Optional.of(location.clone());
    }

    public Optional<Location> breachAnchor(UUID playerId) {
        Location location = breachAnchorLocations.get(playerId);
        return location == null ? Optional.empty() : Optional.of(location.clone());
    }

    public void clearPlayerLocations(UUID playerId) {
        if (playerId == null) {
            return;
        }
        hubReturnLocations.remove(playerId);
        breachAnchorLocations.remove(playerId);
        clearSessionStats(playerId);
    }

    public void leave(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        participants.remove(playerId);
        extractedPlayers.remove(playerId);
        eliminatedPlayers.remove(playerId);
        joinCountdowns.remove(playerId);
        clearPartyTracking(playerId);
        clearSessionStats(playerId);
        worldPool.untrackParticipant(player.getUniqueId());
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        worldPool.refreshJoinableIndex(this);
    }

    public void handleExtract(Player player) {
        if (player == null || state != BreachState.ACTIVE) {
            return;
        }
        if (!participants.contains(player.getUniqueId())) {
            return;
        }
        extractedPlayers.add(player.getUniqueId());
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                player,
                "extraction.breach.extract.success",
                mapMeta.displayName()
        ));
        if (gameplayCoordinator != null) {
            gameplayCoordinator.spectatorService().exit(player);
        }
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.replenish(player);
    }

    public boolean handlePlayerDeath(Player player) {
        return this.handlePlayerDeath(player, null);
    }

    public boolean handlePlayerDeath(Player player, PlayerDeathEvent deathEvent) {
        if (player == null) {
            return false;
        }
        if (state != BreachState.ACTIVE) {
            return false;
        }
        if (!participants.contains(player.getUniqueId()) || extractedPlayers.contains(player.getUniqueId())) {
            return false;
        }
        if (eliminatedPlayers.contains(player.getUniqueId())) {
            return true;
        }

        if (gameplayCoordinator != null && core != null && core.playerInventoryManager() != null) {
            gameplayCoordinator.corpseService().spawnCorpse(
                    player,
                    core.playerInventoryManager(),
                    core.coreHotbarService(),
                    deathEvent
            );
        } else {
            plugin.getLogger().warning("[Breach] Could not spawn corpse for " + player.getName()
                    + " (inventory manager unavailable).");
        }

        breachAnchorLocations.put(player.getUniqueId(), player.getLocation().clone());
        eliminatedPlayers.add(player.getUniqueId());
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        return true;
    }

    /**
     * Void elimination: no corpse is spawned (the body would be unreachable in the void). The player's raid
     * inventory is dropped when {@link #finishEliminatedRespawn(Player, Location)} runs. Returns true when the
     * player transitioned into the eliminated state.
     */
    public boolean handleVoidElimination(Player player) {
        if (player == null || state != BreachState.ACTIVE) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!participants.contains(playerId) || extractedPlayers.contains(playerId)) {
            return false;
        }
        if (eliminatedPlayers.contains(playerId)) {
            return true;
        }
        eliminatedPlayers.add(playerId);
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        return true;
    }

    public void finishEliminatedRespawn(Player player) {
        this.finishEliminatedRespawn(player, null);
    }

    /**
     * Transitions an eliminated player into the custom soft-spectator state. {@code vantage} optionally moves
     * the ghost to a safe location first (used for void deaths so they are not dropped back into the void).
     */
    public void finishEliminatedRespawn(Player player, Location vantage) {
        finishEliminatedRespawn(player, vantage, null);
    }

    public void finishEliminatedRespawn(Player player, Location vantage, String killerName) {
        if (player == null || !eliminatedPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.inventoryBridge().onDeathInRaid(player);
            gameplayCoordinator.spectatorService().enter(player, vantage);
        }
        if (core != null) {
            network.skypvp.extraction.gameplay.BreachCombatFeedback.showEliminated(player, killerName, core);
        }
        BreachMessages.errorKey(player, "extraction.breach.eliminated.prompt");
    }

    public Optional<Location> eliminatedRespawnLocation() {
        if (world == null) {
            return Optional.empty();
        }
        if (!mapMeta.spawnPoints().isEmpty()) {
            BreachMapMeta.SpawnPoint spawn = mapMeta.spawnPoints().get(0);
            return Optional.of(new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
        }
        return Optional.of(world.getSpawnLocation().clone());
    }

    private void syncBreachWorldSpawn() {
        if (world == null || mapMeta.spawnPoints().isEmpty()) {
            return;
        }
        BreachMapMeta.SpawnPoint spawn = mapMeta.spawnPoints().get(0);
        world.setSpawnLocation(
                (int) Math.floor(spawn.x()),
                (int) Math.floor(spawn.y()),
                (int) Math.floor(spawn.z())
        );
    }

    public void refreshExtractZoneBeacons() {
        if (gameplayCoordinator == null || world == null || state != BreachState.ACTIVE) {
            return;
        }
        List<Player> viewers = extractZoneViewers();
        if (viewers.isEmpty()) {
            return;
        }
        Location anchor = world.getSpawnLocation();
        this.scheduler.runAtLocation(anchor, () -> {
            if (this.gameplayCoordinator != null && this.world != null) {
                this.gameplayCoordinator.refreshExtractZoneBeacons(
                        this.world,
                        this.state,
                        this.remainingSeconds,
                        viewers,
                        this::extractZonePlayerView
                );
            }
        });
    }

    public void tick() {
        if (gameplayCoordinator != null && world != null && state == BreachState.ACTIVE) {
            List<Player> viewers = extractZoneViewers();
            if (!viewers.isEmpty()) {
                extractVisualCycle++;
                boolean includeParticles = extractVisualCycle % 3 == 0;
                Location anchor = world.getSpawnLocation();
                this.scheduler.runAtLocation(anchor, () -> {
                    if (this.gameplayCoordinator != null && this.world != null) {
                        this.gameplayCoordinator.tickExtractZones(
                                this.world,
                                this.mapMeta,
                                this.state,
                                this.remainingSeconds,
                                viewers,
                                this::extractZonePlayerView,
                                includeParticles
                        );
                    }
                });
            }
        }
        tickJoinCountdowns();
        switch (state) {
            case ACTIVE -> tickActive();
            case ENDING -> tickEnding();
            default -> {
            }
        }
    }

    public void activateMap() {
        if (state == BreachState.ACTIVE) {
            return;
        }
        startActiveMatch();
    }

    public void forceStart() {
        activateMap();
    }

    public void beginReset() {
        if (state == BreachState.RESETTING) {
            return;
        }
        transition(BreachState.RESETTING);
        for (UUID playerId : new HashSet<>(participants)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                this.scheduler.runOnPlayer(player, () -> {
                    if (gameplayCoordinator != null) {
                        gameplayCoordinator.inventoryBridge().onMatchEnd(player, extractedPlayers.contains(playerId));
                        gameplayCoordinator.spectatorService().exit(player);
                    }
                    BreachHubLocations.teleportToHub(core, player, hubReturnLocation(playerId));
                    player.setGameMode(GameMode.ADVENTURE);
                    BreachPlayerVitality.replenish(player);
                    clearPlayerLocations(playerId);
                });
            }
        }
        if (gameplayCoordinator != null) {
            // The world is about to be recycled (its entities/displays are removed on unload) and every player has
            // been teleported to the hub, so this teardown is best-effort. Guard it: a Folia off-region access
            // here must NOT abort beginReset before scheduleRecycleWorld runs, or the instance would never recycle.
            try {
                gameplayCoordinator.resetBosses();
                if (world != null) {
                    gameplayCoordinator.onWorldClosed(world);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("[Breach] World teardown for '" + worldName + "' raised "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage() + " (continuing with recycle).");
            }
        }
        worldPool.untrackInstance(this);
        participants.clear();
        extractedPlayers.clear();
        eliminatedPlayers.clear();
        cachedActiveParticipantCount = 0;
        cachedExtractZoneViewers = List.of();
        extractZoneViewersDirty = false;
        hubReturnLocations.clear();
        breachAnchorLocations.clear();
        sessionKills.clear();
        sessionDeaths.clear();
        joinCountdowns.clear();
        playerPartyIds.clear();
        partyMemberIds.clear();
        partySpawnLocations.clear();
        remainingSeconds = mapMeta.durationSeconds();
        countdownSeconds = configService.joiningCountdownSeconds();

        worldManager.scheduleRecycleWorld(worldName, mapTemplateId).whenComplete((ignored, error) -> {
            scheduler.runGlobal(() -> {
                if (error != null) {
                    plugin.getLogger().warning("[Breach] Failed to recycle world '" + worldName + "': " + error.getMessage());
                } else {
                    world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        worldPool.trackWorld(this);
                    }
                    worldGuardBridge.applyRegions(world, mapMeta.worldGuardRegions());
                    if (gameplayCoordinator != null && world != null) {
                        gameplayCoordinator.onWorldReady(world, mapMeta);
                    }
                }
                activateMap();
                worldPool.refreshJoinableIndex(this);
                if (onRecycleComplete != null) {
                    onRecycleComplete.run();
                }
            });
        });
    }

    private void startActiveMatch() {
        syncBreachWorldSpawn();
        transition(BreachState.ACTIVE);
        remainingSeconds = mapMeta.durationSeconds();
        elapsedActiveSeconds = 0;
        worldGuardBridge.applyRegions(world, mapMeta.worldGuardRegions());
        if (gameplayCoordinator != null) {
            gameplayCoordinator.onMatchStarted(world, mapMeta, mapTemplateId);
        }
        invalidateExtractZoneViewers();
    }

    private void tickJoinCountdowns() {
        if (joinCountdowns.isEmpty()) {
            return;
        }
        for (UUID playerId : new HashSet<>(joinCountdowns.keySet())) {
            Integer current = joinCountdowns.get(playerId);
            if (current == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !participants.contains(playerId)) {
                joinCountdowns.remove(playerId);
                continue;
            }
            int next = current - 1;
            if (next <= 0) {
                joinCountdowns.remove(playerId);
                this.scheduler.runOnPlayer(player, () -> deployPlayer(player));
                continue;
            }
            joinCountdowns.put(playerId, next);
            if (next <= 5) {
                this.scheduler.runOnPlayer(player, () -> showJoinCountdownTitle(player, next));
            } else if (next % 10 == 0) {
                this.scheduler.runOnPlayer(player, () ->
                        BreachMessages.infoKey(player, "extraction.breach.join.countdown_tick", next));
            }
        }
    }

    private void deployPlayer(Player player) {
        World targetWorld = this.world;
        if (targetWorld == null) {
            this.plugin.getLogger().warning("[Breach] Cannot deploy " + player.getName() + ": breach world unavailable.");
            return;
        }
        Location regionAnchor = breachRegionAnchor();
        // Spawn scoring reads breach-world blocks and entities; on Folia that must run on the map region.
        this.scheduler.runAtLocation(regionAnchor, () -> {
            Location spawn = resolveSpawnLocation(player);
            this.scheduler.runOnPlayer(player, () -> teleportIntoBreach(player, spawn));
        });
    }

    private Location breachRegionAnchor() {
        if (!mapMeta.spawnPoints().isEmpty()) {
            BreachMapMeta.SpawnPoint spawn = mapMeta.spawnPoints().get(0);
            return new Location(world, spawn.x(), spawn.y(), spawn.z());
        }
        return world.getSpawnLocation();
    }

    private void teleportIntoBreach(Player player, Location location) {
        player.teleportAsync(location).whenComplete((success, error) -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                this.plugin.getLogger().warning("[Breach] Failed to teleport " + player.getName()
                        + " into instance '" + instanceId + "': "
                        + (error != null ? error.getMessage() : "teleport returned false"));
                return;
            }
            this.scheduler.runOnPlayer(player, () -> finishDeploy(player, location));
        });
    }

    private Location resolveSpawnLocation(Player player) {
        World targetWorld = world != null ? world : player.getWorld();
        return BreachSpawnSafety.resolveSpawn(
                targetWorld,
                mapMeta.spawnPoints(),
                player.getUniqueId(),
                partyIdFor(player.getUniqueId()),
                this,
                gameplayCoordinator == null ? null : gameplayCoordinator.gunfireTracker(),
                partySpawnLocations
        );
    }

    private void finishDeploy(Player player, Location location) {
        if (!player.isOnline()) {
            return;
        }
        breachAnchorLocations.put(player.getUniqueId(), location.clone());
        player.setGameMode(GameMode.SURVIVAL);
        showBreachEntryWarning(player);
        invalidateExtractZoneViewers();
        if (gameplayCoordinator != null) {
            scheduler.runOnPlayerLater(player, () -> gameplayCoordinator.corpseService().showCorpsesInWorld(player), 2L);
        }
        BreachMessages.infoHighlightKey(
                player,
                "extraction.breach.enter.prefix",
                mapMeta.displayName(),
                "extraction.breach.enter.suffix"
        );
    }

    private void showJoinCountdownTitle(Player player, int seconds) {
        player.showTitle(Title.title(
                Component.text(Integer.toString(seconds), NamedTextColor.GOLD),
                network.skypvp.extraction.text.ExtractionTexts.plain(
                        player,
                        "extraction.title.join_countdown_subtitle"
                ),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(250))
        ));
    }

    private void showBreachEntryWarning(Player player) {
        int totalSeconds = Math.max(1, mapMeta.durationSeconds());
        int remaining = Math.max(0, remainingSeconds);
        int percentRemaining = (int) Math.round(100.0 * remaining / totalSeconds);
        int percentElapsed = (int) Math.round(100.0 * elapsedActiveSeconds / totalSeconds);
        boolean lateEntry = elapsedActiveSeconds > configService.joiningCountdownSeconds()
                && percentElapsed >= 15;
        int othersAlreadyRaiding = countOtherActiveRaidersInWorld(player.getUniqueId());

        String locale = network.skypvp.extraction.text.ExtractionTexts.locale(player);
        Component title = lateEntry
                ? network.skypvp.extraction.text.ExtractionTexts.miniMessage(player, "extraction.title.late_entry")
                : network.skypvp.extraction.text.ExtractionTexts.miniMessage(player, "extraction.title.entering");
        String timeLabel = formatDuration(remaining) + " remaining (" + percentRemaining + "%)";
        Component subtitle;
        if (lateEntry && othersAlreadyRaiding > 0) {
            subtitle = network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.title.late_looted",
                    timeLabel
            );
        } else if (lateEntry) {
            subtitle = network.skypvp.extraction.text.ExtractionTexts.miniMessageTemplate(
                    "<gray>" + timeLabel,
                    locale
            );
        } else {
            subtitle = network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.title.in_cycle",
                    timeLabel
            );
        }
        player.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
        ));
    }

    private int countOtherActiveRaidersInWorld(UUID joiningPlayerId) {
        if (world == null || joiningPlayerId == null) {
            return 0;
        }
        int count = 0;
        for (UUID playerId : participants) {
            if (playerId.equals(joiningPlayerId)) {
                continue;
            }
            if (joinCountdowns.containsKey(playerId)
                    || extractedPlayers.contains(playerId)
                    || eliminatedPlayers.contains(playerId)) {
                continue;
            }
            if (isSpectating(playerId)) {
                continue;
            }
            Player other = Bukkit.getPlayer(playerId);
            if (other == null || !other.isOnline()) {
                continue;
            }
            if (other.getWorld().equals(world)) {
                count++;
            }
        }
        return count;
    }

    private void tickActive() {
        remainingSeconds--;
        elapsedActiveSeconds++;
        if (gameplayCoordinator != null) {
            gameplayCoordinator.tickBosses(world, mapMeta, elapsedActiveSeconds);
        }
        if (remainingSeconds <= 0) {
            broadcastLocalized("extraction.breach.cycle.ended");
            transition(BreachState.ENDING);
        }
    }

    private void tickEnding() {
        if (System.currentTimeMillis() - stateChangedAtMillis >= configService.resetDelaySeconds() * 1000L) {
            beginReset();
        }
    }

    private void broadcastLocalized(String catalogKey) {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(player, catalogKey));
            }
        }
    }

    private void broadcast(String message) {
        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private void transition(BreachState next) {
        this.state = next;
        this.stateChangedAtMillis = System.currentTimeMillis();
        worldPool.refreshJoinableIndex(this);
    }

    private void invalidateExtractZoneViewers() {
        this.extractZoneViewersDirty = true;
    }

    private List<Player> extractZoneViewers() {
        if (extractZoneViewersDirty) {
            cachedExtractZoneViewers = buildExtractZoneViewers();
            extractZoneViewersDirty = false;
        }
        return cachedExtractZoneViewers;
    }

    private List<Player> buildExtractZoneViewers() {
        List<Player> viewers = new ArrayList<>();
        for (UUID playerId : participants) {
            if (extractedPlayers.contains(playerId) || eliminatedPlayers.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && world != null && player.getWorld().equals(world)) {
                viewers.add(player);
            }
        }
        return List.copyOf(viewers);
    }

    public List<UUID> participantsSnapshot() {
        return List.copyOf(participants);
    }

    public boolean isInExtractZone(Location location) {
        if (location == null || world == null || !world.getName().equals(location.getWorld().getName())) {
            return false;
        }
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            if (zone.contains(location.getX(), location.getY(), location.getZ())) {
                return true;
            }
        }
        return false;
    }

    private ExtractZonePlayerView extractZonePlayerView(Player player) {
        if (player == null) {
            return ExtractZonePlayerView.defaults();
        }
        return new ExtractZonePlayerView(
                extractedPlayers.contains(player.getUniqueId()),
                isInExtractZone(player.getLocation()),
                !isSpectating(player.getUniqueId())
        );
    }

    public String statusLine() {
        return localizedStatusLine(network.skypvp.extraction.text.ExtractionTexts.defaultLocale());
    }

    public String localizedStatusLine(String locale) {
        int totalSeconds = Math.max(1, mapMeta.durationSeconds());
        int remaining = Math.max(0, remainingSeconds);
        int percentRemaining = (int) Math.round(100.0 * remaining / totalSeconds);
        String stateKey = "extraction.state." + state.name().toLowerCase(Locale.ROOT);
        return network.skypvp.extraction.text.ExtractionTexts.text(
                "extraction.status.instance_line",
                locale,
                mapMeta.displayName(),
                network.skypvp.extraction.text.ExtractionTexts.text(stateKey, locale),
                activeParticipantCount(),
                formatDuration(remaining),
                percentRemaining
        );
    }

    private static String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int remainder = seconds % 60;
        return String.format("%d:%02d", minutes, remainder);
    }
}
