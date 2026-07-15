package network.skypvp.extraction.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.gameplay.BreachCombatFeedback;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.extraction.gameplay.BreachSpawnSafety;
import network.skypvp.extraction.gameplay.BreachExtractZoneSchedule;
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
    /** Participants who disconnected mid-raid: their slot is held for reconnect while a killable stand-in guards it. */
    private final ConcurrentHashMap<UUID, Long> disconnectedPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> hubReturnLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> breachAnchorLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> joinCountdowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> playerPartyIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> partyMemberIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> partySpawnLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sessionKills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sessionDeaths = new ConcurrentHashMap<>();
    private final AtomicInteger reservedSlots = new AtomicInteger(0);
    /** Engine callback to tear down a disconnected raider's stand-in + escrow when the raid resets while they're offline. */
    private volatile java.util.function.Consumer<UUID> disconnectedResetHandler;
    /** Engine callback to clear proxy reconnect hints and local disconnected tracking when this instance resets. */
    private volatile java.util.function.Consumer<BreachInstance> raidResetHandler;
    private volatile BreachState state = BreachState.WAITING;
    private volatile World world;
    private volatile int countdownSeconds;
    private volatile int remainingSeconds;
    private volatile int elapsedActiveSeconds;
    private volatile int cachedActiveParticipantCount;
    private volatile List<Player> cachedExtractZoneViewers = List.of();
    private volatile boolean extractZoneViewersDirty = true;
    private volatile int toxicElapsedSeconds;
    private volatile BreachExtractZoneSchedule extractZoneSchedule;
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

    public int toxicElapsedSeconds() {
        return toxicElapsedSeconds;
    }

    public BreachExtractZoneSchedule extractZoneSchedule() {
        return extractZoneSchedule;
    }

    public boolean isToxicPhase() {
        return state == BreachState.TOXIC;
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

    /** Flags a participant as disconnected (slot held for reconnect). */
    public void markDisconnected(UUID playerId) {
        if (playerId != null && participants.contains(playerId)) {
            disconnectedPlayers.put(playerId, System.currentTimeMillis());
        }
    }

    public void clearDisconnected(UUID playerId) {
        if (playerId != null) {
            disconnectedPlayers.remove(playerId);
        }
    }

    public boolean isDisconnected(UUID playerId) {
        return playerId != null && disconnectedPlayers.containsKey(playerId);
    }

    /**
     * Offline elimination (AFK body killed, grace expiry, etc.): keep the slot as an eliminated spectator candidate
     * instead of dropping the player from the instance entirely.
     */
    public void markEliminatedOffline(UUID playerId) {
        if (playerId == null || !participants.contains(playerId) || extractedPlayers.contains(playerId)) {
            return;
        }
        clearDisconnected(playerId);
        if (eliminatedPlayers.add(playerId)) {
            refreshActiveParticipantCount();
            invalidateExtractZoneViewers();
            if (gameplayCoordinator != null) {
                gameplayCoordinator.refreshTabVisibility();
            }
        }
    }

    /** Millis since epoch the player disconnected mid-raid, or 0 if not disconnected. */
    public long disconnectedSince(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        return disconnectedPlayers.getOrDefault(playerId, 0L);
    }

    public Set<UUID> disconnectedPlayers() {
        return Set.copyOf(disconnectedPlayers.keySet());
    }

    /**
     * Registers the engine callback that tears down a disconnected raider's stand-in + escrow when the raid resets
     * while they are still offline. Decouples {@link #beginReset()} from the engine's disconnected-stand-in service.
     */
    public void setDisconnectedResetHandler(java.util.function.Consumer<UUID> handler) {
        this.disconnectedResetHandler = handler;
    }

    public void setRaidResetHandler(java.util.function.Consumer<BreachInstance> handler) {
        this.raidResetHandler = handler;
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

    /** Extra travel/positioning margin a fresh joiner needs beyond countdown + dwell. */
    private static final int JOIN_VIABILITY_TRAVEL_BUFFER_SECONDS = 60;
    /** Sampling step when probing whether any extract window still lies ahead. */
    private static final int JOIN_VIABILITY_SAMPLE_STEP_SECONDS = 30;
    /** A breach is unjoinable once 35% or less of its total session time remains. */
    private static final double JOIN_VIABILITY_MIN_REMAINING_FRACTION = 0.35D;

    /**
     * True while the instance is finishing loot activation + initial mob spawns. Fresh
     * instances used to accept players the instant their world existed — raiders landed in
     * empty, loot-less maps because only standby instances ever warmed.
     */
    private volatile boolean warming;

    public void markWarming(boolean warming) {
        this.warming = warming;
    }

    public boolean isWarming() {
        return warming;
    }

    public boolean canAcceptPlayers() {
        return canAcceptPlayers(1);
    }

    public boolean canAcceptPlayers(int additionalPlayers) {
        int joining = Math.max(1, additionalPlayers);
        return state == BreachState.ACTIVE
                && !warming
                && participants.size() + reservedSlots.get() + joining <= mapMeta.maxPlayers()
                && isJoinViable();
    }

    /**
     * Party REJOIN bypasses the late-join viability gate: a member returning to their
     * squad's ongoing raid accepts its remaining time — refusing them here would matchmake
     * them into a DIFFERENT breach and split the party. Capacity and state still apply.
     */
    public boolean canAcceptPartyRejoin(int additionalPlayers) {
        int joining = Math.max(1, additionalPlayers);
        return state == BreachState.ACTIVE
                && participants.size() + reservedSlots.get() + joining <= mapMeta.maxPlayers();
    }

    /**
     * Dead weight: ACTIVE but past the join-viability line with nobody inside and no held
     * slots. Such an instance can never gain players again — it only burns a slot of the
     * pod's breach cap while queued players wait. The engine tick recycles these early.
     */
    public boolean isIdleUnjoinable() {
        return state == BreachState.ACTIVE
                && !warming
                && participants.isEmpty()
                && reservedSlots.get() == 0
                && !isJoinViable();
    }

    /**
     * A raid only accepts NEW players while a fresh joiner can still realistically extract.
     * Matchmaking used to route players into breaches whose extractions had already
     * force-closed — an unavoidable loot wipe. Requires enough remaining time for the join
     * countdown + travel + dwell BEFORE the force-close window, and at least one extract
     * zone whose schedule is (or becomes) usable in that span. Applies to local matchmaking,
     * the world pool cache, and the capacity snapshots the proxy matchmaker consumes.
     */
    public boolean isJoinViable() {
        if (state != BreachState.ACTIVE) {
            return false;
        }
        int joinCountdown = Math.max(0, configService.joiningCountdownSeconds());
        int dwell = Math.max(1, configService.extractDwellSeconds());
        int forceClose = Math.max(0, configService.extractForceCloseSeconds());
        int viabilityFloor = forceClose + dwell;
        // Unjoinable at <= 35% of total session time; the absolute floor (countdown +
        // travel + dwell before force-close) still applies for short maps where 35%
        // would land inside the closed-extraction window.
        int fractionThreshold = (int) Math.ceil(
                mapMeta.durationSeconds() * JOIN_VIABILITY_MIN_REMAINING_FRACTION);
        int minimumRemaining = Math.max(
                fractionThreshold,
                viabilityFloor + joinCountdown + JOIN_VIABILITY_TRAVEL_BUFFER_SECONDS);
        int remaining = remainingSeconds;
        if (remaining <= minimumRemaining) {
            return false;
        }
        BreachExtractZoneSchedule schedule = extractZoneSchedule;
        if (schedule == null || mapMeta.extractZones().isEmpty()) {
            return true;
        }
        // Zones open in staggered windows: probe from "just after the joiner lands" down to
        // the force-close floor; any usable sample means an extract window still lies ahead.
        for (int sample = remaining - joinCountdown; sample >= viabilityFloor;
                sample -= JOIN_VIABILITY_SAMPLE_STEP_SECONDS) {
            for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
                if (schedule.isZoneUsable(zone.id(), BreachState.ACTIVE, sample)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Test hook for {@code /breach set <seconds>}: jumps the match clock so extraction
     * closings, toxicity, and phase transitions can be exercised without waiting. All
     * schedules/HUD/beacon states derive from {@code remainingSeconds}, so setting it
     * advances everything coherently on the next engine tick.
     */
    public boolean debugSetRemainingSeconds(int seconds) {
        if (state != BreachState.ACTIVE) {
            return false;
        }
        remainingSeconds = Math.max(0, Math.min(mapMeta.durationSeconds(), seconds));
        invalidateExtractZoneViewers();
        return true;
    }

    /** Open player slots in this instance (participants + reservations already counted). */
    public int openSlots() {
        if (state != BreachState.ACTIVE) {
            return 0;
        }
        return Math.max(0, mapMeta.maxPlayers() - participants.size() - reservedSlots.get());
    }

    /**
     * Atomically reserves {@code count} slots so an entire party can be admitted without a mid-join capacity race
     * (previously two parties joining concurrently could over-fill or partially admit an instance). Reserved slots
     * count against {@link #canAcceptPlayers(int)} until they are consumed by {@link #joinReserved(Player, UUID)} or
     * returned via {@link #releaseReservation(int)}.
     */
    public synchronized boolean reserveSlots(int count) {
        int needed = Math.max(0, count);
        if (needed == 0) {
            return true;
        }
        if (state != BreachState.ACTIVE) {
            return false;
        }
        if (participants.size() + reservedSlots.get() + needed > mapMeta.maxPlayers()) {
            return false;
        }
        reservedSlots.addAndGet(needed);
        worldPool.refreshJoinableIndex(this);
        return true;
    }

    public void releaseReservation(int count) {
        if (count <= 0) {
            return;
        }
        reservedSlots.updateAndGet(current -> Math.max(0, current - count));
        worldPool.refreshJoinableIndex(this);
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
        if (!reserveSlots(1)) {
            return false;
        }
        return joinReserved(player, partyId);
    }

    /**
     * Admits a player using a slot previously secured by {@link #reserveSlots(int)}. The capacity check is skipped
     * because the slot is already held; the reservation is consumed here. If the instance is no longer accepting
     * players (e.g. it rolled into ENDING/RESETTING between reserve and join) the reservation is returned and the
     * join is refused.
     */
    public boolean joinReserved(Player player, UUID partyId) {
        Objects.requireNonNull(player, "player");
        if (state != BreachState.ACTIVE) {
            releaseReservation(1);
            return false;
        }
        // Never double-book a player: concurrent matchmaking flows (or a proxy deploy racing a local
        // provision) would otherwise seat them in two instances at once — the pool index only tracks
        // the last one, leaving the other's deploy countdown orphaned and uncancellable.
        BreachInstance alreadyIn = worldPool.findByPlayer(player.getUniqueId()).orElse(null);
        if (alreadyIn != null && alreadyIn != this) {
            releaseReservation(1);
            return false;
        }
        reservedSlots.updateAndGet(current -> Math.max(0, current - 1));
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
            gameplayCoordinator.notifyRaidSessionStarted(player);
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
        if (gameplayCoordinator != null) {
            gameplayCoordinator.refreshTabVisibility();
        }
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

    /** True when the party has at least one live raider (not pending join / extracted / eliminated) in this instance. */
    public boolean hasActivePartyMember(UUID partyId) {
        if (partyId == null || state != BreachState.ACTIVE) {
            return false;
        }
        for (UUID participantId : participants) {
            if (isPendingJoin(participantId) || hasExtracted(participantId) || isEliminated(participantId)) {
                continue;
            }
            if (partyId.equals(playerPartyIds.get(participantId))) {
                return true;
            }
        }
        return false;
    }

    /** Party ids with live raiders in this instance (published to the proxy heartbeat). */
    public java.util.Set<UUID> activePartyIds() {
        if (state != BreachState.ACTIVE) {
            return java.util.Set.of();
        }
        java.util.Set<UUID> partyIds = new java.util.HashSet<>();
        for (UUID participantId : participants) {
            if (isPendingJoin(participantId) || hasExtracted(participantId) || isEliminated(participantId)) {
                continue;
            }
            UUID partyId = playerPartyIds.get(participantId);
            if (partyId != null) {
                partyIds.add(partyId);
            }
        }
        return java.util.Set.copyOf(partyIds);
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
        removeOfflineParticipant(player.getUniqueId());
    }

    /** Removes a participant by id without needing a live {@link Player} (used for extracted/leaving players). */
    public void removeOfflineParticipant(UUID playerId) {
        if (playerId == null) {
            return;
        }
        participants.remove(playerId);
        extractedPlayers.remove(playerId);
        eliminatedPlayers.remove(playerId);
        disconnectedPlayers.remove(playerId);
        joinCountdowns.remove(playerId);
        clearPartyTracking(playerId);
        clearSessionStats(playerId);
        // Conditional: leaving THIS instance must not erase the index entry of another instance
        // that admitted the player afterwards.
        worldPool.untrackParticipant(playerId, this);
        refreshActiveParticipantCount();
        invalidateExtractZoneViewers();
        worldPool.refreshJoinableIndex(this);
        if (gameplayCoordinator != null) {
            gameplayCoordinator.refreshTabVisibility();
        }
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
        if (gameplayCoordinator != null) {
            gameplayCoordinator.notifyPlayerRemovedFromRaid(
                    player.getUniqueId(),
                    world,
                    player.getLocation(),
                    false
            );
        }
        player.sendMessage(network.skypvp.extraction.text.ExtractionTexts.miniMessage(
                player,
                "extraction.breach.extract.success",
                mapMeta.displayName()
        ));
        if (gameplayCoordinator != null) {
            gameplayCoordinator.spectatorService().exit(player);
        }
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.restore(player);
    }

    public boolean handlePlayerDeath(Player player) {
        return this.handlePlayerDeath(player, null);
    }

    public boolean handlePlayerDeath(Player player, PlayerDeathEvent deathEvent) {
        if (player == null) {
            return false;
        }
        if (state != BreachState.ACTIVE && state != BreachState.TOXIC) {
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
        if (player == null || (state != BreachState.ACTIVE && state != BreachState.TOXIC)) {
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
        if (state == BreachState.TOXIC) {
            finishHardElimination(player, killerName);
            return;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.inventoryBridge().onDeathInRaid(player);
            gameplayCoordinator.spectatorService().enter(player, vantage);
        }
        if (core != null) {
            network.skypvp.extraction.gameplay.BreachCombatFeedback.showEliminated(player, killerName, core);
        }
        BreachCombatFeedback.broadcastElimination(this, player, killerName);
        Location lastLocation = breachAnchorLocations.get(player.getUniqueId());
        if (lastLocation == null) {
            lastLocation = player.getLocation();
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.notifyPlayerRemovedFromRaid(
                    player.getUniqueId(),
                    world,
                    lastLocation,
                    true
            );
            gameplayCoordinator.refreshHudAfterElimination(player);
        }
        BreachMessages.errorKey(player, "extraction.breach.eliminated.prompt");
    }

    /** Toxic phase elimination: corpse stays in the raid, player returns to hub without spectator mode. */
    public void finishHardElimination(Player player, String killerName) {
        if (player == null || !eliminatedPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.inventoryBridge().onDeathInRaid(player);
            gameplayCoordinator.spectatorService().exit(player);
        }
        if (core != null) {
            network.skypvp.extraction.gameplay.BreachCombatFeedback.showToxicEliminated(player, killerName, core);
        }
        BreachCombatFeedback.broadcastElimination(this, player, killerName);
        Location lastLocation = breachAnchorLocations.get(player.getUniqueId());
        if (lastLocation == null) {
            lastLocation = player.getLocation();
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.notifyPlayerRemovedFromRaid(
                    player.getUniqueId(),
                    world,
                    lastLocation,
                    true
            );
        }
        if (gameplayCoordinator != null && core != null) {
            BreachHubLocations.teleportToHub(core, player, hubReturnLocation(player.getUniqueId()));
            gameplayCoordinator.clearExtractZoneVisualsForPlayer(player, world);
            gameplayCoordinator.notifyRaidSessionEnded(player);
            gameplayCoordinator.refreshTabVisibility();
        }
        player.setGameMode(GameMode.ADVENTURE);
        BreachPlayerVitality.restore(player);
        leave(player);
        clearPlayerLocations(player.getUniqueId());
        BreachMessages.errorKey(player, "extraction.toxic.eliminated");
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
        // The visual service dispatches per viewer / per beam with correct region affinity.
        this.gameplayCoordinator.refreshExtractZoneBeacons(
                this.world,
                this.state,
                this.remainingSeconds,
                viewers,
                this::extractZonePlayerView
        );
    }

    public void tick() {
        int previousRemaining = remainingSeconds;
        BreachState previousState = state;
        tickJoinCountdowns();
        switch (state) {
            case ACTIVE -> tickActive();
            case TOXIC -> tickToxic();
            case ENDING -> tickEnding();
            default -> {
            }
        }
        if (gameplayCoordinator != null && world != null
                && (state == BreachState.ACTIVE || state == BreachState.TOXIC)) {
            List<Player> viewers = extractZoneViewers();
            if (!viewers.isEmpty()) {
                // Material nodes mutate item displays / drops at fixed map positions near spawn —
                // genuinely region-bound work, so it keeps its region hop.
                Location anchor = world.getSpawnLocation();
                this.scheduler.runAtLocation(anchor, () -> {
                    if (this.gameplayCoordinator != null && this.world != null) {
                        this.gameplayCoordinator.tickMaterialNodes(this.world, this.mapMeta, viewers);
                    }
                });
            }
            if (!viewers.isEmpty()) {
                extractVisualCycle++;
                // Coordination only: the visual/toxicity services dispatch per VIEWER on the
                // player's region and per BEAM on the entity's region internally. Funneling
                // this through the spawn-anchor region used to mutate zone displays owned by
                // OTHER regions and burst all per-viewer packet work onto the busiest region.
                this.gameplayCoordinator.tickExtractZones(
                        this,
                        viewers,
                        this::extractZonePlayerView,
                        true
                );
                if (state == BreachState.ACTIVE) {
                    this.gameplayCoordinator.tickToxicityCreep(this, viewers);
                } else if (state == BreachState.TOXIC) {
                    this.gameplayCoordinator.tickToxicityLethal(this, liveRaiders());
                }
            }
            if (previousState == BreachState.ACTIVE && extractZoneSchedule != null) {
                extractZoneSchedule.tickAlerts(BreachState.ACTIVE, previousRemaining, remainingSeconds, viewers, core);
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
                    BreachPlayerVitality.restore(player);
                    clearPlayerLocations(playerId);
                });
            }
        }
        if (gameplayCoordinator != null) {
            // Purge lootable bodies before world recycle so corpse map entries cannot outlive the instance.
            if (world != null && gameplayCoordinator.corpseService() != null) {
                gameplayCoordinator.corpseService().clearWorld(world);
            }
            // The world is about to be recycled (its entities/displays are removed on unload) and every player has
            // been teleported to the hub, so this teardown is best-effort. Guard it: a Folia off-region access
            // here must NOT abort beginReset before scheduleRecycleWorld runs, or the instance would never recycle.
            try {
                // World-scoped: the global resetBosses() wiped sibling breaches' mobs too.
                gameplayCoordinator.resetBosses(world);
                if (world != null) {
                    gameplayCoordinator.onWorldClosed(world);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("[Breach] World teardown for '" + worldName + "' raised "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage() + " (continuing with recycle).");
            }
        }
        // Any raider still disconnected when the raid ends failed to extract: tear down their stand-in and wipe the
        // escrow (no corpse — the world is recycling and is unreachable). Done before participants are cleared.
        if (!disconnectedPlayers.isEmpty()) {
            java.util.function.Consumer<UUID> handler = this.disconnectedResetHandler;
            for (UUID disconnectedId : new HashSet<>(disconnectedPlayers.keySet())) {
                if (handler != null) {
                    handler.accept(disconnectedId);
                } else if (core != null && core.playerInventoryManager() != null) {
                    core.playerInventoryManager().clearRaid(disconnectedId);
                }
            }
            disconnectedPlayers.clear();
        }
        java.util.function.Consumer<BreachInstance> resetHandler = this.raidResetHandler;
        if (resetHandler != null) {
            resetHandler.accept(this);
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.notifyInstanceReset(this);
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
        reservedSlots.set(0);
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
        toxicElapsedSeconds = 0;
        // A fresh Random per match: seeding with instanceId.hashCode() made recycled instances
        // re-roll the IDENTICAL close schedule every raid — one unlucky roll repeated forever.
        extractZoneSchedule = BreachExtractZoneSchedule.roll(mapMeta, configService, new java.util.Random());
        this.plugin.getLogger().info("[Breach] Extract close schedule for '" + instanceId + "': "
                + extractZoneSchedule.closeAtRemainingSeconds()
                + " (remaining-seconds thresholds; force close at " + extractZoneSchedule.forceCloseSeconds() + "s)");
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
        // Spawn scoring runs on the anchor region; mob threat comes from the cross-region-safe
        // spawn tracker, so candidates in other Folia regions still score their gunner patrols.
        this.scheduler.runAtLocation(regionAnchor, () -> {
            Location chosen = resolveSpawnLocation(player);
            // Ground-snapping reads blocks around the CHOSEN point, which may belong to a
            // different region than the anchor — hop there before snapping and teleporting.
            this.scheduler.runAtLocation(chosen, () -> {
                Location spawn = BreachSpawnSafety.snapToGround(targetWorld, chosen);
                Location settled = rememberPartySpawn(player.getUniqueId(), spawn);
                this.scheduler.runOnPlayer(player, () -> teleportIntoBreach(player, settled));
            });
        });
    }

    /** Parties deploy together: the first member's snapped spawn wins and later members reuse it. */
    private Location rememberPartySpawn(UUID playerId, Location spawn) {
        UUID partyId = partyIdFor(playerId);
        if (partyId == null) {
            return spawn;
        }
        partySpawnLocations.putIfAbsent(partyId, spawn.clone());
        return partySpawnLocations.get(partyId).clone();
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
                mapMeta,
                player.getUniqueId(),
                partyIdFor(player.getUniqueId()),
                this,
                gameplayCoordinator == null ? null : gameplayCoordinator.gunfireTracker(),
                gameplayCoordinator == null ? null : gameplayCoordinator.mobSpawnService(),
                this.scheduler,
                partySpawnLocations,
                this.plugin.getLogger()
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
        if (gameplayCoordinator != null) {
            gameplayCoordinator.refreshTabVisibility();
        }
    }

    private void showJoinCountdownTitle(Player player, int seconds) {
        Component subtitle = network.skypvp.extraction.text.ExtractionTexts.plain(
                player,
                "extraction.title.join_countdown_subtitle"
        );
        // Big pack-font digit slides in from the left, one frame per tick; each resend
        // replaces the previous title in place (fade-in zero) so the motion reads smoothly.
        int frames = network.skypvp.extraction.hud.BreachCountdownTitle.frameCount();
        for (int frame = 0; frame < frames; frame++) {
            Component title = network.skypvp.extraction.hud.BreachCountdownTitle.frame(seconds, frame);
            Runnable show = () -> network.skypvp.extraction.hud.ClientTitles.offer(
                    core,
                    player,
                    title,
                    subtitle,
                    0,
                    18,
                    4,
                    network.skypvp.paper.clientupdate.ClientUpdatePipeline.PRIORITY_FLASH
            );
            if (frame == 0) {
                show.run();
            } else {
                this.scheduler.runOnPlayerLater(player, show, frame);
            }
        }
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
        network.skypvp.extraction.hud.ClientTitles.offer(core, player, title, subtitle, 5, 60, 10);
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
            beginToxicPhase();
        }
    }

    private void beginToxicPhase() {
        if (state == BreachState.TOXIC) {
            return;
        }
        remainingSeconds = 0;
        toxicElapsedSeconds = 0;
        transition(BreachState.TOXIC);
        List<Player> viewers = extractZoneViewers();
        if (extractZoneSchedule != null) {
            extractZoneSchedule.broadcastToxicPhaseStart(viewers, core);
        } else {
            broadcastLocalized("extraction.toxic.phase_started");
        }
        if (gameplayCoordinator != null) {
            gameplayCoordinator.cancelAllExtracts(this);
        }
    }

    private void tickToxic() {
        toxicElapsedSeconds++;
        if (gameplayCoordinator != null) {
            gameplayCoordinator.applyToxicDamage(this, liveRaiders());
        }
        if (liveRaiderCount() <= 0 || toxicElapsedSeconds >= configService.toxicityMaxPhaseSeconds()) {
            broadcastLocalized("extraction.breach.cycle.ended");
            transition(BreachState.ENDING);
        }
    }

    private int liveRaiderCount() {
        return liveRaiders().size();
    }

    public List<Player> liveRaiders() {
        if (world == null) {
            return List.of();
        }
        List<Player> live = new ArrayList<>();
        for (UUID playerId : participants) {
            if (extractedPlayers.contains(playerId) || eliminatedPlayers.contains(playerId)) {
                continue;
            }
            if (joinCountdowns.containsKey(playerId) || isSpectating(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                continue;
            }
            live.add(player);
        }
        return List.copyOf(live);
    }

    private void tickEnding() {
        if (System.currentTimeMillis() - stateChangedAtMillis >= configService.resetDelaySeconds() * 1000L) {
            // World teardown (corpse purge, chunk-ticket release) wants the map's region thread;
            // the tick itself runs on the global heartbeat. beginReset's RESETTING guard makes
            // double-dispatch harmless.
            if (world != null) {
                this.scheduler.runAtLocation(world.getSpawnLocation(), this::beginReset);
            } else {
                beginReset();
            }
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
        return findExtractZoneId(location) != null;
    }

    public boolean isInOpenExtractZone(Location location) {
        if (location == null || extractZoneSchedule == null) {
            return isInExtractZone(location);
        }
        String zoneId = findExtractZoneId(location);
        if (zoneId == null) {
            return false;
        }
        // CLOSING_SOON must still extract (matches HUD / orange beacon state).
        return extractZoneSchedule.isZoneUsable(zoneId, state, remainingSeconds);
    }

    public String findExtractZoneIdForLocation(Location location) {
        return findExtractZoneId(location);
    }

    private String findExtractZoneId(Location location) {
        if (location == null || world == null || !world.getName().equals(location.getWorld().getName())) {
            return null;
        }
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            if (zone.contains(location.getX(), location.getY(), location.getZ())) {
                return zone.id();
            }
        }
        return null;
    }

    private ExtractZonePlayerView extractZonePlayerView(Player player) {
        if (player == null) {
            return ExtractZonePlayerView.defaults();
        }
        return new ExtractZonePlayerView(
                extractedPlayers.contains(player.getUniqueId()),
                isInOpenExtractZone(player.getLocation()),
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
