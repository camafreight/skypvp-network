package network.skypvp.extraction.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.gameplay.BreachDisconnectedStandInService;
import network.skypvp.extraction.gameplay.BreachArrivalCoordinator;
import network.skypvp.extraction.gameplay.BreachCombatFeedback;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachSpectatorService;
import network.skypvp.extraction.integration.BreachDisconnectedPresenceBridge;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.world.BreachWorldManager;
import network.skypvp.extraction.gameplay.BreachPartyJoin;
import network.skypvp.extraction.gameplay.BreachRosterService;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.nms.HeadlessSnapshot;
import network.skypvp.paper.repository.SocialGraphRepository;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.service.WorldStateService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.PlatformTask;

public final class BreachEngine {

    /** Max party members that can deploy together on a single breach. Larger parties pick a squad via the roster GUI. */
    public static final int BREACH_MAX_SQUAD = 4;

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private final BreachWorldPool worldPool;
    private final BreachQueueService queueService;
    /** Players waiting on a coalesced fresh-instance provision, per map (queue position UX). */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> provisionWaiters =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * One in-flight {@code /breach play} search per player. Running the command again while a search is active
     * CANCELS it (the flag is honored at every admit point), instead of silently launching a second matchmaking
     * flow — which used to double-admit the player into two instances with two deploy countdowns.
     */
    private final java.util.Map<UUID, PlaySearch> activeSearches = new java.util.concurrent.ConcurrentHashMap<>();

    /** Safety net for searches that route the player to another pod and never terminate locally. */
    private static final long SEARCH_TTL_MILLIS = 120_000L;

    private static final class PlaySearch {
        final long startedAtMillis = System.currentTimeMillis();
        volatile boolean cancelled;

        boolean expired() {
            return System.currentTimeMillis() - startedAtMillis > SEARCH_TTL_MILLIS;
        }
    }

    private void finishSearch(UUID playerId, PlaySearch search) {
        if (playerId != null && search != null) {
            this.activeSearches.remove(playerId, search);
        }
    }
    private final BreachGameplayCoordinator gameplayCoordinator;
    private final BreachArrivalCoordinator arrivalCoordinator;
    private final BreachDisconnectedStandInService disconnectedStandIns;
    private final BreachDisconnectedPresenceBridge disconnectedPresenceBridge;
    private final Logger logger;
    private BreachRosterService rosterService;
    private PlatformTask tickTask;

