package network.skypvp.extraction.gameplay;

import java.util.List;
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
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachGameplayCoordinator {

    private final BreachLootService lootService;
    private final BreachBossService bossService;
    private final BreachExtractZoneVisualService extractZoneVisualService;
    private final BreachExtractService extractService;
    private final BreachPlayerInventoryBridge inventoryBridge;
    private final BreachPlayerCorpseService corpseService;
    private final BreachSpectatorService spectatorService;
    private final BreachLootChestGuiService lootChestGuiService;
    private BreachLeavePromptService leavePromptService;
    private final BreachLootChestAmbienceService lootChestAmbienceService;
    private final BreachLootChestDisplayService lootChestDisplayService;
    private final BreachGunfireTracker gunfireTracker = new BreachGunfireTracker();

    public BreachGameplayCoordinator(
            JavaPlugin plugin,
            PaperCorePlugin core,
            ServerPlatform scheduler,
            BreachConfigService configService,
            WeaponMechanicsBridge weaponMechanicsBridge,
            MythicMobsBridge mythicMobsBridge
    ) {
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
        this.extractZoneVisualService = new BreachExtractZoneVisualService(plugin, configService);
        this.extractService = new BreachExtractService(configService, scheduler);
        this.inventoryBridge = new BreachPlayerInventoryBridge(plugin, core);
        this.corpseService = new BreachPlayerCorpseService(plugin, scheduler, core);
        this.spectatorService = new BreachSpectatorService(scheduler, core);
        this.corpseService.setSpectatorCheck(this.spectatorService::isSpectating);
        this.lootChestGuiService = new BreachLootChestGuiService(plugin, configService, chestRegistry, this.lootChestDisplayService);
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
    }

    public void onWorldClosed(World world) {
        this.extractZoneVisualService.teardownWorld(world);
        this.lootService.invalidateWorldCache(world);
        this.corpseService.clearWorld(world);
    }

    public void bindLeavePrompt(BreachLeavePromptService leavePromptService) {
        this.leavePromptService = leavePromptService;
    }

    public BreachLeavePromptService leavePromptService() {
        return this.leavePromptService;
    }

    public BreachPlayerCorpseService corpseService() {
        return this.corpseService;
    }

    public BreachSpectatorService spectatorService() {
        return this.spectatorService;
    }

    public void onMatchStarted(World world, BreachMapMeta mapMeta, String templateId) {
        this.lootService.populateMapLoot(world, mapMeta, templateId);
        this.bossService.reset();
    }

    public void warmLootCaches() {
        this.lootService.warmTemplateCaches();
    }

    public PaperCorePlugin core() {
        return this.inventoryBridge.core();
    }

    public void tickBosses(World world, BreachMapMeta mapMeta, int elapsedSeconds) {
        this.bossService.tick(world, mapMeta, elapsedSeconds);
    }

    public void clearExtractZoneVisualsForPlayer(Player player, World world) {
        this.extractZoneVisualService.clearPlayer(player, world);
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

    public void resetBosses() {
        this.bossService.reset();
    }

    public BreachPlayerInventoryBridge inventoryBridge() {
        return this.inventoryBridge;
    }

    public BreachGunfireTracker gunfireTracker() {
        return this.gunfireTracker;
    }
}