package network.skypvp.extraction.gameplay;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.loot.BreachLootChestAmbienceService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestDisplayService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestGuiService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestRegistry;
import network.skypvp.extraction.gameplay.BreachLeavePromptService;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.waypoint.Waypoint;
import network.skypvp.paper.waypoint.WaypointNavigatorService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachGameplayCoordinator {

    private final BreachLootService lootService;
    private final BreachBossService bossService;
    private final BreachMobSpawnService mobSpawnService;
    private final BreachMobChunkService mobChunkService;
    private BreachRuinsMobNametagService ruinsMobNametagService;
    private BreachRuinsRaiderAiService ruinsRaiderAiService;
    private BreachMaterialNodeService materialNodeService;
    private final BreachExtractZoneVisualService extractZoneVisualService;
    private final BreachExtractService extractService;
    private final BreachToxicityService toxicityService;
    private final BreachPlayerInventoryBridge inventoryBridge;
    private final BreachPlayerCorpseService corpseService;
    private final BreachSpectatorService spectatorService;
    private final BreachLootChestGuiService lootChestGuiService;
    private BreachLeavePromptService leavePromptService;
    private volatile RaidSessionListener raidSessionListener;
    private final BreachLootChestAmbienceService lootChestAmbienceService;
    private final BreachLootChestDisplayService lootChestDisplayService;
    private final BreachGunfireTracker gunfireTracker = new BreachGunfireTracker();
    private BreachCombatDebugService combatDebugService;
    private final JavaPlugin plugin;
    private final PaperCorePlugin core;
    private final ServerPlatform scheduler;
    private final BreachConfigService configService;
    private volatile BreachTabVisibilityService tabVisibilityService;

    public BreachGameplayCoordinator(
            JavaPlugin plugin,
            PaperCorePlugin core,
            ServerPlatform scheduler,
            BreachConfigService configService,
            WeaponMechanicsBridge weaponMechanicsBridge,
            MythicMobsBridge mythicMobsBridge
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.combatDebugService = new BreachCombatDebugService(core);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        BreachLootChestRegistry chestRegistry = new BreachLootChestRegistry();
        this.lootChestDisplayService = new BreachLootChestDisplayService(core, configService, chestRegistry);
        this.lootService = new BreachLootService(
                plugin,
                scheduler,
                configService,
                weaponMechanicsBridge,
                chestRegistry,
                this.lootChestDisplayService
        );
        this.bossService = new BreachBossService(plugin, scheduler, mythicMobsBridge);
        this.mobSpawnService = new BreachMobSpawnService(plugin, scheduler, mythicMobsBridge);
        this.mobChunkService = new BreachMobChunkService(plugin, scheduler);
        this.extractZoneVisualService = new BreachExtractZoneVisualService(plugin, configService, scheduler);
        this.extractService = new BreachExtractService(core, configService, scheduler);
        this.toxicityService = new BreachToxicityService(configService, scheduler, core);
        this.inventoryBridge = new BreachPlayerInventoryBridge(plugin, core);
        this.corpseService = new BreachPlayerCorpseService(plugin, scheduler, core);
        this.spectatorService = new BreachSpectatorService(scheduler, core);
        this.corpseService.setSpectatorCheck(this.spectatorService::isSpectating);
        this.lootChestGuiService = new BreachLootChestGuiService(
                plugin, core, configService, chestRegistry, this.lootChestDisplayService);
        this.lootChestAmbienceService = new BreachLootChestAmbienceService(
                configService,
                chestRegistry,
                this.lootChestDisplayService
        );
    }

    public void startLootChestAmbience(BreachEngine engine, JavaPlugin plugin, ServerPlatform scheduler) {
        this.lootChestAmbienceService.start(plugin, scheduler, engine);
    }

    public BreachLootChestDisplayService lootChestDisplayService() {
        return this.lootChestDisplayService;
    }

    public void startExtractTicker(JavaPlugin plugin, ServerPlatform scheduler, BreachEngine engine) {
        this.extractService.start(plugin, scheduler, engine);
    }

    public void shutdownExtractTicker() {
        this.extractService.shutdown();
    }

    public BreachExtractService extractService() {
        return this.extractService;
    }

    public BreachLootChestGuiService lootChestGuiService() {
        return this.lootChestGuiService;
    }

    public BreachLootService lootService() {
        return this.lootService;
    }

    public void onWorldReady(World world, BreachMapMeta mapMeta) {
        this.extractZoneVisualService.setupWorld(world, mapMeta);
        this.bossService.reset();
        this.mobSpawnService.reset();
        this.mobChunkService.retainMatchChunks(world, mapMeta);
        if (this.materialNodeService != null) {
            this.materialNodeService.resetWorld(world);
        }
    }

    public void onWorldClosed(World world) {
        this.extractZoneVisualService.teardownWorld(world);
        this.lootService.invalidateWorldCache(world);
        this.corpseService.clearWorld(world);
        this.mobChunkService.release(world);
        if (this.ruinsMobNametagService != null) {
            this.ruinsMobNametagService.purgeWorld(world);
        }
        if (this.ruinsRaiderAiService != null) {
            this.ruinsRaiderAiService.clearWorld(world);
        }
        this.gunfireTracker.clearWorld(world);
        if (this.materialNodeService != null) {
            this.materialNodeService.resetWorld(world);
        }
    }

    public void bindLeavePrompt(BreachLeavePromptService leavePromptService) {
        this.leavePromptService = leavePromptService;
    }

    public BreachLeavePromptService leavePromptService() {
        return this.leavePromptService;
    }

    public void bindRaidSessionListener(RaidSessionListener listener) {
        this.raidSessionListener = listener;
    }

    public void bindRuinsServices(
            BreachRuinsMobNametagService nametagService,
            BreachRuinsRaiderAiService raiderAiService
    ) {
        this.ruinsMobNametagService = nametagService;
        this.ruinsRaiderAiService = raiderAiService;
    }

    public void bindMaterialNodes(
            BreachEngine engine,
            network.skypvp.extraction.crafting.CraftingConfigService craftingConfig,
            network.skypvp.extraction.gameplay.scrapper.ScrapperService scrapperService
    ) {
        this.materialNodeService = new BreachMaterialNodeService(
                this.plugin,
                this.core,
                craftingConfig,
                engine,
                scrapperService
        );
    }

    public void notifyRaidSessionStarted(Player player) {
        if (this.ruinsRaiderAiService != null && player != null && player.getWorld() != null) {
            this.ruinsRaiderAiService.reconcileWorld(player.getWorld());
        }
        if (this.raidSessionListener != null && player != null) {
            this.raidSessionListener.onPlayerRaidSessionStarted(player);
        }
    }

    public void notifyRaidSessionEnded(Player player) {
        if (this.raidSessionListener != null && player != null) {
            this.raidSessionListener.onPlayerRaidSessionEnded(player);
        }
    }

    public void notifyInstanceReset(BreachInstance instance) {
        if (this.raidSessionListener != null && instance != null) {
            this.raidSessionListener.onInstanceReset(instance);
        }
    }

    public void notifyPlayerRemovedFromRaid(UUID playerId, World world, Location lastLocation, boolean inspectBodies) {
        if (this.ruinsRaiderAiService != null) {
            this.ruinsRaiderAiService.onPlayerRemovedFromRaid(playerId, world, lastLocation, inspectBodies);
        }
    }

    public BreachPlayerCorpseService corpseService() {
        return this.corpseService;
    }

    public BreachSpectatorService spectatorService() {
        return this.spectatorService;
    }

    public void bindTabVisibility(BreachEngine engine) {
        this.tabVisibilityService = new BreachTabVisibilityService(
                this.core,
                this.scheduler,
                engine,
                this.spectatorService
        );
    }

    public void refreshTabVisibility() {
        if (this.tabVisibilityService != null) {
            this.tabVisibilityService.reconcileAll();
        }
    }

    /** Reconciles tab visibility and immediately refreshes tab-board/scoreboard HUD for affected viewers. */
    public void refreshHudAfterElimination(Player eliminated) {
        refreshTabVisibility();
        if (this.core == null) {
            return;
        }
        if (eliminated != null && eliminated.isOnline()) {
            this.core.tabListService().refreshPlayer(eliminated);
            this.core.scoreboardService().refreshPlayer(eliminated);
        }
        this.scheduler.runGlobalLater(() -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == null || !online.isOnline()) {
                    continue;
                }
                this.core.tabListService().refreshPlayer(online);
                this.core.scoreboardService().refreshPlayer(online);
            }
        }, 2L);
    }

    public void onMatchStarted(World world, BreachMapMeta mapMeta, String templateId) {
        this.lootService.populateMapLoot(world, mapMeta, templateId);
        this.bossService.reset();
        this.mobSpawnService.reset();
        this.mobChunkService.retainMatchChunks(world, mapMeta);
        if (this.materialNodeService != null) {
            this.materialNodeService.resetWorld(world);
        }
    }

    public void warmLootCaches() {
        this.lootService.warmTemplateCaches();
    }

    /**
     * Finishes loot rolls and initial mob spawns for a freshly activated standby breach before publishing a joinable
     * heartbeat.
     */
    public void warmStandbyInstance(World world, BreachMapMeta mapMeta, String templateId, Runnable onComplete) {
        if (world == null || mapMeta == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        Runnable finish = () -> {
            this.mobSpawnService.warmInitialSpawns(world, mapMeta);
            if (onComplete != null) {
                this.scheduler.runGlobalLater(onComplete, 40L);
            }
        };
        if (this.configService.enhancedLootChests()) {
            this.lootService.forceActivatePlannedLoot(world, finish);
            return;
        }
        this.lootService.populateMapLoot(world, mapMeta, templateId);
        finish.run();
    }

    public PaperCorePlugin core() {
        return this.inventoryBridge.core();
    }

    public void tickBosses(World world, BreachMapMeta mapMeta, int elapsedSeconds) {
        this.bossService.tick(world, mapMeta, elapsedSeconds);
        this.mobSpawnService.tick(world, mapMeta, elapsedSeconds);
    }

    public void tickMaterialNodes(World world, BreachMapMeta mapMeta, List<Player> viewers) {
        if (this.materialNodeService != null) {
            this.materialNodeService.tick(world, mapMeta, viewers);
        }
    }

    public void clearExtractZoneVisualsForPlayer(Player player, World world) {
        this.extractZoneVisualService.clearPlayer(player, world);
        clearExtractWaypoints(player);
    }

    public void tickExtractZones(
            BreachInstance instance,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles
    ) {
        if (instance == null || instance.world() == null) {
            return;
        }
        this.extractZoneVisualService.tickWorld(
                instance,
                viewers,
                playerViewLookup,
                includeParticles
        );
        this.syncExtractWaypoints(instance, viewers, playerViewLookup);
    }

    /** Guides live raiders to open extract zones via the core {@link WaypointNavigatorService}. */
    private void syncExtractWaypoints(
            BreachInstance instance,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup
    ) {
        PaperCorePlugin paper = this.core();
        WaypointNavigatorService navigator = paper == null ? null : paper.waypointNavigator();
        if (navigator == null || instance == null || instance.mapMeta() == null || viewers == null) {
            return;
        }
        BreachExtractZoneSchedule schedule = instance.extractZoneSchedule();
        BreachState state = instance.state();
        int remaining = instance.remainingSeconds();
        for (Player viewer : viewers) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            ExtractZonePlayerView view = playerViewLookup != null
                    ? playerViewLookup.apply(viewer)
                    : ExtractZonePlayerView.defaults();
            if (view.extracted() || this.spectatorService.isSpectating(viewer)) {
                clearExtractWaypoints(viewer);
                continue;
            }
            for (BreachMapMeta.ExtractZone zone : instance.mapMeta().extractZones()) {
                String waypointId = "extract:" + zone.id();
                BreachExtractZoneVisualService.ExtractAvailability availability = schedule != null
                        ? schedule.zoneAvailability(zone.id(), state, remaining)
                        : BreachExtractZoneVisualService.ExtractAvailability.OPEN;
                if (availability == BreachExtractZoneVisualService.ExtractAvailability.CLOSED) {
                    navigator.clear(viewer, waypointId);
                    continue;
                }
                Color color = availability == BreachExtractZoneVisualService.ExtractAvailability.CLOSING_SOON
                        ? Color.fromRGB(255, 170, 30)
                        : Color.fromRGB(30, 255, 80);
                // Re-issue when the availability tier flips (open -> closing soon) so the
                // marker/beam color and label actually update; skipping while navigating
                // used to freeze the waypoint on its spawn-time color forever.
                java.util.Optional<Waypoint> existing = navigator.waypoint(viewer.getUniqueId(), waypointId);
                if (existing.isPresent() && color.equals(existing.get().color())) {
                    continue;
                }
                String label = availability == BreachExtractZoneVisualService.ExtractAvailability.CLOSING_SOON
                        ? "Extract · " + zone.id() + " <gold>closing soon</gold>"
                        : "Extract · " + zone.id();
                Location target = new Location(
                        instance.world(),
                        zone.centerX(),
                        zone.centerY(),
                        zone.centerZ()
                );
                navigator.navigate(
                        viewer,
                        Waypoint.of(waypointId, target, label, color, 0.0D)
                                .withMarker(network.skypvp.paper.waypoint.WaypointMarker.octagon(
                                        color, "<white>▲</white>"))
                );
            }
        }
    }

    private void clearExtractWaypoints(Player player) {
        PaperCorePlugin paper = this.core();
        WaypointNavigatorService navigator = paper == null ? null : paper.waypointNavigator();
        if (navigator == null || player == null) {
            return;
        }
        navigator.clearByPrefix(player, "extract:");
    }

    public void tickExtractZones(
            World world,
            BreachMapMeta mapMeta,
            BreachState state,
            int remainingSeconds,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup,
            boolean includeParticles
    ) {
        this.extractZoneVisualService.tickWorld(world, state, remainingSeconds, viewers, playerViewLookup, includeParticles);
    }

    public void tickExtractZones(
            World world,
            BreachMapMeta mapMeta,
            BreachState state,
            int remainingSeconds,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup
    ) {
        this.tickExtractZones(world, mapMeta, state, remainingSeconds, viewers, playerViewLookup, true);
    }

    public void refreshExtractZoneBeacons(
            World world,
            BreachState state,
            int remainingSeconds,
            List<Player> viewers,
            Function<Player, ExtractZonePlayerView> playerViewLookup
    ) {
        this.extractZoneVisualService.tickWorld(world, state, remainingSeconds, viewers, playerViewLookup, false);
    }

    /** Full reset — plugin shutdown only. Per-instance recycles must use the World overload. */
    public void resetBosses() {
        this.bossService.reset();
        this.mobSpawnService.reset();
    }

    /**
     * Per-instance reset: clears only {@code world}'s spawn/boss bookkeeping and despawns
     * only its tracked mobs. The global reset used to wipe every sibling breach's mob
     * tracking (and despawn their mobs) whenever ONE instance recycled — with multiple
     * concurrent breaches that alternately starved and over-spawned the survivors.
     */
    public void resetBosses(World world) {
        if (world == null) {
            resetBosses();
            return;
        }
        this.bossService.clearWorld(world);
        this.mobSpawnService.clearWorld(world);
    }

    public BreachPlayerInventoryBridge inventoryBridge() {
        return this.inventoryBridge;
    }

    public BreachGunfireTracker gunfireTracker() {
        return this.gunfireTracker;
    }

    public BreachMobSpawnService mobSpawnService() {
        return this.mobSpawnService;
    }

    public BreachCombatDebugService combatDebugService() {
        return this.combatDebugService;
    }

    public BreachMobChunkService mobChunkService() {
        return this.mobChunkService;
    }

    public void tickToxicityCreep(BreachInstance instance, List<Player> viewers) {
        this.toxicityService.tickCreep(instance, viewers);
    }

    public void tickToxicityLethal(BreachInstance instance, List<Player> liveRaiders) {
        this.toxicityService.tickLethal(instance, liveRaiders);
    }

    public void applyToxicDamage(BreachInstance instance, List<Player> liveRaiders) {
        if (instance == null || liveRaiders == null || liveRaiders.isEmpty()) {
            return;
        }
        double damage = this.toxicityService.damageAmount();
        for (Player player : liveRaiders) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            this.scheduler.runOnPlayer(player, () -> {
                if (!player.isOnline() || player.getHealth() <= 0.0D) {
                    return;
                }
                player.damage(damage);
            });
        }
    }

    public void cancelAllExtracts(BreachInstance instance) {
        if (instance == null) {
            return;
        }
        for (UUID playerId : instance.participantsSnapshot()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                boolean wasExtracting = this.extractService.isExtracting(player);
                this.extractService.clearPlayer(player);
                if (wasExtracting) {
                    ExtractFeedback.cancelled(
                            this.core,
                            player,
                            "extraction.title.extract_cancelled_zone_closed"
                    );
                }
            }
        }
    }
}