    public BreachEngine(
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
        this.gameplayCoordinator = gameplayCoordinator;
        this.logger = plugin.getLogger();
        this.arrivalCoordinator = new BreachArrivalCoordinator(scheduler, this.logger);
        this.disconnectedStandIns = core == null ? null : new BreachDisconnectedStandInService(plugin, scheduler, core);
        this.disconnectedPresenceBridge = core == null ? null : new BreachDisconnectedPresenceBridge(core, this.logger);
        this.worldPool = new BreachWorldPool(plugin, scheduler, configService, worldManager, worldGuardBridge, gameplayCoordinator, core);
        this.queueService = new BreachQueueService(new BreachQueueService.BreachConfigServiceAccessor() {
            @Override
            public int queueTimeoutSeconds() {
                return configService.queueTimeoutSeconds();
            }

            @Override
            public String defaultMapId() {
                return configService.defaultMapId();
            }
        });
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.bindTabVisibility(this);
        }
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.refreshTabVisibility();
        }
        tickTask = scheduler.runGlobalTimer(this::tick, 20L, 20L);
        if (disconnectedStandIns != null) {
            disconnectedStandIns.start();
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.startExtractTicker(plugin, scheduler, this);
            gameplayCoordinator.startLootChestAmbience(this, plugin, scheduler);
        }
        logger.info("[Breach] Engine started (max instances=" + configService.maxBreachesPerPod()
                + ", folia=" + scheduler.isFolia() + ").");
        worldPool.prewarmStandbyWorlds();
    }

    /**
     * Provisions and fully warms a joinable standby breach on pod startup so the first matchmaking request does not
     * pay world creation, chunk load, loot roll, or initial mob spawn costs.
     */
    public void warmStandbyInstances() {
        PaperCorePlugin core = corePlugin();
        String mapId = configService.defaultMapId();
        if (mapId == null || mapId.isBlank()) {
            publishJoinableWhenReady(core);
            return;
        }
        if (worldPool.findJoinableInstance(mapId).isPresent()) {
            logger.info("[Breach] Joinable standby already present for map '" + mapId + "'.");
            publishJoinableWhenReady(core);
            return;
        }
        if (worldPool.capacityRemaining() <= 0) {
            logger.warning("[Breach] No capacity for standby instance warm-up.");
            publishJoinableWhenReady(core);
            return;
        }
        logger.info("[Breach] Provisioning standby breach for map '" + mapId + "'...");
        worldPool.acquireFreshInstance(mapId).whenComplete((instance, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[Breach] Standby breach warm-up failed: " + error.getMessage(), error);
                scheduler.runGlobal(() -> publishJoinableWhenReady(core));
                return;
            }
            World world = instance.world();
            if (world == null) {
                scheduler.runGlobal(() -> publishJoinableWhenReady(core));
                return;
            }
            Location anchor = instanceAnchor(world, instance.mapMeta());
            scheduler.runAtLocation(anchor, () -> {
                if (gameplayCoordinator == null) {
                    worldPool.refreshJoinableIndex(instance);
                    publishJoinableWhenReady(core);
                    return;
                }
                gameplayCoordinator.warmStandbyInstance(
                        world,
                        instance.mapMeta(),
                        instance.mapTemplateId(),
                        () -> scheduler.runGlobal(() -> {
                            worldPool.refreshJoinableIndex(instance);
                            logger.info("[Breach] Standby breach '" + instance.instanceId()
                                    + "' is ready for matchmaking on map '" + mapId + "'.");
                            publishJoinableWhenReady(core);
                        })
                );
            });
        });
    }

    private static Location instanceAnchor(World world, BreachMapMeta meta) {
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

    private void publishJoinableWhenReady(PaperCorePlugin core) {
        if (core == null) {
            return;
        }
        // Release the mode-plugin hold so WorldState can open routing once settle/decorations are done.
        if (core.worldStateService() != null) {
            core.worldStateService().releaseRoutingHold(WorldStateService.HOLD_MODE_PLUGIN);
        }
        core.publishJoinableHeartbeatNow();
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.shutdownExtractTicker();
        }
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            this.cleanupInstanceReconnectState(instance);
        }
        if (disconnectedStandIns != null) {
            disconnectedStandIns.shutdown();
        }
        arrivalCoordinator.clear();
        queueService.clear();
        worldPool.shutdown();
    }

    /** Epoch millis when the player disconnected mid-raid, or {@code 0} if not disconnected. */
    public long disconnectedSinceInRaid(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        for (BreachInstance instance : this.worldPool.instancesSnapshot()) {
            if (instance.isDisconnected(playerId)) {
                return instance.disconnectedSince(playerId);
            }
        }
        return 0L;
    }

    public BreachDisconnectedStandInService disconnectedStandIns() {
        return disconnectedStandIns;
    }

    /** True if the player is a participant currently marked disconnected mid-raid in any active instance. */
    public boolean isDisconnectedInRaid(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            if (instance.isDisconnected(playerId)) {
                return true;
            }
        }
        return false;
    }

    /** True when the player is eliminated in a still-active breach (offline body kill, live death, etc.). */
    public boolean isEliminatedInActiveRaid(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return worldPool.findByPlayer(playerId)
                .filter(instance -> instance.state() == network.skypvp.extraction.model.BreachState.ACTIVE)
                .filter(instance -> instance.isEliminated(playerId))
                .isPresent();
    }

    /** Clears every proxy-side breach reconnect hint (away + offline-elimination spectator). */
    public void clearReconnectHints(UUID playerId) {
        if (playerId == null || this.disconnectedPresenceBridge == null) {
            return;
        }
        this.disconnectedPresenceBridge.publishCleared(playerId);
        this.disconnectedPresenceBridge.publishSpectatorCleared(playerId);
    }

    /**
     * Drops an eliminated spectator from all in-memory breach state and proxy reconnect pools so their next network
     * login uses normal lobby routing.
     *
     * @param quitting when {@code true}, uses lightweight spectator quit cleanup (no entity work on a disconnecting player)
     */
    public void releaseSpectatorSession(Player player, BreachInstance instance, boolean quitting) {
        if (player == null || instance == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        this.queueService.dequeue(playerId);
        if (this.disconnectedStandIns != null) {
            this.disconnectedStandIns.remove(playerId);
        }
        this.clearReconnectHints(playerId);
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.notifyRaidSessionEnded(player);
            this.gameplayCoordinator.extractService().clearPlayer(player);
            this.gameplayCoordinator.inventoryBridge().onSpectatorExitRaid(player);
            if (instance.world() != null) {
                this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
            }
        }
        BreachSpectatorService spectatorService = this.spectatorService();
        if (spectatorService != null) {
            if (quitting) {
                spectatorService.handleQuit(player);
            } else {
                spectatorService.exit(player);
            }
        }
        instance.leave(player);
        this.worldPool.releaseIfIdle(instance);
        instance.clearPlayerLocations(playerId);
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.refreshTabVisibility();
        }
    }

    public void releaseSpectatorSession(Player player, BreachInstance instance) {
        this.releaseSpectatorSession(player, instance, false);
    }

    public BreachWorldPool worldPool() {
        return worldPool;
    }

    public BreachQueueService queueService() {
        return queueService;
    }

    public BreachConfigService configService() {
        return configService;
    }

    public BreachGameplayCoordinator gameplayCoordinator() {
        return gameplayCoordinator;
    }

    public BreachArrivalCoordinator arrivalCoordinator() {
        return arrivalCoordinator;
    }

    public ServerPlatform scheduler() {
        return scheduler;
    }

    public BreachSpectatorService spectatorService() {
        return this.gameplayCoordinator == null ? null : this.gameplayCoordinator.spectatorService();
    }

    public boolean isSpectating(Player player) {
        BreachSpectatorService spectatorService = this.spectatorService();
        return spectatorService != null && spectatorService.isSpectating(player);
    }

    public CompletableFuture<Boolean> play(Player player, String mapId) {
        Objects.requireNonNull(player, "player");
        logger.info("[Breach] play() requested by " + player.getName() + " mapId=" + mapId);
        if (player.getWorld().getName().startsWith("breach_")) {
            BreachMessages.errorKey(player, "extraction.breach.error.in_lobby_world");
            return CompletableFuture.completedFuture(false);
        }

        Optional<BreachInstance> tracked = worldPool.findByPlayer(player.getUniqueId());
        if (tracked.isPresent()) {
            BreachInstance instance = tracked.get();
            if (instance.isPendingJoin(player.getUniqueId())) {
                cancelPendingJoin(player, instance);
                return CompletableFuture.completedFuture(true);
            }
            BreachMessages.errorKey(player, "extraction.breach.error.already_in_match");
            return CompletableFuture.completedFuture(false);
        }

        UUID playerId = player.getUniqueId();
        this.activeSearches.entrySet().removeIf(entry -> entry.getValue().expired());
        PlaySearch inFlight = this.activeSearches.get(playerId);
        if (inFlight != null && !inFlight.cancelled) {
            // Toggle semantics: a second /breach play while searching/provisioning cancels the search.
            inFlight.cancelled = true;
            this.activeSearches.remove(playerId, inFlight);
            this.queueService.dequeue(playerId);
            network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FAIL.play(player);
            BreachMessages.infoKey(player, "extraction.breach.info.search_cancelled");
            return CompletableFuture.completedFuture(true);
        }
        if (this.queueService.dequeue(playerId)) {
            // Queued on the pod-full queue with no live search: /breach play leaves the queue.
            network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FAIL.play(player);
            BreachMessages.infoKey(player, "extraction.breach.info.queue_left");
            return CompletableFuture.completedFuture(true);
        }

        SocialGraphRepository socialGraph = this.socialGraphRepository();
        if (socialGraph == null) {
            return scheduleExecutePlay(player, mapId, BreachPartyJoin.solo(player));
        }
        return socialGraph.partyForMember(player.getUniqueId())
                .exceptionally(error -> {
                    logger.log(
                            Level.WARNING,
                            "[Breach] party lookup failed for " + player.getName() + "; continuing as solo.",
                            error
                    );
                    return Optional.empty();
                })
                .thenCompose(partyOptional -> {
            SocialGraphRepository.PartySnapshot snapshot = partyOptional.orElse(null);
            if (snapshot == null || snapshot.partyId() == null) {
                return scheduleExecutePlay(player, mapId, BreachPartyJoin.solo(player));
            }
            if (!BreachPartyJoin.initiatorCanStartBreach(snapshot, player.getUniqueId())) {
                scheduler.runOnPlayer(player, () ->
                        BreachMessages.errorKey(player, "extraction.breach.error.party_officer_only"));
                return CompletableFuture.completedFuture(false);
            }
            // Parties bigger than a squad let the leader pick who deploys. A confirmed pick is consumed here; if none
            // exists yet, open the squad picker and bail (the picker re-invokes play() with a stored selection).
            if (snapshot.members().size() > BREACH_MAX_SQUAD) {
                java.util.Set<UUID> roster = rosterService().consumeSelection(player.getUniqueId());
                if (roster == null) {
                    SocialGraphRepository.PartySnapshot pending = snapshot;
                    scheduler.runOnPlayer(player, () -> rosterService().openRosterPicker(player, pending, mapId));
                    return CompletableFuture.completedFuture(false);
                }
                return scheduleExecutePlay(player, mapId, BreachPartyJoin.fromSnapshot(snapshot, player, roster));
            }
            return scheduleExecutePlay(player, mapId, BreachPartyJoin.fromSnapshot(snapshot, player));
        });
    }

    private PlaySearch beginSearch(UUID playerId) {
        PlaySearch search = new PlaySearch();
        this.activeSearches.put(playerId, search);
        return search;
    }

    private CompletableFuture<Boolean> scheduleExecutePlay(
            Player player,
            String mapId,
            BreachPartyJoin.PartyContext partyContext
    ) {
        return scheduleExecutePlay(player, mapId, partyContext, false, beginSearch(player.getUniqueId()));
    }

    private CompletableFuture<Boolean> scheduleExecutePlay(
            Player player,
            String mapId,
            BreachPartyJoin.PartyContext partyContext,
            boolean localOnly,
            PlaySearch search
    ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        scheduler.runOnPlayer(player, () -> executePlay(player, mapId, partyContext, localOnly, search).whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                finishSearch(player.getUniqueId(), search);
                result.completeExceptionally(cause);
                return;
            }
            result.complete(Boolean.TRUE.equals(value));
        }));
        return result;
    }

    private CompletableFuture<Boolean> executePlay(
            Player leader,
            String mapId,
            BreachPartyJoin.PartyContext partyContext,
            boolean localOnly,
            PlaySearch search
    ) {
        List<Player> targets = partyContext.members().stream()
                .filter(worldPool::isLobbyDeployable)
                .toList();
        if (targets.isEmpty() && partyContext.hasParty() && worldPool.isLobbyDeployable(leader)) {
            // Leader is in the hub but every other online member is already in a raid — still allow rejoining them.
            targets = List.of(leader);
        }
        if (targets.isEmpty()) {
            finishSearch(leader.getUniqueId(), search);
            scheduler.runOnPlayer(leader, () -> {
                if (worldPool.findByPlayer(leader.getUniqueId()).isPresent()) {
                    BreachMessages.errorKey(leader, "extraction.breach.error.already_in_match");
                } else if (partyContext.hasParty()) {
                    BreachMessages.errorKey(leader, "extraction.breach.error.party_no_members");
                } else {
                    BreachMessages.errorKey(leader, "extraction.breach.error.join_failed");
                }
            });
            return CompletableFuture.completedFuture(false);
        }

        List<UUID> deployableMemberIds = deployableMemberIds(partyContext, targets);

        String resolvedMap = mapId == null || mapId.isBlank()
                ? configService.defaultMapId()
                : mapId.trim().toLowerCase();

        if (!configService.mapEntry(resolvedMap).filter(BreachConfigService.BreachMapEntry::enabled).isPresent()) {
            finishSearch(leader.getUniqueId(), search);
            scheduler.runOnPlayer(leader, () ->
                    BreachMessages.errorKey(leader, "extraction.breach.error.unknown_map", resolvedMap));
            return CompletableFuture.completedFuture(false);
        }

        String mapLabel = configService.mapMeta(resolvedMap)
                .map(meta -> meta.displayName())
                .orElse(resolvedMap);
        // Matchmaking feedback: the search/provision path can take seconds (fresh instances
        // now WARM before admitting); silence here read as a broken command. The localOnly
        // proxy-fallback pass is a continuation of the same search — announcing again printed
        // "Finding a joinable breach..." twice per command.
        if (!localOnly) {
            scheduler.runOnPlayer(leader, () -> {
                if (leader.isOnline()) {
                    network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_SEARCH.play(leader);
                    BreachMessages.infoKey(leader, "extraction.breach.info.matchmaking");
                }
            });
        }
        UUID partyId = partyContext.partyId();
        int partySize = targets.size();
        int maxPerInstance = configService.mapMeta(resolvedMap)
                .map(BreachMapMeta::maxPlayers)
                .orElse(partySize);
        // A party bigger than a whole instance can never be co-located; refuse instead of retrying forever.
        if (partySize > maxPerInstance) {
            finishSearch(leader.getUniqueId(), search);
            scheduler.runOnPlayer(leader, () -> {
                network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FAIL.play(leader);
                BreachMessages.errorKey(leader, "extraction.breach.error.party_too_large", maxPerInstance);
            });
            return CompletableFuture.completedFuture(false);
        }

        // Ask the proxy to pull deployable party members onto this extraction server. Members already in a breach are
        // left in their raid; only lobby members and in-transit arrivals are gathered and held a slot.
        if (partyContext.hasParty()) {
            requestPartyGather(leader, deployableMemberIds);
        }

        // 1) Prefer an instance the party is already raiding, then any joinable instance on this pod.
        Optional<BreachInstance> joinable = Optional.empty();
        if (partyContext.hasParty()) {
            // Rejoin path: viability (35% rule) must NOT block returning to the party's own
            // raid — canAcceptPlayers would silently seat the member in a different breach.
            joinable = worldPool.findActiveInstanceForParty(partyContext.partyId(), resolvedMap)
                    .filter(instance -> instance.canAcceptPartyRejoin(partySize));
        }
        if (joinable.isEmpty()) {
            joinable = worldPool.findJoinableInstance(resolvedMap);
        }
        if (joinable.isPresent() && joinable.get().reserveSlots(partySize)) {
            BreachInstance instance = joinable.get();
            if (search != null && search.cancelled) {
                instance.releaseReservation(partySize);
                finishSearch(leader.getUniqueId(), search);
                return CompletableFuture.completedFuture(false);
            }
            logger.info("[Breach] play() reserved " + partySize + " slot(s) in existing instance "
                    + instance.instanceId() + " for party led by " + leader.getName());
            int raidersInside = instance.participantsSnapshot().size();
            scheduler.runOnPlayer(leader, () -> {
                if (leader.isOnline()) {
                    network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FOUND.play(leader);
                    BreachMessages.infoKey(leader, "extraction.breach.info.joining_existing", raidersInside);
                }
            });
            admitReservedParty(instance, targets, partyContext, deployableMemberIds);
            finishSearch(leader.getUniqueId(), search);
            return CompletableFuture.completedFuture(true);
        }

        // 2) No local joinable instance — ask the proxy to matchmake across the network breach pool.
        // The search token intentionally stays live here: the flow continues when the proxy either
        // routes the squad away or answers with BREACH_PLAY_LOCAL (continueLocalPlay).
        if (!localOnly) {
            PaperCorePlugin core = corePlugin();
            if (core != null) {
                scheduler.runOnPlayer(leader, () -> {
                    if (leader.isOnline()) {
                        network.skypvp.paper.integration.ProxyRouteMessenger.requestBreachPlay(
                                core,
                                leader,
                                resolvedMap,
                                deployableMemberIds,
                                partyContext.partyId()
                        );
                    }
                });
                return CompletableFuture.completedFuture(true);
            }
        }

        return continueLocalProvision(leader, resolvedMap, targets, partyContext, mapLabel, search);
    }

    /**
     * Proxy fallback when the network pool has no joinable breach: provision a fresh instance on this pod or enqueue.
     */
    public void continueLocalPlay(Player leader, String mapId) {
        if (leader == null || !leader.isOnline()) {
            return;
        }
        // Continuation of the search the player started with /breach play. No token (or a cancelled
        // one) means they cancelled during the proxy round-trip — do not resurrect the search.
        PlaySearch search = this.activeSearches.get(leader.getUniqueId());
        if (search == null || search.cancelled || search.expired()) {
            return;
        }
        SocialGraphRepository socialGraph = socialGraphRepository();
        if (socialGraph == null) {
            scheduleExecutePlay(leader, mapId, BreachPartyJoin.solo(leader), true, search);
            return;
        }
        socialGraph.partyForMember(leader.getUniqueId())
                .exceptionally(error -> Optional.empty())
                .thenAccept(partyOptional -> {
                    SocialGraphRepository.PartySnapshot snapshot = partyOptional.orElse(null);
                    BreachPartyJoin.PartyContext partyContext = snapshot == null || snapshot.partyId() == null
                            ? BreachPartyJoin.solo(leader)
                            : BreachPartyJoin.fromSnapshot(snapshot, leader);
                    scheduleExecutePlay(leader, mapId, partyContext, true, search);
                });
    }

    private CompletableFuture<Boolean> continueLocalProvision(
            Player leader,
            String resolvedMap,
            List<Player> targets,
            BreachPartyJoin.PartyContext partyContext,
            String mapLabel,
            PlaySearch search
    ) {
        if (search != null && search.cancelled) {
            finishSearch(leader.getUniqueId(), search);
            return CompletableFuture.completedFuture(false);
        }
        logger.info("[Breach] play() no joinable instance with room for party of " + targets.size()
                + "; capacityRemaining=" + worldPool.capacityRemaining() + " for " + leader.getName());
        if (worldPool.capacityRemaining() <= 0) {
            boolean queued = queueService.enqueue(leader, resolvedMap);
            finishSearch(leader.getUniqueId(), search);
            int position = queueService.position(leader.getUniqueId());
            int queueSize = queueService.size();
            scheduler.runOnPlayer(leader, () -> {
                if (queued) {
                    BreachMessages.infoHighlightKey(
                            leader,
                            "extraction.breach.info.queued_full_prefix",
                            mapLabel,
                            "extraction.breach.info.queued_full_suffix"
                    );
                    BreachMessages.infoKey(leader, "extraction.breach.info.queue_position",
                            Math.max(1, position), Math.max(1, queueSize));
                } else {
                    BreachMessages.errorKey(leader, "extraction.breach.error.already_queued");
                }
            });
            return CompletableFuture.completedFuture(false);
        }

        logger.info("[Breach] play() acquiring fresh instance for map '" + resolvedMap + "' (" + leader.getName() + ")");
        // Players waiting on a provisioning session are a QUEUE: concurrent acquires for the
        // same map coalesce onto one pending world, so everyone who plays during the warm-up
        // fills into the same fresh session. Show their position rather than provisioning
        // internals ("Finding a joinable breach…" already covers the search phase).
        java.util.concurrent.atomic.AtomicInteger waiters = provisionWaiters
                .computeIfAbsent(resolvedMap, ignored -> new java.util.concurrent.atomic.AtomicInteger());
        int queuePosition = waiters.incrementAndGet();
        scheduler.runOnPlayer(leader, () -> {
            if (leader.isOnline()) {
                BreachMessages.infoKey(leader, "extraction.breach.info.queue_position",
                        queuePosition, Math.max(queuePosition, waiters.get()));
            }
        });
        return acquireAndAdmitFreshInstance(leader, resolvedMap, targets, partyContext, 1, search)
                .whenComplete((seated, error) -> {
                    waiters.decrementAndGet();
                    finishSearch(leader.getUniqueId(), search);
                });
    }

    private static final int MAX_FRESH_INSTANCE_ATTEMPTS = 4;

    /**
     * Keeps provisioning fresh instances until the whole party fits into one (or the pod runs out of room / we
     * exhaust the retry budget). Because {@link BreachWorldPool#acquireFreshInstance} always yields a new raid,
     * each attempt makes forward progress even when other parties are grabbing slots concurrently.
     */
    private CompletableFuture<Boolean> acquireAndAdmitFreshInstance(
            Player leader,
            String resolvedMap,
            List<Player> targets,
            BreachPartyJoin.PartyContext partyContext,
            int attempt,
            PlaySearch search
    ) {
        return worldPool.acquireFreshInstance(resolvedMap).thenCompose(instance -> {
            if (search != null && search.cancelled) {
                // Cancelled mid-provision: leave the fresh instance as a joinable standby.
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            logger.info("[Breach] play() fresh instance acquired (" + instance.instanceId() + ", state="
                    + instance.state() + ", attempt=" + attempt + ") for party led by " + leader.getName());
            if (instance.reserveSlots(targets.size())) {
                admitReservedParty(instance, targets, partyContext, deployableMemberIds(partyContext, targets));
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
            if (attempt >= MAX_FRESH_INSTANCE_ATTEMPTS || worldPool.capacityRemaining() <= 0) {
                logger.warning("[Breach] play() could not seat party of " + targets.size() + " after " + attempt
                        + " attempt(s) for " + leader.getName());
                scheduler.runOnPlayer(leader, () -> {
                    network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FAIL.play(leader);
                    BreachMessages.errorKey(leader, "extraction.breach.error.party_instance_full");
                });
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            return acquireAndAdmitFreshInstance(leader, resolvedMap, targets, partyContext, attempt + 1, search);
        }).exceptionally(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            logger.log(Level.SEVERE,
                    "[Breach] play() failed to acquire instance for " + leader.getName() + ": " + cause.getMessage(), cause);
            scheduler.runOnPlayer(leader, () -> {
                network.skypvp.paper.library.NetworkSoundCue.MATCHMAKING_FAIL.play(leader);
                BreachMessages.errorKey(leader, "extraction.breach.error.start_failed", cause.getMessage());
            });
            return Boolean.FALSE;
        });
    }

    /**
     * Signals the proxy to co-locate the leader's selected breach squad ({@code roster}) onto this extraction server.
     * Members outside the roster are left where they are. An empty roster means the whole party. Best-effort: if the
     * plugin channel or proxy is unavailable it simply no-ops (members can still travel via /play or follow-leader).
     */
    private void requestPartyGather(Player leader, java.util.Collection<UUID> roster) {
        PaperCorePlugin core = this.corePlugin();
        if (core == null || leader == null) {
            return;
        }
        java.util.List<UUID> squad = roster == null ? java.util.List.of() : java.util.List.copyOf(roster);
        scheduler.runOnPlayer(leader, () -> {
            if (leader.isOnline()) {
                network.skypvp.paper.integration.ProxyRouteMessenger.gatherParty(core, leader, squad);
            }
        });
    }

    private void admitReservedParty(
            BreachInstance instance,
            List<Player> targets,
            BreachPartyJoin.PartyContext partyContext,
            List<UUID> deployableMemberIds
    ) {
        UUID partyId = partyContext.partyId();
        for (Player target : targets) {
            UUID memberId = target.getUniqueId();
            scheduler.runOnPlayer(target, () -> {
                Player online = Bukkit.getPlayer(memberId);
                if (online == null || !online.isOnline()) {
                    instance.releaseReservation(1);
                    return;
                }
                if (!instance.joinReserved(online, partyId)) {
                    logger.warning("[Breach] joinReserved() returned false for " + online.getName()
                            + " (instance state=" + instance.state() + ")");
                    BreachMessages.errorKey(online, "extraction.breach.error.join_failed");
                }
            });
        }
        // Hold slots for deployable party members still in transit from other servers.
        if (partyContext.hasParty() && deployableMemberIds != null && !deployableMemberIds.isEmpty()) {
            arrivalCoordinator.expectArrivals(instance, partyId, deployableMemberIds);
        }
    }

    /**
     * Party members already seated in a breach are excluded. In-transit members who are not yet tracked locally are
     * included so {@link BreachArrivalCoordinator} can hold slots for them.
     */
    private List<UUID> deployableMemberIds(BreachPartyJoin.PartyContext partyContext, List<Player> localTargets) {
        if (!partyContext.hasParty()) {
            return localTargets.stream().map(Player::getUniqueId).toList();
        }
        java.util.LinkedHashSet<UUID> deployable = new java.util.LinkedHashSet<>();
        for (Player target : localTargets) {
            deployable.add(target.getUniqueId());
        }
        for (UUID expectedId : partyContext.expectedMemberIds()) {
            if (expectedId == null || deployable.contains(expectedId)) {
                continue;
            }
            if (worldPool.findByPlayer(expectedId).isPresent()) {
                continue;
            }
            Player local = Bukkit.getPlayer(expectedId);
            if (local == null || !local.isOnline()) {
                deployable.add(expectedId);
            }
        }
        return List.copyOf(deployable);
    }

    public void cancelPendingJoin(Player player, BreachInstance instance) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(instance, "instance");
        UUID partyId = instance.partyIdFor(player.getUniqueId());
        if (partyId == null) {
            scheduler.runOnPlayer(player, () -> cancelPendingJoinSingle(player, instance));
            return;
        }

        SocialGraphRepository socialGraph = this.socialGraphRepository();
        if (socialGraph == null) {
            scheduler.runOnPlayer(player, () -> cancelWholePartyPendingJoin(instance, partyId));
            return;
        }

        UUID playerId = player.getUniqueId();
        socialGraph.partyForMember(playerId)
                .exceptionally(error -> {
                    logger.log(Level.WARNING,
                            "[Breach] cancel role lookup failed for " + player.getName() + "; allowing cancel.", error);
                    return Optional.empty();
                })
                .thenAccept(snapshotOptional -> {
                    boolean canCancelAll = snapshotOptional
                            .map(snapshot -> BreachPartyJoin.initiatorCanCancelBreach(snapshot, playerId))
                            .orElse(true);
                    scheduler.runOnPlayer(player, () -> {
                        if (!instance.isPendingJoin(playerId)) {
                            return;
                        }
                        if (canCancelAll) {
                            cancelWholePartyPendingJoin(instance, partyId);
                        } else {
                            // Plain members can only pull themselves out — they cannot abort the raid for the party.
                            cancelPendingJoinSingle(player, instance);
                            BreachMessages.errorKey(player, "extraction.breach.error.cancel_not_trusted");
                        }
                    });
                });
    }

    private void cancelWholePartyPendingJoin(BreachInstance instance, UUID partyId) {
        for (UUID memberId : instance.partyMembers(partyId)) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline() && instance.isPendingJoin(memberId)) {
                Player finalMember = member;
                scheduler.runOnPlayer(finalMember, () -> cancelPendingJoinSingle(finalMember, instance));
            }
        }
    }

    private void cancelPendingJoinSingle(Player player, BreachInstance instance) {
        if (!instance.isPendingJoin(player.getUniqueId())) {
            return;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.inventoryBridge().onCancelPendingJoin(player);
        }
        instance.leave(player);
        worldPool.releaseIfIdle(instance);
        instance.clearPlayerLocations(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.restore(player);
        BreachMessages.infoKey(player, "extraction.breach.join.cancelled");
    }

    private SocialGraphRepository socialGraphRepository() {
        PaperCorePlugin core = this.corePlugin();
        return core == null ? null : core.socialGraphRepository();
    }

    private BreachRosterService rosterService() {
        if (this.rosterService == null) {
            this.rosterService = new BreachRosterService(this.corePlugin(), this);
        }
        return this.rosterService;
    }

    public void leave(Player player) {
        this.requestLeave(player);
    }

    public void requestLeave(Player player) {
        Objects.requireNonNull(player, "player");
        this.queueService.dequeue(player.getUniqueId());
        Optional<BreachInstance> instanceOptional = this.worldPool.findByPlayer(player.getUniqueId());
        if (instanceOptional.isEmpty()) {
            Optional<BreachInstance> worldInstance = this.instanceForWorld(player.getWorld());
            if (worldInstance.isPresent()) {
                this.forceReturnFromBreachWorld(player, worldInstance.get());
                return;
            }
            if (player.getWorld().getName().startsWith("breach_")) {
                this.returnStrandedPlayerToHub(player);
                return;
            }
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.error.not_in_match"
            ));
            return;
        }
        BreachInstance instance = instanceOptional.get();
        if (instance.isPendingJoin(player.getUniqueId())) {
            this.cancelPendingJoin(player, instance);
            return;
        }
        if (instance.hasExtracted(player.getUniqueId())) {
            this.leaveExtracted(player, instance);
            return;
        }
        if (instance.isEliminated(player.getUniqueId()) || this.isSpectating(player)) {
            this.leaveSpectator(player);
            return;
        }
        if (this.gameplayCoordinator != null && this.gameplayCoordinator.leavePromptService() != null) {
            this.gameplayCoordinator.leavePromptService().openAbandonPrompt(player);
        } else {
            this.executeAbandonLeave(player);
        }
    }

    public void executeAbandonLeave(Player player) {
        Objects.requireNonNull(player, "player");
        this.scheduler.runOnPlayer(player, () -> this.executeAbandonLeaveOnPlayer(player));
    }

    private void executeAbandonLeaveOnPlayer(Player player) {
        this.queueService.dequeue(player.getUniqueId());
        this.worldPool.findByPlayer(player.getUniqueId()).ifPresent(instance -> {
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.notifyRaidSessionEnded(player);
                this.gameplayCoordinator.corpseService().spawnCorpse(
                        player,
                        this.gameplayCoordinator.core().playerInventoryManager(),
                        this.gameplayCoordinator.core().coreHotbarService()
                );
                this.gameplayCoordinator.extractService().clearPlayer(player);
                this.gameplayCoordinator.inventoryBridge().onAbandonRaid(player);
                if (instance.world() != null) {
                    this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
                }
            }
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            instance.leave(player);
            this.worldPool.releaseIfIdle(instance);
            this.exitSpectator(player);
            this.teleportPlayerToHub(player, instance);
            instance.clearPlayerLocations(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.restore(player);
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.inventoryBridge().restoreHubInventory(player);
            }
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.abandon.body_left"
            ));
        });
    }

    public void leaveSpectator(Player player) {
        Objects.requireNonNull(player, "player");
        this.scheduler.runOnPlayer(player, () -> this.leaveSpectatorOnPlayer(player));
    }

    private void leaveSpectatorOnPlayer(Player player) {
        this.worldPool.findByPlayer(player.getUniqueId()).ifPresentOrElse(instance -> {
            this.releaseSpectatorSession(player, instance);
            this.teleportPlayerToHub(player, instance);
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.restore(player);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.leave.spectator"
            ));
        }, () -> {
            this.clearReconnectHints(player.getUniqueId());
            this.exitSpectator(player);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.error.not_in_match"
            ));
        });
    }

    public void handleDisconnect(Player player) {
        if (player == null) {
            return;
        }
        Optional<BreachInstance> instance = this.worldPool.findByPlayer(player.getUniqueId());
        if (instance.isEmpty()) {
            return;
        }
        if (instance.get().hasExtracted(player.getUniqueId())) {
            return;
        }
        if (this.isSpectating(player) || instance.get().isEliminated(player.getUniqueId())) {
            return;
        }
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.corpseService().spawnCorpse(
                    player,
                    this.gameplayCoordinator.core().playerInventoryManager(),
                    this.gameplayCoordinator.core().coreHotbarService()
            );
            this.gameplayCoordinator.inventoryBridge().onAbandonRaid(player);
        }
    }

    /**
     * Mid-raid disconnect: keep the raider's slot (mark away), escrow their gear in the persistent RAID container, and
     * spawn a killable AFK stand-in where they logged off. No corpse and no gear wipe happen yet — reconnect restores
     * everything; only a stand-in death (or grace expiry) converts the escrow into a lootable corpse.
     */
    public void handleRaiderDisconnect(Player player, BreachInstance instance) {
        if (player == null || instance == null) {
            return;
        }
        PaperCorePlugin core = this.corePlugin();
        org.bukkit.inventory.ItemStack[] snapshot = null;
        if (core != null && core.playerInventoryManager() != null) {
            snapshot = network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseLayout.captureLootFromPlayer(
                    player, core.playerInventoryManager(), core.coreHotbarService(), null);
        }
        instance.markDisconnected(player.getUniqueId());
        instance.setDisconnectedResetHandler(this::eliminateDisconnectedRaiderOnReset);
        instance.setRaidResetHandler(this::cleanupInstanceReconnectState);
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.inventoryBridge().onDisconnectAway(player);
        }
        if (this.disconnectedStandIns != null) {
            this.disconnectedStandIns.spawn(player, instance.instanceId(), snapshot);
        }
        if (this.disconnectedPresenceBridge != null) {
            this.disconnectedPresenceBridge.publishPresent(player.getUniqueId(), instance.instanceId());
        }
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.refreshTabVisibility();
        }
        logger.info("[Breach] " + player.getName() + " disconnected mid-raid; holding slot in "
                + instance.instanceId() + " (disconnected stand-in spawned).");
    }

    /**
     * Reconnect: re-seat an away raider into their held slot — reload escrowed gear, restore health to the stand-in's
     * remaining HP (so reconnecting can't be abused to heal), drop them back where they logged off, and despawn the
     * stand-in. No-op when the stand-in was already eliminated while they were offline.
     */
    public void resumeDisconnectedRaider(Player player, BreachInstance instance) {
        if (player == null || instance == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (instance.isEliminated(playerId)) {
            this.rejoinEliminatedSpectator(player, instance);
            return;
        }
        if (!instance.isDisconnected(playerId) || this.disconnectedStandIns == null || !this.disconnectedStandIns.hasStandIn(playerId)) {
            instance.clearDisconnected(playerId);
            if (this.disconnectedPresenceBridge != null) {
                this.disconnectedPresenceBridge.publishCleared(playerId);
            }
            return;
        }
        double resumeHealth = BreachPlayerVitality.RAID_MAX_HEALTH;
        Location resumeLocation = null;
        Optional<HeadlessSnapshot> captured = this.disconnectedStandIns.consumeReconnectCapture(playerId);
        if (captured.isPresent()) {
            resumeLocation = captured.get().location();
            resumeHealth = Math.max(1.0D, captured.get().health());
        }
        Optional<BreachDisconnectedStandInService.StandIn> standInOptional = this.disconnectedStandIns.byOwner(playerId);
        if (resumeLocation == null && standInOptional.isPresent()) {
            BreachDisconnectedStandInService.StandIn standIn = standInOptional.get();
            resumeLocation = standIn.location();
            Player hung = this.plugin.getServer().getPlayer(playerId);
            if (hung != null && this.corePlugin() != null
                    && this.corePlugin().headlessPlayerService() != null
                    && this.corePlugin().headlessPlayerService().isHeadless(playerId)) {
                resumeLocation = hung.getLocation().clone();
                resumeHealth = Math.max(1.0D, hung.getHealth());
            }
        }
        this.disconnectedStandIns.remove(playerId);
        if (resumeLocation == null) {
            resumeLocation = instance.breachAnchor(playerId).orElse(null);
        }
        instance.clearDisconnected(playerId);
        if (this.disconnectedPresenceBridge != null) {
            this.disconnectedPresenceBridge.publishCleared(playerId);
        }
        Location target = resumeLocation;
        double health = Math.min(resumeHealth, BreachPlayerVitality.RAID_MAX_HEALTH);
        scheduler.runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.replenish(player);
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.inventoryBridge().onResumeRaid(player);
            }
            if (target != null && target.getWorld() != null) {
                player.teleportAsync(target);
            }
            applyResumeHealthWhenEnrolled(player, health, 10);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player, "extraction.breach.reconnect.resumed"));
        });
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.refreshTabVisibility();
        }
        logger.info("[Breach] " + player.getName() + " reconnected into raid " + instance.instanceId() + ".");
    }

    /**
     * Applies the saved resume health once the 40-HP raid pool enrollment has landed. Enrollment routes through
     * PlayerHealthService and can apply a tick or two after the join; setting 40 while the attribute still reads
     * vanilla 20 threw IllegalArgumentException and skipped the restore entirely.
     */
    private void applyResumeHealthWhenEnrolled(Player player, double health, int attemptsLeft) {
        scheduler.runOnPlayerLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            org.bukkit.attribute.AttributeInstance maxAttr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double cap = maxAttr != null ? maxAttr.getValue() : 20.0D;
            if (cap < BreachPlayerVitality.RAID_MAX_HEALTH - 0.01D && attemptsLeft > 0) {
                applyResumeHealthWhenEnrolled(player, health, attemptsLeft - 1);
                return;
            }
            player.setHealth(Math.max(1.0D, Math.min(health, cap)));
        }, 2L);
    }

    /**
     * Offline raider whose AFK body was killed (or grace expired) reconnects while the breach is still active: seat
     * them as a soft spectator with a full health bar, not as a live raider at the body's last HP.
     */
    public void rejoinEliminatedSpectator(Player player, BreachInstance instance) {
        if (player == null || instance == null || !instance.isEliminated(player.getUniqueId())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        instance.clearDisconnected(playerId);
        if (this.disconnectedStandIns != null) {
            this.disconnectedStandIns.remove(playerId);
        }
        if (this.disconnectedPresenceBridge != null) {
            this.disconnectedPresenceBridge.publishCleared(playerId);
        }
        // Keep spectator reconnect hint until they leave/crash while spectating; releaseSpectatorSession clears it.
        Location vantage = instance.eliminatedRespawnLocation()
                .or(() -> instance.breachAnchor(playerId))
                .orElse(null);
        scheduler.runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            instance.finishEliminatedRespawn(player, vantage, null);
        });
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.refreshTabVisibility();
        }
        logger.info("[Breach] " + player.getName() + " rejoined eliminated raid " + instance.instanceId() + " as spectator.");
    }

    /** A hung headless body (disconnected raider) was killed → eliminate its owner and drop their escrow as loot. */
    public void eliminateDisconnectedRaiderByHeadlessBody(Player dead, String killerName) {
        if (this.disconnectedStandIns == null || dead == null) {
            return;
        }
        this.disconnectedStandIns.byOwner(dead.getUniqueId()).ifPresent(standIn -> eliminateDisconnectedRaider(standIn, killerName, false));
    }

    private void eliminateDisconnectedRaider(BreachDisconnectedStandInService.StandIn standIn, String killerName) {
        this.eliminateDisconnectedRaider(standIn, killerName, false);
    }

    private void eliminateDisconnectedRaider(BreachDisconnectedStandInService.StandIn standIn, String killerName, boolean graceExpired) {
        if (standIn == null) {
            return;
        }
        UUID ownerId = standIn.ownerId();
        BreachInstance instance = null;
        for (BreachInstance candidate : worldPool.instancesSnapshot()) {
            if (candidate.instanceId().equals(standIn.instanceId())) {
                instance = candidate;
                break;
            }
        }
        if (this.gameplayCoordinator != null && this.gameplayCoordinator.corpseService() != null) {
            Location corpseAt = standIn.location();
            if (corpseAt != null && corpseAt.getWorld() != null) {
                // Corpse placement probes blocks for a surface — illegal on the global region
                // thread this sweep ticks on (getCurrentWorldData()==null NPE, retried forever).
                this.scheduler.runAtLocation(corpseAt, () -> this.gameplayCoordinator.corpseService().spawnCorpseFromSnapshot(
                        ownerId,
                        standIn.ownerName(),
                        corpseAt,
                        standIn.lootSnapshot(),
                        standIn.textureValue(),
                        standIn.textureSignature()));
            }
        }
        PaperCorePlugin core = this.corePlugin();
        if (core != null && core.playerInventoryManager() != null) {
            core.playerInventoryManager().clearRaid(ownerId);
        }
        this.disconnectedStandIns.remove(ownerId);
        if (this.disconnectedPresenceBridge != null) {
            this.disconnectedPresenceBridge.publishCleared(ownerId);
        }
        if (instance != null) {
            BreachCombatFeedback.broadcastElimination(instance, ownerId, standIn.ownerName(), killerName);
            BreachCombatFeedback.notifyPartyDisconnectedEliminated(
                    instance,
                    ownerId,
                    standIn.ownerName(),
                    killerName,
                    graceExpired
            );
            if (graceExpired) {
                instance.recordSessionDeath(ownerId, null);
            }
            instance.markEliminatedOffline(ownerId);
            if (instance.world() != null && this.gameplayCoordinator != null) {
                this.gameplayCoordinator.notifyPlayerRemovedFromRaid(
                        ownerId,
                        instance.world(),
                        standIn.location(),
                        true
                );
            }
            if (instance.state() == BreachState.ACTIVE && this.disconnectedPresenceBridge != null) {
                this.disconnectedPresenceBridge.publishSpectatorPresent(ownerId, instance.instanceId());
            }
        }
        logger.info("[Breach] Disconnected stand-in for " + standIn.ownerName() + " eliminated"
                + (killerName == null ? "" : " by " + killerName) + "; owner marked eliminated.");
    }

    private void cleanupInstanceReconnectState(BreachInstance instance) {
        if (instance == null) {
            return;
        }
        if (this.disconnectedStandIns != null) {
            this.disconnectedStandIns.removeForInstance(instance.instanceId());
        }
        if (this.disconnectedPresenceBridge == null) {
            return;
        }
        for (UUID participantId : instance.participantIdsSnapshot()) {
            this.disconnectedPresenceBridge.publishCleared(participantId);
            this.disconnectedPresenceBridge.publishSpectatorCleared(participantId);
        }
        for (UUID disconnectedId : instance.disconnectedPlayers()) {
            this.disconnectedPresenceBridge.publishCleared(disconnectedId);
        }
    }

    /**
     * Raid-reset teardown for a still-away raider (invoked from {@link BreachInstance#beginReset()}). They never
     * reconnected before the raid ended, so they forfeit the run: despawn the stand-in and wipe the escrow. No corpse
     * is dropped — the world is recycling and would be unreachable.
     */
    private void eliminateDisconnectedRaiderOnReset(UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        if (this.disconnectedStandIns != null) {
            this.disconnectedStandIns.remove(ownerId);
        }
        if (this.disconnectedPresenceBridge != null) {
            this.disconnectedPresenceBridge.publishCleared(ownerId);
        }
        PaperCorePlugin core = this.corePlugin();
        if (core != null && core.playerInventoryManager() != null) {
            core.playerInventoryManager().clearRaid(ownerId);
        }
        logger.info("[Breach] Disconnected raider " + ownerId + " forfeited (raid reset before reconnect); escrow wiped.");
    }

    /** Auto-eliminates disconnected raiders whose reconnect grace has expired. */
    private void sweepDisconnectedRaiders() {
        if (this.disconnectedStandIns == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            for (UUID disconnectedId : instance.disconnectedPlayers()) {
                if (now - instance.disconnectedSince(disconnectedId) < this.configService.disconnectedGraceMillis()) {
                    continue;
                }
                this.disconnectedStandIns.byOwner(disconnectedId).ifPresent(standIn -> this.eliminateDisconnectedRaider(standIn, null, true));
            }
        }
    }

    private void leaveExtracted(Player player, BreachInstance instance) {
        this.scheduler.runOnPlayer(player, () ->
                this.returnPlayerToHubKey(player, instance, "extraction.breach.leave.match"));
    }

    public void returnStrandedPlayerToHub(Player player) {
        Objects.requireNonNull(player, "player");
        this.scheduler.runOnPlayer(player, () -> {
            PaperCorePlugin core = this.corePlugin();
            if (core != null) {
                BreachHubLocations.teleportToHub(core, player, Optional.empty());
            }
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.restore(player);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.return.hub"
            ));
            this.resyncHubDecorations(player);
        });
    }

    public void forceReturnFromBreachWorld(Player player, BreachInstance instance) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(instance, "instance");
        this.scheduler.runOnPlayer(player, () -> {
            if (this.isSpectating(player) || instance.isEliminated(player.getUniqueId())) {
                this.releaseSpectatorSession(player, instance);
            } else if (instance.containsPlayer(player.getUniqueId())) {
                if (this.gameplayCoordinator != null) {
                    this.gameplayCoordinator.extractService().clearPlayer(player);
                    if (instance.world() != null) {
                        this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
                    }
                }
                instance.leave(player);
                this.worldPool.releaseIfIdle(instance);
                instance.clearPlayerLocations(player.getUniqueId());
            } else if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.extractService().clearPlayer(player);
                this.gameplayCoordinator.inventoryBridge().onSpectatorExitRaid(player);
                if (instance.world() != null) {
                    this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
                }
                this.clearReconnectHints(player.getUniqueId());
                this.exitSpectator(player);
                instance.clearPlayerLocations(player.getUniqueId());
            }
            this.teleportPlayerToHub(player, instance);
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.restore(player);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.return.hub"
            ));
            this.resyncHubDecorations(player);
        });
    }

    private void returnPlayerToHubKey(Player player, BreachInstance instance, String catalogKey, Object... args) {
        this.returnPlayerToHub(player, instance, catalogKey, false, args);
    }

    private void returnPlayerToHub(
            Player player,
            BreachInstance instance,
            String catalogKey,
            boolean skipInventoryRestore,
            Object... args
    ) {
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.notifyRaidSessionEnded(player);
            this.gameplayCoordinator.extractService().clearPlayer(player);
            if (instance.world() != null) {
                this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
            }
        }
        instance.leave(player);
        this.worldPool.releaseIfIdle(instance);
        if (this.gameplayCoordinator != null) {
            this.gameplayCoordinator.refreshTabVisibility();
        }
        this.exitSpectator(player);
        this.teleportPlayerToHub(player, instance);
        instance.clearPlayerLocations(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.restore(player);
        if (this.gameplayCoordinator != null && !skipInventoryRestore) {
            this.gameplayCoordinator.inventoryBridge().restoreHubInventory(player);
        }
        if (catalogKey != null && !catalogKey.isBlank()) {
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(player, catalogKey, args));
        }
        this.resyncHubDecorations(player);
    }

    private void resyncHubDecorations(Player player) {
        PaperCorePlugin core = this.corePlugin();
        if (core == null || core.npcLibrary() == null || player == null) {
            return;
        }
        for (long delay : new long[] {2L, 10L, 30L, 60L}) {
            this.scheduler.runOnPlayerLater(player, () -> core.npcLibrary().resyncViewer(player), delay);
        }
    }

    private void teleportPlayerToHub(Player player, BreachInstance instance) {
        PaperCorePlugin core = this.corePlugin();
        if (core == null || player == null || instance == null) {
            return;
        }
        BreachHubLocations.teleportToHub(core, player, instance.hubReturnLocation(player.getUniqueId()));
    }

    private void exitSpectator(Player player) {
        BreachSpectatorService spectatorService = this.spectatorService();
        if (spectatorService != null) {
            spectatorService.exit(player);
        }
    }

    /**
     * Void-detection entry point (called from the player's region thread by the move listener).
     * <ul>
     *   <li>Soft-spectator / extracted / pending players that fall are simply repositioned to a safe vantage.</li>
     *   <li>Live raiders in an active breach are eliminated (loot lost) and dropped into soft-spectator at a spawn point.</li>
     *   <li>Players in the hub (no breach session) are bounced back to the configured hub spawn.</li>
     * </ul>
     */
    public void onVoidFall(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<BreachInstance> worldInstance = this.instanceForWorld(player.getWorld());

        if (this.isSpectating(player)) {
            worldInstance.flatMap(BreachInstance::eliminatedRespawnLocation)
                    .ifPresent(player::teleportAsync);
            return;
        }

        if (worldInstance.isPresent()) {
            BreachInstance instance = worldInstance.get();
            boolean liveRaider = instance.containsPlayer(playerId)
                    && (instance.state() == network.skypvp.extraction.model.BreachState.ACTIVE
                    || instance.state() == network.skypvp.extraction.model.BreachState.TOXIC)
                    && !instance.hasExtracted(playerId)
                    && !instance.isEliminated(playerId)
                    && !instance.isPendingJoin(playerId);
            if (liveRaider) {
                this.handleVoidDeath(player, instance);
            } else {
                instance.eliminatedRespawnLocation().ifPresent(player::teleportAsync);
            }
            return;
        }

        this.teleportToHubSpawn(player);
    }

    /**
     * No-death-screen elimination. Called when a live raider would take fatal damage; the caller cancels the damage
     * so the player never sees the vanilla death screen and never respawns at the hub. Instead the corpse is dropped
     * where they fell, they are marked eliminated, healed, and transitioned into soft-spectator mode in place.
     */
    public void eliminateOnFatalDamage(Player player, BreachInstance instance, Player killer) {
        if (player == null || instance == null) {
            return;
        }
        if (!instance.handlePlayerDeath(player)) {
            return;
        }
        BreachPlayerVitality.restore(player);
        String killerName = killer != null ? killer.getName() : null;
        instance.finishEliminatedRespawn(player, null, killerName);
        if (this.gameplayCoordinator != null) {
            this.scheduler.runOnPlayerLater(player,
                    () -> this.gameplayCoordinator.corpseService().showCorpsesInWorld(player), 5L);
        }
    }

    public void eliminateOnFatalDamage(Player player, BreachInstance instance) {
        eliminateOnFatalDamage(player, instance, null);
    }

    private void handleVoidDeath(Player player, BreachInstance instance) {
        Location vantage = instance.eliminatedRespawnLocation().orElse(null);
        if (!instance.handleVoidElimination(player)) {
            if (vantage != null) {
                player.teleportAsync(vantage);
            }
            return;
        }
        instance.finishEliminatedRespawn(player, vantage);
        BreachMessages.errorKey(player, "extraction.breach.error.void_death");
        if (this.gameplayCoordinator != null) {
            this.scheduler.runOnPlayerLater(player,
                    () -> this.gameplayCoordinator.corpseService().showCorpsesInWorld(player), 5L);
        }
    }

    private void teleportToHubSpawn(Player player) {
        PaperCorePlugin core = this.corePlugin();
        if (core == null || core.worldStateService() == null) {
            return;
        }
        core.worldStateService().presetSpawnLocation().ifPresent(player::teleportAsync);
    }

    private PaperCorePlugin corePlugin() {
        return this.gameplayCoordinator == null ? null : this.gameplayCoordinator.core();
    }

    private void recordExtraction(Player player) {
        PaperCorePlugin core = this.corePlugin();
        if (core == null || core.playerStatsRepository() == null) {
            return;
        }
        java.util.UUID uuid = player.getUniqueId();
        // DB writes block on the async executor — keep them off the region thread.
        this.scheduler.runAsync(() -> core.playerStatsRepository().incrementExtractions(uuid));
    }

    public Optional<BreachInstance> instanceFor(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        return worldPool.findByPlayer(player.getUniqueId());
    }

    public List<BreachInstance> activeInstances() {
        return worldPool.instancesSnapshot();
    }

    public Optional<BreachInstance> instanceForWorld(World world) {
        return worldPool.findByWorld(world);
    }

    /**
     * Reserves a specific breach instance for a proxy party-queue deploy before members connect. Late arrivals are
     * admitted through {@link BreachArrivalCoordinator}.
     */
    public boolean admitPartyFromQueueDeploy(UUID partyId, String instanceId, List<UUID> memberIds) {
        if (partyId == null || instanceId == null || instanceId.isBlank() || memberIds == null || memberIds.isEmpty()) {
            return false;
        }
        Optional<BreachInstance> instanceOptional = this.instanceById(instanceId);
        if (instanceOptional.isEmpty()) {
            this.logger.warning("[Breach] queue deploy rejected — unknown instance " + instanceId);
            return false;
        }
        BreachInstance instance = instanceOptional.get();
        List<UUID> deployableMemberIds = memberIds.stream()
                .filter(memberId -> memberId != null && worldPool.findByPlayer(memberId).isEmpty())
                .toList();
        if (deployableMemberIds.isEmpty()) {
            this.logger.warning("[Breach] queue deploy rejected — no deployable members for party " + partyId);
            return false;
        }
        if (!instance.reserveSlots(deployableMemberIds.size())) {
            this.logger.warning("[Breach] queue deploy rejected — instance " + instanceId + " cannot fit "
                    + deployableMemberIds.size());
            return false;
        }
        for (UUID memberId : deployableMemberIds) {
            Player local = Bukkit.getPlayer(memberId);
            if (local == null || !local.isOnline()) {
                continue;
            }
            UUID seatedMemberId = memberId;
            this.scheduler.runOnPlayer(local, () -> {
                Player online = Bukkit.getPlayer(seatedMemberId);
                if (online == null || !online.isOnline()) {
                    instance.releaseReservation(1);
                    return;
                }
                if (!instance.joinReserved(online, partyId)) {
                    this.logger.warning("[Breach] queue deploy joinReserved() failed for " + online.getName()
                            + " in " + instanceId);
                }
            });
        }
        this.arrivalCoordinator.expectArrivals(instance, partyId, deployableMemberIds, false);
        this.logger.info("[Breach] queue deploy seated/reserved " + deployableMemberIds.size() + " slot(s) in "
                + instanceId + " for party " + partyId);
        return true;
    }

    /**
     * Aborts a proxy queue deploy: drops held arrival slots and cancels any local pending joins for the squad so a
     * member who disconnected or left mid-transfer is not force-admitted on reconnect.
     */
    public void cancelPartyQueueDeploy(UUID partyId, String instanceId, List<UUID> memberIds) {
        if (memberIds != null && !memberIds.isEmpty()) {
            this.arrivalCoordinator.cancelMembers(memberIds);
        }
        if (partyId != null) {
            this.arrivalCoordinator.cancelParty(partyId);
        }
        Optional<BreachInstance> instanceOptional = instanceId == null || instanceId.isBlank()
                ? Optional.empty()
                : this.instanceById(instanceId);
        if (instanceOptional.isEmpty() && partyId == null && (memberIds == null || memberIds.isEmpty())) {
            return;
        }
        List<UUID> targets = memberIds == null ? List.of() : memberIds;
        for (UUID memberId : targets) {
            if (memberId == null) {
                continue;
            }
            Player local = Bukkit.getPlayer(memberId);
            if (local == null || !local.isOnline()) {
                continue;
            }
            Optional<BreachInstance> tracked = this.worldPool.findByPlayer(memberId);
            if (tracked.isEmpty() || !tracked.get().isPendingJoin(memberId)) {
                continue;
            }
            Player online = local;
            BreachInstance instance = tracked.get();
            this.scheduler.runOnPlayer(online, () -> this.cancelPendingJoinSingle(online, instance));
        }
        if (instanceOptional.isPresent() && partyId != null) {
            BreachInstance instance = instanceOptional.get();
            for (UUID memberId : instance.partyMembers(partyId)) {
                Player local = Bukkit.getPlayer(memberId);
                if (local == null || !local.isOnline() || !instance.isPendingJoin(memberId)) {
                    continue;
                }
                Player online = local;
                this.scheduler.runOnPlayer(online, () -> this.cancelPendingJoinSingle(online, instance));
            }
        }
        this.logger.info("[Breach] cancelled queue deploy"
                + (partyId == null ? "" : " party=" + partyId)
                + (instanceId == null ? "" : " instance=" + instanceId));
    }

    /** Looks up an active instance by its id (used to credit stand-in eliminations back to the owning raid). */
    public Optional<BreachInstance> instanceById(String instanceId) {
        if (instanceId == null) {
            return Optional.empty();
        }
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            if (instanceId.equals(instance.instanceId())) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    public String statusFor(Player player) {
        return localizedStatusFor(player, network.skypvp.extraction.text.ExtractionTexts.defaultLocale());
    }

    public String localizedStatusFor(Player player, String locale) {
        Optional<BreachInstance> instance = instanceFor(player);
        if (instance.isPresent()) {
            return instance.get().localizedStatusLine(locale);
        }
        Optional<BreachQueueService.QueuedPlayer> queued = queueService.find(player.getUniqueId());
        if (queued.isPresent()) {
            int position = queueService.position(player.getUniqueId());
            return network.skypvp.extraction.text.ExtractionTexts.text(
                    "extraction.status.queued",
                    locale,
                    queued.get().mapId(),
                    position
            );
        }
        return network.skypvp.extraction.text.ExtractionTexts.text("extraction.status.idle", locale);
    }

    public void completeExtract(Player player, BreachInstance instance) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(instance, "instance");
        this.scheduler.runOnPlayer(player, () -> {
            String mapName = instance.mapMeta().displayName();
            if (this.gameplayCoordinator == null) {
                this.returnPlayerToHub(player, instance, "extraction.breach.extract.success", false, mapName);
                return;
            }

            this.gameplayCoordinator.extractService().clearPlayer(player);

            // Always pull the raider out of the breach before any DB work. A save failure must never
            // leave them stranded in-raid with loot still contested.
            this.returnPlayerToHub(player, instance, null, true, mapName);

            this.gameplayCoordinator.inventoryBridge()
                    .onExtractSuccess(player)
                    .whenComplete((ignored, error) -> this.scheduler.runOnPlayer(player, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        this.recordExtraction(player);
                        PaperCorePlugin levelCore = this.corePlugin();
                        if (levelCore != null && levelCore.playerLevelService() != null) {
                            levelCore.playerLevelService().addXp(player, 45L, "breach_extract");
                        }
                        if (error != null) {
                            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                                    player,
                                    "extraction.breach.error.extract_save_failed"
                            ));
                            this.plugin.getLogger().warning(
                                    "[Breach] Extract save failed for " + player.getName()
                                            + " (player already returned to hub with loot on them): "
                                            + error.getMessage());
                            return;
                        }
                        player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                                player,
                                "extraction.breach.extract.success_vault_hint",
                                mapName
                        ));
                    }));
        });
    }

    private void tick() {
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            World world = instance.world();
            if (world == null) {
                continue;
            }
            Location anchor = world.getSpawnLocation();
            // Reclaim dead weight: an unjoinable instance with nobody inside can never gain
            // players again — recycling it early frees a slot of the pod's breach cap so the
            // queue can drain into a fresh session instead of waiting out the full timer.
            if (instance.isIdleUnjoinable()) {
                logger.info("[Breach] Recycling idle unjoinable instance '" + instance.instanceId()
                        + "' (" + instance.remainingSeconds() + "s left, 0 players) to free pod capacity.");
                this.scheduler.runAtLocation(anchor, instance::beginReset);
                continue;
            }
            // CORE coordination (state machine, countdowns, schedule alerts, per-viewer fan-out)
            // runs HERE on the global heartbeat: it spans players/zones across many regions and
            // only performs cross-region-tolerated reads. Region-bound leaf work (material nodes,
            // beam displays, per-viewer packets, world teardown) is dispatched to its owning
            // region/player inside. Funneling the whole tick through the spawn-anchor region used
            // to pile every instance's per-second work onto the busiest region — visible spikes.
            try {
                instance.tick();
            } catch (RuntimeException ex) {
                logger.warning("[Breach] Instance '" + instance.instanceId() + "' tick failed: " + ex.getMessage());
            }
        }
        arrivalCoordinator.sweep();
        sweepDisconnectedRaiders();
        drainQueue();
    }

    private void drainQueue() {
        if (worldPool.capacityRemaining() <= 0) {
            return;
        }
        Optional<BreachQueueService.QueuedPlayer> next = queueService.pollReadyPlayer();
        if (next.isEmpty()) {
            return;
        }
        UUID playerId = next.get().playerId();
        String mapId = next.get().mapId();
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            drainQueue();
            return;
        }
        play(player, mapId);
    }
}
