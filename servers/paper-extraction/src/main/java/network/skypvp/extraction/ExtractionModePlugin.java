package network.skypvp.extraction;

import network.skypvp.extraction.command.BreachCommand;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.hud.BreachHudProvider;
import network.skypvp.extraction.hud.BreachScoreboardData;
import network.skypvp.extraction.integration.BreachPlaceholderExpansion;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.integration.ExtractionWeaponLobbyGuard;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.integration.WeaponMechanicsHitscanService;
import network.skypvp.extraction.listener.BreachLootChestChunkListener;
import network.skypvp.extraction.listener.BreachLootChestListener;
import network.skypvp.extraction.listener.BreachListener;
import network.skypvp.extraction.listener.BreachVoidListener;
import network.skypvp.extraction.listener.BreachPlayerCorpseListener;
import network.skypvp.extraction.gameplay.BreachLeavePromptService;
import network.skypvp.extraction.listener.ExtractionInventoryQuitListener;
import network.skypvp.extraction.listener.ExtractionEnvironmentGuard;
import network.skypvp.extraction.listener.ExtractionLobbyListener;
import network.skypvp.extraction.listener.ExtractionRaidInventoryListener;
import network.skypvp.extraction.engine.BreachCapacityReporter;
import network.skypvp.extraction.integration.BreachHotbarActionExtension;
import network.skypvp.extraction.integration.BreachNetworkMenuAccess;
import network.skypvp.extraction.chat.ExtractionLocalChatScope;
import network.skypvp.paper.gamemode.api.LocalChatScope;
import network.skypvp.extraction.world.BreachWorldManager;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.BreachCapacityProvider;
import network.skypvp.paper.gamemode.api.CoreBehaviorProfile;
import network.skypvp.paper.gamemode.api.HotbarActionExtension;
import network.skypvp.paper.gamemode.api.NetworkMenuAccess;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.NetworkServerRole;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExtractionModePlugin extends JavaPlugin {

    private final AetherBreachMechanicCatalog mechanics = new AetherBreachMechanicCatalog();
    private final CoreBehaviorProfile behaviorProfile = new ExtractionCoreBehaviorProfile();

    private BreachConfigService configService;
    private BreachEngine breachEngine;
    private BreachCapacityReporter capacityReporter;
    private BreachHudProvider hudProvider;
    private BreachScoreboardData scoreboardData;
    private BreachHotbarActionExtension hotbarActionExtension;
    private BreachNetworkMenuAccess networkMenuAccess;
    private BreachPlaceholderExpansion placeholderExpansion;
    private ExtractionLocalChatScope localChatScope;
    private boolean breachBootstrapScheduled;
    private PaperCorePlugin coreForNametags;

    private static final String NAMETAG_HIDE_CONDITION_ID = "extraction_breach";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getServicesManager().register(CoreBehaviorProfile.class, behaviorProfile, this, ServicePriority.Normal);
        getLogger().info("Loaded mode: " + mechanics.modeKey() + " (" + mechanics.mechanics().size() + " classified mechanics)");

        if (getServer().getPluginManager().getPlugin("SkyPvPCore") instanceof PaperCorePlugin core) {
            core.serverTextBridge().registerCatalogPack("extraction", getClass().getClassLoader(), getLogger());
            bootstrapBreachSystems(core);
        } else {
            getLogger().warning("SkyPvPCore not found; Aether Breach systems disabled.");
        }
    }

    private void bootstrapBreachSystems(PaperCorePlugin core) {
        if (core.serverRole() != NetworkServerRole.EXTRACTION) {
            getLogger().info("Server role is " + core.serverRole() + "; skipping extraction breach systems.");
            return;
        }

        if (!core.worldStateService().isStartupReady()) {
            if (!breachBootstrapScheduled) {
                breachBootstrapScheduled = true;
                getLogger().warning("Extraction world not ready yet; retrying breach bootstrap in 1s.");
                core.platformScheduler().runGlobalLater(() -> {
                    breachBootstrapScheduled = false;
                    bootstrapBreachSystems(core);
                }, 20L);
            }
            return;
        }

        if (breachEngine != null) {
            return;
        }

        configService = new BreachConfigService(this);
        BreachWorldGuardBridge worldGuardBridge = new BreachWorldGuardBridge(this);
        WeaponMechanicsBridge weaponMechanicsBridge = new WeaponMechanicsBridge(this);
        MythicMobsBridge mythicMobsBridge = new MythicMobsBridge(this);
        BreachWorldManager worldManager = new BreachWorldManager(this, core, core.platformScheduler(), configService);
        BreachGameplayCoordinator gameplayCoordinator = new BreachGameplayCoordinator(
                this,
                core,
                core.platformScheduler(),
                configService,
                weaponMechanicsBridge,
                mythicMobsBridge
        );
        breachEngine = new BreachEngine(this, core.platformScheduler(), configService, worldManager, worldGuardBridge, gameplayCoordinator, core);
        gameplayCoordinator.bindLeavePrompt(new BreachLeavePromptService(core, breachEngine));
        capacityReporter = new BreachCapacityReporter(breachEngine, configService);
        getServer().getServicesManager().register(BreachCapacityProvider.class, capacityReporter, this, ServicePriority.Normal);
        scoreboardData = new BreachScoreboardData(core);
        scoreboardData.start();
        hudProvider = new BreachHudProvider(
                breachEngine,
                gameplayCoordinator.extractService(),
                scoreboardData,
                weaponMechanicsBridge
        );

        getServer().getServicesManager().register(HudProvider.class, hudProvider, this, ServicePriority.Normal);
        hotbarActionExtension = new BreachHotbarActionExtension(breachEngine);
        getServer().getServicesManager().register(HotbarActionExtension.class, hotbarActionExtension, this, ServicePriority.Normal);
        networkMenuAccess = new BreachNetworkMenuAccess(breachEngine);
        getServer().getServicesManager().register(NetworkMenuAccess.class, networkMenuAccess, this, ServicePriority.Normal);

        localChatScope = new ExtractionLocalChatScope();
        getServer().getServicesManager().register(LocalChatScope.class, localChatScope, this, ServicePriority.Normal);

        gameplayCoordinator.warmLootCaches();
        breachEngine.start();
        ExtractionWeaponLobbyGuard.register(this, breachEngine, weaponMechanicsBridge, gameplayCoordinator.gunfireTracker());
        WeaponMechanicsHitscanService.register(this, configService.hitscanSettings(), weaponMechanicsBridge, core.platformScheduler());

        PluginCommand breachCommand = getCommand("breach");
        if (breachCommand != null) {
            BreachCommand executor = new BreachCommand(breachEngine, configService);
            breachCommand.setExecutor(executor);
            breachCommand.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(new BreachListener(breachEngine, gameplayCoordinator.extractService()), this);
        getServer().getPluginManager().registerEvents(new BreachVoidListener(breachEngine), this);
        getServer().getPluginManager().registerEvents(new ExtractionEnvironmentGuard(weaponMechanicsBridge), this);
        getServer().getPluginManager().registerEvents(new ExtractionLobbyListener(breachEngine), this);
        getServer().getPluginManager().registerEvents(new BreachPlayerCorpseListener(gameplayCoordinator.corpseService()), this);
        getServer().getPluginManager().registerEvents(new ExtractionInventoryQuitListener(breachEngine, core), this);
        getServer().getPluginManager().registerEvents(new ExtractionRaidInventoryListener(core, breachEngine), this);
        if (configService.enhancedLootChests()) {
            getServer().getPluginManager().registerEvents(
                    new BreachLootChestListener(breachEngine, gameplayCoordinator.lootChestGuiService(), core, this),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new BreachLootChestChunkListener(breachEngine, gameplayCoordinator.lootService()),
                    this
            );
        }
        registerPlaceholderExpansion();

        if (core.nametagLibrary() != null) {
            // Nametags would give away positions in the raid; hide them while the player is inside a breach.
            // The library re-evaluates this every refresh tick, so leave/extract/eliminate transitions are covered.
            BreachEngine engine = breachEngine;
            core.nametagLibrary().registerHideCondition(
                    NAMETAG_HIDE_CONDITION_ID,
                    player -> engine.instanceFor(player).isPresent()
            );
            coreForNametags = core;
        }

        getLogger().info("[Breach] Aether Breach systems initialized.");
    }

    private void registerPlaceholderExpansion() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI is not installed; skypvp_breach expansion was not registered.");
            return;
        }
        placeholderExpansion = new BreachPlaceholderExpansion(breachEngine);
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion: skypvp_breach");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion: skypvp_breach");
        }
    }

    @Override
    public void onDisable() {
        if (coreForNametags != null && coreForNametags.nametagLibrary() != null) {
            coreForNametags.nametagLibrary().unregisterHideCondition(NAMETAG_HIDE_CONDITION_ID);
            coreForNametags = null;
        }
        if (breachEngine != null) {
            breachEngine.shutdown();
        }
        if (scoreboardData != null) {
            scoreboardData.stop();
        }
        if (hudProvider != null) {
            getServer().getServicesManager().unregister(HudProvider.class, hudProvider);
        }
        if (capacityReporter != null) {
            getServer().getServicesManager().unregister(BreachCapacityProvider.class, capacityReporter);
        }
        if (hotbarActionExtension != null) {
            getServer().getServicesManager().unregister(HotbarActionExtension.class, hotbarActionExtension);
        }
        if (networkMenuAccess != null) {
            getServer().getServicesManager().unregister(NetworkMenuAccess.class, networkMenuAccess);
        }
        if (localChatScope != null) {
            getServer().getServicesManager().unregister(LocalChatScope.class, localChatScope);
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        getServer().getServicesManager().unregister(CoreBehaviorProfile.class, behaviorProfile);
    }
}
