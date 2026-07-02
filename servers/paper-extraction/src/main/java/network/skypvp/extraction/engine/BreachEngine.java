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
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachSpectatorService;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.world.BreachWorldManager;
import network.skypvp.extraction.gameplay.BreachPartyJoin;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.SocialGraphRepository;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.PlatformTask;

public final class BreachEngine {

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private final BreachWorldPool worldPool;
    private final BreachQueueService queueService;
    private final BreachGameplayCoordinator gameplayCoordinator;
    private final Logger logger;
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
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        tickTask = scheduler.runGlobalTimer(this::tick, 20L, 20L);
        if (gameplayCoordinator != null) {
            gameplayCoordinator.startExtractTicker(plugin, scheduler, this);
            gameplayCoordinator.startLootChestAmbience(this, plugin, scheduler);
        }
        logger.info("[Breach] Engine started (max instances=" + configService.maxBreachesPerPod()
                + ", folia=" + scheduler.isFolia() + ").");
        worldPool.prewarmStandbyWorlds();
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.shutdownExtractTicker();
        }
        queueService.clear();
        worldPool.shutdown();
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
                scheduler.runOnPlayer(player, () -> cancelPendingJoin(player, instance));
                return CompletableFuture.completedFuture(true);
            }
            BreachMessages.errorKey(player, "extraction.breach.error.already_in_match");
            return CompletableFuture.completedFuture(false);
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
            BreachPartyJoin.PartyContext partyContext = partyOptional
                    .map(snapshot -> BreachPartyJoin.fromSnapshot(snapshot, player))
                    .orElseGet(() -> BreachPartyJoin.solo(player));
            if (partyContext.hasParty() && !BreachPartyJoin.initiatorCanStartBreach(partyOptional.orElse(null), player.getUniqueId())) {
                scheduler.runOnPlayer(player, () ->
                        BreachMessages.errorKey(player, "extraction.breach.error.party_officer_only"));
                return CompletableFuture.completedFuture(false);
            }
            return scheduleExecutePlay(player, mapId, partyContext);
        });
    }

    private CompletableFuture<Boolean> scheduleExecutePlay(
            Player player,
            String mapId,
            BreachPartyJoin.PartyContext partyContext
    ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        scheduler.runOnPlayer(player, () -> executePlay(player, mapId, partyContext).whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                result.completeExceptionally(cause);
                return;
            }
            result.complete(Boolean.TRUE.equals(value));
        }));
        return result;
    }

    private CompletableFuture<Boolean> executePlay(Player leader, String mapId, BreachPartyJoin.PartyContext partyContext) {
        List<Player> targets = partyContext.members().stream()
                .filter(member -> !member.getWorld().getName().startsWith("breach_"))
                .filter(member -> worldPool.findByPlayer(member.getUniqueId()).isEmpty())
                .toList();
        if (targets.isEmpty()) {
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

        String resolvedMap = mapId == null || mapId.isBlank()
                ? configService.defaultMapId()
                : mapId.trim().toLowerCase();

        if (!configService.mapEntry(resolvedMap).filter(BreachConfigService.BreachMapEntry::enabled).isPresent()) {
            scheduler.runOnPlayer(leader, () ->
                    BreachMessages.errorKey(leader, "extraction.breach.error.unknown_map", resolvedMap));
            return CompletableFuture.completedFuture(false);
        }

        String mapLabel = configService.mapMeta(resolvedMap)
                .map(meta -> meta.displayName())
                .orElse(resolvedMap);
        UUID partyId = partyContext.partyId();

        Optional<BreachInstance> joinable = worldPool.findJoinableInstance(resolvedMap);
        if (joinable.isPresent()) {
            BreachInstance instance = joinable.get();
            if (!instance.canAcceptPlayers(targets.size())) {
                scheduler.runOnPlayer(leader, () ->
                        BreachMessages.errorKey(leader, "extraction.breach.error.party_instance_full"));
                return CompletableFuture.completedFuture(false);
            }
            logger.info("[Breach] play() joining existing instance for party led by " + leader.getName());
            for (Player target : targets) {
                Player online = Bukkit.getPlayer(target.getUniqueId());
                if (online == null || !online.isOnline()) {
                    continue;
                }
                scheduler.runOnPlayer(online, () -> {
                    if (!instance.join(online, partyId)) {
                        BreachMessages.errorKey(online, "extraction.breach.error.join_failed");
                    }
                });
            }
            return CompletableFuture.completedFuture(true);
        }

        logger.info("[Breach] play() no joinable instance; capacityRemaining=" + worldPool.capacityRemaining()
                + " for " + leader.getName());
        if (worldPool.capacityRemaining() <= 0) {
            boolean queued = queueService.enqueue(leader, resolvedMap);
            scheduler.runOnPlayer(leader, () -> {
                if (queued) {
                    BreachMessages.infoHighlightKey(
                            leader,
                            "extraction.breach.info.queued_full_prefix",
                            mapLabel,
                            "extraction.breach.info.queued_full_suffix"
                    );
                } else {
                    BreachMessages.errorKey(leader, "extraction.breach.error.already_queued");
                }
            });
            return CompletableFuture.completedFuture(false);
        }

        logger.info("[Breach] play() acquiring new instance for map '" + resolvedMap + "' (" + leader.getName() + ")");
        return worldPool.acquireInstance(resolvedMap).thenApply(instance -> {
            logger.info("[Breach] play() instance acquired (" + instance.instanceId() + ", state=" + instance.state()
                    + ") for party led by " + leader.getName() + "; scheduling join.");
            if (!instance.canAcceptPlayers(targets.size())) {
                scheduler.runOnPlayer(leader, () ->
                        BreachMessages.errorKey(leader, "extraction.breach.error.party_instance_full"));
                return false;
            }
            for (Player target : targets) {
                Player online = Bukkit.getPlayer(target.getUniqueId());
                if (online == null || !online.isOnline()) {
                    continue;
                }
                scheduler.runOnPlayer(online, () -> {
                    if (!instance.join(online, partyId)) {
                        logger.warning("[Breach] play() join() returned false for " + online.getName()
                                + " (instance state=" + instance.state() + ", canAccept=" + instance.canAcceptPlayers() + ")");
                        BreachMessages.errorKey(online, "extraction.breach.error.join_failed");
                    }
                });
            }
            return true;
        }).exceptionally(error -> {
            Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            logger.log(java.util.logging.Level.SEVERE,
                    "[Breach] play() failed to acquire instance for " + leader.getName() + ": " + cause.getMessage(), cause);
            scheduler.runOnPlayer(leader, () ->
                    BreachMessages.errorKey(leader, "extraction.breach.error.start_failed", cause.getMessage()));
            return false;
        });
    }

    public void cancelPendingJoin(Player player, BreachInstance instance) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(instance, "instance");
        UUID partyId = instance.partyIdFor(player.getUniqueId());
        if (partyId != null) {
            for (UUID memberId : instance.partyMembers(partyId)) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null && member.isOnline() && instance.isPendingJoin(memberId)) {
                    cancelPendingJoinSingle(member, instance);
                }
            }
            return;
        }
        cancelPendingJoinSingle(player, instance);
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
        BreachPlayerVitality.replenish(player);
        BreachMessages.infoKey(player, "extraction.breach.join.cancelled");
    }

    private SocialGraphRepository socialGraphRepository() {
        PaperCorePlugin core = this.corePlugin();
        return core == null ? null : core.socialGraphRepository();
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
            BreachPlayerVitality.replenish(player);
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
        this.queueService.dequeue(player.getUniqueId());
        this.worldPool.findByPlayer(player.getUniqueId()).ifPresentOrElse(instance -> {
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.extractService().clearPlayer(player);
                this.gameplayCoordinator.inventoryBridge().onSpectatorExitRaid(player);
                if (instance.world() != null) {
                    this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
                }
            }
            instance.leave(player);
            this.worldPool.releaseIfIdle(instance);
            this.exitSpectator(player);
            this.teleportPlayerToHub(player, instance);
            instance.clearPlayerLocations(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.replenish(player);
            player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                    player,
                    "extraction.breach.leave.spectator"
            ));
        }, () -> player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                player,
                "extraction.breach.error.not_in_match"
        )));
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
            BreachPlayerVitality.replenish(player);
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
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.extractService().clearPlayer(player);
                this.gameplayCoordinator.inventoryBridge().onSpectatorExitRaid(player);
                if (instance.world() != null) {
                    this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
                }
            }
            this.exitSpectator(player);
            this.teleportPlayerToHub(player, instance);
            instance.clearPlayerLocations(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            BreachPlayerVitality.replenish(player);
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
            this.gameplayCoordinator.extractService().clearPlayer(player);
            if (instance.world() != null) {
                this.gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, instance.world());
            }
        }
        instance.leave(player);
        this.worldPool.releaseIfIdle(instance);
        this.exitSpectator(player);
        this.teleportPlayerToHub(player, instance);
        instance.clearPlayerLocations(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.replenish(player);
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
                    && instance.state() == network.skypvp.extraction.model.BreachState.ACTIVE
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
        BreachPlayerVitality.replenish(player);
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
            if (this.gameplayCoordinator != null) {
                this.gameplayCoordinator.extractService().clearPlayer(player);
                this.gameplayCoordinator.inventoryBridge()
                        .onExtractSuccess(player)
                        .whenComplete((ignored, error) -> this.scheduler.runOnPlayer(player, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (error != null) {
                                player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                                        player,
                                        "extraction.breach.error.extract_save_failed"
                                ));
                                this.plugin.getLogger().warning(
                                        "[Breach] Extract save failed for " + player.getName() + ": " + error.getMessage());
                                return;
                            }
                            this.recordExtraction(player);
                            this.returnPlayerToHub(
                                    player,
                                    instance,
                                    "extraction.breach.extract.success_vault_hint",
                                    true,
                                    instance.mapMeta().displayName()
                            );
                        }));
                return;
            }
            this.returnPlayerToHub(
                    player,
                    instance,
                    "extraction.breach.extract.success",
                    false,
                    instance.mapMeta().displayName()
            );
        });
    }

    private void tick() {
        for (BreachInstance instance : worldPool.instancesSnapshot()) {
            World world = instance.world();
            if (world == null) {
                continue;
            }
            // Instance logic touches breach-world players and block overlays; keep it off the global region.
            Location anchor = world.getSpawnLocation();
            this.scheduler.runAtLocation(anchor, instance::tick);
        }
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
