package network.skypvp.extraction;

import network.skypvp.extraction.command.BreachCommand;
import network.skypvp.extraction.command.BreachItemsCommand;
import network.skypvp.extraction.command.CombatCommand;
import network.skypvp.extraction.command.CraftingReloadCommand;
import network.skypvp.extraction.command.ExtractionHubCommand;
import network.skypvp.extraction.command.ScrapperCommand;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.hud.BreachHudProvider;
import network.skypvp.extraction.hud.BreachScoreboardData;
import network.skypvp.paper.service.PartyScoreboardData;
import network.skypvp.extraction.integration.BreachPlaceholderExpansion;
import network.skypvp.extraction.integration.BreachProxyRouteListener;
import network.skypvp.extraction.integration.BreachWorldGuardBridge;
import network.skypvp.extraction.integration.LibsDisguisesBridge;
import network.skypvp.extraction.integration.MythicMobsBridge;
import network.skypvp.extraction.integration.ExtractionWeaponLobbyGuard;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.integration.WeaponMechanicsCombatBridge;
import network.skypvp.paper.integration.LivingEntitySprintBridge;
import network.skypvp.extraction.integration.WeaponMechanicsHitscanService;
import network.skypvp.extraction.listener.BreachArrivalListener;
import network.skypvp.extraction.listener.BreachLootChestChunkListener;
import network.skypvp.extraction.listener.BreachLootChestListener;
import network.skypvp.extraction.listener.BreachRuinsGunnerViewerListener;
import network.skypvp.extraction.listener.BreachListener;
import network.skypvp.extraction.gameplay.RaiderGunnerKeys;
import network.skypvp.extraction.gameplay.BreachRuinsRaiderAiService;
import network.skypvp.extraction.gameplay.BreachRuinsMobNametagService;
import network.skypvp.extraction.gameplay.BreachRuinsMobService;
import network.skypvp.extraction.gameplay.BreachStaminaSprintBridge;
import network.skypvp.extraction.gameplay.BreachStaminaService;
import network.skypvp.extraction.gameplay.ExtractionLootFactory;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.BlackMarketConfigService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialLoreContributor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.crafting.MedicShopConfigService;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService;
import network.skypvp.extraction.stash.MaterialStashTierConfigService;
import network.skypvp.extraction.hub.ExtractionHubStationCatalog;
import network.skypvp.extraction.gui.HubEconomyService;
import network.skypvp.extraction.crafting.ItemConfigService;
import network.skypvp.extraction.listener.BlueprintReceiptListener;
import network.skypvp.extraction.listener.ExtractionCraftingProgressListener;
import network.skypvp.extraction.integration.WeaponCrossbowPosePacketService;
import network.skypvp.extraction.listener.BreachStaminaListener;
import network.skypvp.extraction.listener.MedicConsumableListener;
import network.skypvp.extraction.listener.ShieldRechargerListener;
import network.skypvp.extraction.listener.ShieldRepairKitListener;
import network.skypvp.extraction.listener.BreachVoidListener;
import network.skypvp.extraction.listener.BreachPlayerCorpseListener;
import network.skypvp.extraction.gameplay.BreachLeavePromptService;
import network.skypvp.extraction.gameplay.scrapper.ScrapperCollectTask;
import network.skypvp.extraction.gameplay.scrapper.ScrapperLifecycleListener;
import network.skypvp.extraction.gameplay.scrapper.ScrapperProgressRepository;
import network.skypvp.extraction.gameplay.scrapper.ScrapperService;
import network.skypvp.extraction.gameplay.scrapper.ScrapperTierConfigService;
import network.skypvp.extraction.questdialogue.ExtractionQuestDialogueBridge;
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
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.MedicHealService;
import network.skypvp.extraction.item.ShieldRechargeService;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.item.api.CustomItemProvider;
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
    private PartyScoreboardData partyScoreboardData;
    private BreachHotbarActionExtension hotbarActionExtension;
    private BreachNetworkMenuAccess networkMenuAccess;
    private BreachPlaceholderExpansion placeholderExpansion;
    private ExtractionLocalChatScope localChatScope;
    private ExtractionCustomItemProvider customItemProvider;
    private ShieldRechargeService shieldRechargeService;
    private MedicHealService medicHealService;
    private BreachStaminaService staminaService;
    private BreachStaminaSprintBridge staminaSprintBridge;
    private BreachStaminaListener staminaListener;
    private BreachRuinsRaiderAiService ruinsRaiderAiService;
    private BreachRuinsMobNametagService ruinsMobNametagService;
    private CraftingConfigService craftingConfig;
    private CraftingMaterialService craftingMaterials;
    private network.skypvp.extraction.stash.MaterialStashGuiService materialStashGui;
    private ScrapperService scrapperService;
    private ScrapperTierConfigService scrapperTierConfig;
    private ScrapperProgressRepository scrapperProgressRepository;
    private ExtractionQuestDialogueBridge questDialogueBridge;
    private ScrapperCollectTask scrapperCollectTask;
    private MaterialStashTierConfigService stashTierConfig;
    private BlueprintDiscoveryService blueprintDiscovery;
    private BlackMarketConfigService blackMarketConfig;
    private ItemConfigService itemConfigService;
    private MedicShopConfigService medicShopConfig;
    private MaterialBreakdownConfigService materialBreakdownConfig;
    private ExtractionHubStationCatalog hubStationCatalog;
    private HubEconomyService hubEconomyService;
    private WeaponMechanicsBridge weaponMechanicsBridge;
    private WeaponCrossbowPosePacketService weaponCrossbowPosePacketService;
    private BreachGameplayCoordinator gameplayCoordinator;
    private network.skypvp.paper.platform.PlatformTask staminaTickTask;
    private network.skypvp.paper.platform.PlatformTask ruinsRaiderAiTickTask;
    private network.skypvp.paper.platform.PlatformTask shieldFlashTask;
    private network.skypvp.extraction.backpack.BackpackService backpackService;
    private boolean breachBootstrapScheduled;
    private PaperCorePlugin coreForNametags;
    private network.skypvp.extraction.integration.BreachPartyQueueDeploySubscriber partyQueueDeploySubscriber;

    private static final String NAMETAG_HIDE_CONDITION_ID = "extraction_breach";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getServicesManager().register(CoreBehaviorProfile.class, behaviorProfile, this, ServicePriority.Normal);
        getLogger().info("Loaded mode: " + mechanics.modeKey() + " (" + mechanics.mechanics().size() + " classified mechanics)");

        if (getServer().getPluginManager().getPlugin("SkyPvPCore") instanceof PaperCorePlugin core) {
            core.serverTextBridge().registerCatalogPack("extraction", getClass().getClassLoader(), getLogger());
            try {
                bootstrapBreachSystems(core);
            } catch (Exception ex) {
                getLogger().log(java.util.logging.Level.SEVERE,
                        "Breach bootstrap failed; extraction stays not-joinable until a retry succeeds", ex);
                scheduleBreachBootstrapRetry(core, 100L);
            }
        } else {
            getLogger().warning("SkyPvPCore not found; Aether Breach systems disabled.");
        }
    }

    private void scheduleBreachBootstrapRetry(PaperCorePlugin core, long delayTicks) {
        if (breachBootstrapScheduled || breachEngine != null || core == null || core.platformScheduler() == null) {
            return;
        }
        breachBootstrapScheduled = true;
        core.platformScheduler().runGlobalLater(() -> {
            breachBootstrapScheduled = false;
            if (breachEngine != null) {
                return;
            }
            try {
                bootstrapBreachSystems(core);
            } catch (Exception retryEx) {
                getLogger().log(java.util.logging.Level.SEVERE, "Breach bootstrap retry failed", retryEx);
                scheduleBreachBootstrapRetry(core, 100L);
            }
        }, Math.max(20L, delayTicks));
    }

    private void bootstrapBreachSystems(PaperCorePlugin core) {
        if (core.serverRole() != NetworkServerRole.EXTRACTION) {
            getLogger().info("Server role is " + core.serverRole() + "; skipping extraction breach systems.");
            releaseModeReadyHold(core);
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
            releaseModeReadyHold(core);
            return;
        }

        configService = new BreachConfigService(this);
        network.skypvp.extraction.gameplay.BreachPlayerVitality.bind(core.playerHealthService());
        BreachWorldGuardBridge worldGuardBridge = new BreachWorldGuardBridge(this);
        WeaponMechanicsBridge weaponMechanicsBridge = new WeaponMechanicsBridge(this);
        this.weaponMechanicsBridge = weaponMechanicsBridge;
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
        this.gameplayCoordinator = gameplayCoordinator;
        breachEngine = new BreachEngine(this, core.platformScheduler(), configService, worldManager, worldGuardBridge, gameplayCoordinator, core);
        gameplayCoordinator.bindLeavePrompt(new BreachLeavePromptService(core, breachEngine));
        capacityReporter = new BreachCapacityReporter(breachEngine, configService);
        getServer().getServicesManager().register(BreachCapacityProvider.class, capacityReporter, this, ServicePriority.Normal);
        scoreboardData = new BreachScoreboardData(core);
        scoreboardData.start();
        partyScoreboardData = new PartyScoreboardData(core);
        partyScoreboardData.setDisconnectedPredicate(breachEngine::isDisconnectedInRaid);
        partyScoreboardData.setDisconnectedSinceSupplier(breachEngine::disconnectedSinceInRaid);
        partyScoreboardData.start();
        staminaService = new BreachStaminaService(core, configService);
        staminaSprintBridge = new BreachStaminaSprintBridge();
        staminaService.bindSprintBridge(staminaSprintBridge);
        craftingConfig = new CraftingConfigService(this, getLogger());
        scrapperTierConfig = new ScrapperTierConfigService(this, getLogger());
        scrapperProgressRepository = new ScrapperProgressRepository(core.extractionInventoryRepository(), scrapperTierConfig);
        scrapperService = new ScrapperService(core, craftingConfig, breachEngine, scrapperTierConfig, scrapperProgressRepository);
        ScrapperLifecycleListener.bind(breachEngine, scrapperService, core);
        gameplayCoordinator.bindMaterialNodes(breachEngine, craftingConfig, scrapperService);
        CraftingMaterialLoreContributor.bind(craftingConfig);
        stashTierConfig = new MaterialStashTierConfigService(this, getLogger());
        craftingMaterials = new CraftingMaterialService(core, craftingConfig, stashTierConfig, getLogger());
        if (core.questDialogueService() != null && core.asyncDbExecutor() != null) {
            network.skypvp.extraction.questdialogue.QuestProgressRepository questProgress =
                    new network.skypvp.extraction.questdialogue.QuestProgressRepository(core.asyncDbExecutor());
            questProgress.ensureSchema();
            questDialogueBridge = new ExtractionQuestDialogueBridge(
                    core,
                    breachEngine,
                    craftingConfig,
                    core.questDialogueService(),
                    scrapperService,
                    scrapperProgressRepository,
                    scrapperTierConfig,
                    craftingMaterials,
                    new network.skypvp.extraction.questdialogue.QuestConfigService(this),
                    questProgress
            );
            core.bindQuestDialogueBridge(questDialogueBridge);
            core.questDialogueService().bindActionExecutor(questDialogueBridge);
            if (core.questSignals() != null) {
                core.questSignals().register(new network.skypvp.extraction.questdialogue.ScrapperQuestSignalProvider(
                        core, scrapperService));
                core.questSignals().register(new network.skypvp.extraction.questdialogue.PilotQuestSignalProvider(
                        core, questProgress));
            }
        } else if (core.questDialogueService() != null) {
            getLogger().warning("[Quests] asyncDbExecutor unavailable — quest dialogue bridge not bound.");
        }
        blueprintDiscovery = new BlueprintDiscoveryService(core.extractionCraftingRepository(), getLogger(), craftingConfig);
        hubEconomyService = new HubEconomyService(core);
        materialStashGui = new network.skypvp.extraction.stash.MaterialStashGuiService(
                core, craftingMaterials, stashTierConfig, hubEconomyService);
        craftingMaterials.bindStashGui(materialStashGui);
        blackMarketConfig = new BlackMarketConfigService(this, getLogger());
        itemConfigService = new ItemConfigService(this, getLogger());
        medicShopConfig = new MedicShopConfigService(this, getLogger());
        materialBreakdownConfig = new MaterialBreakdownConfigService(this, getLogger());
        hubStationCatalog = new ExtractionHubStationCatalog(this, getLogger());
        ItemConfigOverrides.bind(itemConfigService);
        ItemConfigOverrides.bindMedicShop(medicShopConfig);
        gameplayCoordinator.lootService().bindExtractionLoot(new ExtractionLootFactory(core, craftingConfig));
        hudProvider = new BreachHudProvider(
                breachEngine,
                gameplayCoordinator.extractService(),
                scoreboardData,
                partyScoreboardData,
                weaponMechanicsBridge,
                staminaService,
                core.rankService(),
                core.chatFormatService()
        );

        getServer().getServicesManager().register(HudProvider.class, hudProvider, this, ServicePriority.Normal);
        hotbarActionExtension = new BreachHotbarActionExtension(breachEngine);
        getServer().getServicesManager().register(HotbarActionExtension.class, hotbarActionExtension, this, ServicePriority.Normal);
        networkMenuAccess = new BreachNetworkMenuAccess(breachEngine);
        getServer().getServicesManager().register(NetworkMenuAccess.class, networkMenuAccess, this, ServicePriority.Normal);

        localChatScope = new ExtractionLocalChatScope();
        getServer().getServicesManager().register(LocalChatScope.class, localChatScope, this, ServicePriority.Normal);

        customItemProvider = new ExtractionCustomItemProvider();
        customItemProvider.register(this, core.customItemService());
        getServer().getServicesManager().register(CustomItemProvider.class, customItemProvider, this, ServicePriority.Normal);
        // Modernizes stored stacks (vault/stash/inventories) whose display material or
        // item model changed since they were created.
        core.customItemService().registerReconciler(
                new network.skypvp.extraction.item.ExtractionItemPresentationReconciler(
                        core.customItemService(), craftingConfig));
        getLogger().info("[Breach] Custom items registered ("
                + customItemProvider.definitions().size()
                + " types; extraction/infuse_chestplate ready).");

        this.backpackService = new network.skypvp.extraction.backpack.BackpackService(core, this);
        PluginCommand breachItemsCommand = getCommand("breachitems");
        if (breachItemsCommand != null) {
            BreachItemsCommand itemsExecutor = new BreachItemsCommand(core, customItemProvider, craftingConfig, backpackService);
            breachItemsCommand.setExecutor(itemsExecutor);
            breachItemsCommand.setTabCompleter(itemsExecutor);
        }

        PluginCommand combatCommand = getCommand("combat");
        if (combatCommand != null) {
            CombatCommand combatExecutor = new CombatCommand(
                    breachEngine,
                    gameplayCoordinator.extractService(),
                    core,
                    gameplayCoordinator.combatDebugService()
            );
            combatCommand.setExecutor(combatExecutor);
            combatCommand.setTabCompleter(combatExecutor);
        }

        gameplayCoordinator.warmLootCaches();
        RaiderGunnerKeys.register(this);
        breachEngine.start();
        BreachRuinsMobService ruinsMobService = new BreachRuinsMobService(core, breachEngine, craftingConfig, getLogger());
        BreachRuinsMobNametagService ruinsMobNametagService = new BreachRuinsMobNametagService(this, core);
        WeaponMechanicsCombatBridge weaponCombatBridge = new WeaponMechanicsCombatBridge(this, weaponMechanicsBridge);
        weaponCombatBridge.bindOwnGunfire(core.platformScheduler(), configService.hitscanSettings());
        LivingEntitySprintBridge sprintBridge = new LivingEntitySprintBridge(this);
        ruinsRaiderAiService = new BreachRuinsRaiderAiService(
                core,
                breachEngine,
                gameplayCoordinator.mobChunkService(),
                weaponCombatBridge,
                sprintBridge,
                gameplayCoordinator.gunfireTracker()
        );
        ruinsMobService.bindRaiderAiService(ruinsRaiderAiService);
        ruinsMobService.bindNametagService(ruinsMobNametagService);
        ruinsMobService.bindCorpseService(gameplayCoordinator.corpseService());
        ruinsRaiderAiService.bindNametagService(ruinsMobNametagService);
        this.ruinsMobNametagService = ruinsMobNametagService;
        gameplayCoordinator.bindRuinsServices(ruinsMobNametagService, ruinsRaiderAiService);
        getServer().getPluginManager().registerEvents(ruinsMobService, this);
        getServer().getPluginManager().registerEvents(ruinsRaiderAiService, this);
        getServer().getPluginManager().registerEvents(ruinsMobNametagService, this);
        getServer().getPluginManager().registerEvents(
                new BreachRuinsGunnerViewerListener(core, breachEngine, ruinsRaiderAiService, ruinsMobNametagService),
                this
        );
        startRuinsRaiderAiTicker(core);
        this.partyQueueDeploySubscriber = network.skypvp.extraction.integration.BreachPartyQueueDeploySubscriber.create(
                core, breachEngine, getLogger());
        if (this.partyQueueDeploySubscriber != null) {
            this.partyQueueDeploySubscriber.start();
        }
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                "skypvp:route",
                new BreachProxyRouteListener(breachEngine, getLogger())
        );
        ExtractionWeaponLobbyGuard.register(this, breachEngine, weaponMechanicsBridge, gameplayCoordinator.gunfireTracker());
        WeaponMechanicsHitscanService.register(this, configService.hitscanSettings(), weaponMechanicsBridge, core.platformScheduler());
        network.skypvp.extraction.integration.PlayerFullAutoService.register(this, core, weaponMechanicsBridge);

        PluginCommand breachCommand = getCommand("breach");
        if (breachCommand != null) {
            BreachCommand executor = new BreachCommand(breachEngine, configService);
            breachCommand.setExecutor(executor);
            breachCommand.setTabCompleter(executor);
        }

        network.skypvp.extraction.gameplay.BreachHitMarkerService hitMarkerService =
                new network.skypvp.extraction.gameplay.BreachHitMarkerService();
        hitMarkerService.registerWeaponMechanics(this);
        network.skypvp.extraction.gameplay.RuinsGunnerDamageService ruinsGunnerDamage =
                new network.skypvp.extraction.gameplay.RuinsGunnerDamageService(this);
        ruinsGunnerDamage.registerWeaponMechanics();
        network.skypvp.extraction.gameplay.BreachDamageIndicatorService damageIndicatorService =
                new network.skypvp.extraction.gameplay.BreachDamageIndicatorService(this, core);
        getServer().getPluginManager().registerEvents(
                new BreachListener(
                        breachEngine,
                        gameplayCoordinator.extractService(),
                        gameplayCoordinator.combatDebugService(),
                        hitMarkerService,
                        damageIndicatorService
                ),
                this
        );

        PluginCommand infuseCommand = getCommand("infuse");
        if (infuseCommand != null) {
            infuseCommand.setExecutor(new network.skypvp.extraction.command.InfuseMenuCommand(core, breachEngine));
        }

        PluginCommand armoryCommand = getCommand("armory");
        if (armoryCommand != null) {
            armoryCommand.setExecutor(new network.skypvp.extraction.command.ArmoryMenuCommand(
                    core, breachEngine, craftingMaterials, hubEconomyService));
        }

        PluginCommand blackMarketCommand = getCommand("blackmarket");
        if (blackMarketCommand != null) {
            blackMarketCommand.setExecutor(new network.skypvp.extraction.command.BlackMarketMenuCommand(
                    core, breachEngine, hubEconomyService, blackMarketConfig));
        }

        PluginCommand craftCommand = getCommand("craft");
        if (craftCommand != null) {
            craftCommand.setExecutor(new network.skypvp.extraction.command.CraftWorkbenchMenuCommand(
                    core, breachEngine, craftingConfig, craftingMaterials, blueprintDiscovery, weaponMechanicsBridge));
        }

        PluginCommand refineryCommand = getCommand("refinery");
        if (refineryCommand != null) {
            refineryCommand.setExecutor(new network.skypvp.extraction.command.RefineryMenuCommand(
                    core, breachEngine, craftingConfig, materialBreakdownConfig));
        }

        PluginCommand craftingReloadCommand = getCommand("armoryreload");
        if (craftingReloadCommand != null) {
            craftingReloadCommand.setExecutor(new CraftingReloadCommand(
                    this, core, craftingConfig, blackMarketConfig, itemConfigService, medicShopConfig,
                    materialBreakdownConfig, stashTierConfig, blueprintDiscovery, gameplayCoordinator));
        }

        PluginCommand medicCommand = getCommand("medic");
        if (medicCommand != null) {
            medicCommand.setExecutor(new network.skypvp.extraction.command.MedicMenuCommand(
                    core, breachEngine, craftingMaterials, medicShopConfig));
        }

        PluginCommand stashCommand = getCommand("stash");
        if (stashCommand != null) {
            stashCommand.setExecutor(new network.skypvp.extraction.command.StashCommand(breachEngine, materialStashGui));
        }

        PluginCommand scrapperCommand = getCommand("scrapper");
        if (scrapperCommand != null) {
            scrapperCommand.setExecutor(new ScrapperCommand(core, breachEngine, scrapperService, craftingConfig));
        }

        scrapperCollectTask = new ScrapperCollectTask(this, core.platformScheduler(), scrapperService);
        scrapperCollectTask.start();

        PluginCommand extractionHubCommand = getCommand("extractionhub");
        if (extractionHubCommand != null) {
            ExtractionHubCommand hubExecutor = new ExtractionHubCommand(hubStationCatalog);
            extractionHubCommand.setExecutor(hubExecutor);
            extractionHubCommand.setTabCompleter(hubExecutor);
        }

        staminaListener = new BreachStaminaListener(core, breachEngine, staminaService, staminaSprintBridge);
        getServer().getPluginManager().registerEvents(staminaListener, this);
        medicHealService = new MedicHealService(core, core.platformScheduler());
        getServer().getPluginManager().registerEvents(
                new MedicConsumableListener(core, breachEngine, staminaService, medicHealService), this);
        startStaminaTicker(core);
        shieldRechargeService = new ShieldRechargeService(core, core.platformScheduler());
        getServer().getPluginManager().registerEvents(new ShieldRechargerListener(core, shieldRechargeService), this);
        getServer().getPluginManager().registerEvents(new ShieldRepairKitListener(core, breachEngine), this);
        getServer().getPluginManager().registerEvents(
                new BlueprintReceiptListener(core, breachEngine, craftingConfig, blueprintDiscovery), this);
        getServer().getPluginManager().registerEvents(
                new ExtractionCraftingProgressListener(blueprintDiscovery, craftingMaterials), this);
        // ExtractionRaidInventoryListener handles raid inventory rules.
        startShieldFlashTicker(core);
        getServer().getPluginManager().registerEvents(new BreachVoidListener(breachEngine), this);
        getServer().getPluginManager().registerEvents(new ExtractionEnvironmentGuard(weaponMechanicsBridge), this);
        weaponCrossbowPosePacketService = WeaponCrossbowPosePacketService.register(this, weaponMechanicsBridge);
        getServer().getPluginManager().registerEvents(new BreachArrivalListener(breachEngine), this);
        getServer().getPluginManager().registerEvents(new ExtractionLobbyListener(breachEngine), this);
        getServer().getPluginManager().registerEvents(
                new network.skypvp.extraction.listener.ExtractionHubSpawnListener(core, breachEngine), this);
        getServer().getPluginManager().registerEvents(
                new network.skypvp.extraction.listener.ExtractionItemReconcileListener(core), this);
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
        LibsDisguisesBridge.logStartupStatus(getLogger());
        breachEngine.warmStandbyInstances();
    }

    private void releaseModeReadyHold(PaperCorePlugin core) {
        if (core != null && core.worldStateService() != null) {
            core.worldStateService().releaseRoutingHold(
                    network.skypvp.paper.service.WorldStateService.HOLD_MODE_PLUGIN);
        }
    }

    private void startStaminaTicker(PaperCorePlugin core) {
        if (core == null || core.platformScheduler() == null || staminaListener == null) {
            return;
        }
        staminaTickTask = core.platformScheduler().runGlobalTimer(staminaListener::tickRaidStamina, 1L, 1L);
    }

    private void startRuinsRaiderAiTicker(PaperCorePlugin core) {
        if (core == null || core.platformScheduler() == null || ruinsRaiderAiService == null) {
            return;
        }
        ruinsRaiderAiTickTask = core.platformScheduler().runGlobalTimer(() -> {
            ruinsRaiderAiService.tick();
            if (ruinsMobNametagService != null) {
                ruinsMobNametagService.tick();
            }
        }, 1L, 1L);
    }

    /**
     * The action bar normally refreshes on the core HUD cadence, which is too slow to animate the
     * "SHIELD DOWN" / "BROKEN SHIELD" flash or stepwise medic healing. This lightweight timer re-pushes
     * the action bar every few ticks for live raiders who are flashing a shield state or actively healing.
     */
    private void startShieldFlashTicker(PaperCorePlugin core) {
        if (core == null || core.platformScheduler() == null) {
            return;
        }
        shieldFlashTask = core.platformScheduler().runGlobalTimer(
                () -> {
                    if (breachEngine == null || hudProvider == null) {
                        return;
                    }
                    for (network.skypvp.extraction.engine.BreachInstance instance : breachEngine.activeInstances()) {
                        if (instance.state() != network.skypvp.extraction.model.BreachState.ACTIVE) {
                            continue;
                        }
                        for (org.bukkit.entity.Player player : instance.liveRaiders()) {
                            boolean healing = medicHealService != null
                                    && medicHealService.isHealing(player.getUniqueId());
                            if (!healing && !hudProvider.shouldFlashRefresh(player)) {
                                continue;
                            }
                            core.platformScheduler().runOnPlayer(
                                    player,
                                    () -> core.actionBarService().refreshPlayer(player)
                            );
                        }
                    }
                },
                10L,
                2L
        );
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
        if (backpackService != null) {
            // Restores any open backpack views before inventories save — prevents item loss.
            backpackService.shutdown();
            backpackService = null;
        }
        if (shieldFlashTask != null) {
            shieldFlashTask.cancel();
            shieldFlashTask = null;
        }
        if (staminaTickTask != null) {
            staminaTickTask.cancel();
            staminaTickTask = null;
        }
        if (ruinsRaiderAiTickTask != null) {
            ruinsRaiderAiTickTask.cancel();
            ruinsRaiderAiTickTask = null;
        }
        if (scrapperCollectTask != null) {
            scrapperCollectTask.stop();
            scrapperCollectTask = null;
        }
        if (shieldRechargeService != null) {
            shieldRechargeService.shutdown();
        }
        if (medicHealService != null) {
            medicHealService.shutdown();
        }
        if (weaponCrossbowPosePacketService != null) {
            weaponCrossbowPosePacketService.shutdown();
            weaponCrossbowPosePacketService = null;
        }
        if (coreForNametags != null && coreForNametags.nametagLibrary() != null) {
            coreForNametags.nametagLibrary().unregisterHideCondition(NAMETAG_HIDE_CONDITION_ID);
            coreForNametags = null;
        }
        if (breachEngine != null) {
            breachEngine.shutdown();
        }
        if (partyQueueDeploySubscriber != null) {
            partyQueueDeploySubscriber.close();
            partyQueueDeploySubscriber = null;
        }
        if (scoreboardData != null) {
            scoreboardData.stop();
        }
        if (partyScoreboardData != null) {
            partyScoreboardData.stop();
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
        if (customItemProvider != null) {
            getServer().getServicesManager().unregister(CustomItemProvider.class, customItemProvider);
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        getServer().getServicesManager().unregister(CoreBehaviorProfile.class, behaviorProfile);
    }
}
