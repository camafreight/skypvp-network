package network.skypvp.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;

import network.skypvp.paper.currency.CurrencyCommand;
import network.skypvp.paper.currency.CurrencyRegistry;

import network.skypvp.paper.command.HologramCommand;
import network.skypvp.paper.command.NametagCommand;
import network.skypvp.paper.command.HubCommand;
import network.skypvp.paper.command.ChatCommand;
import network.skypvp.paper.command.IgnoreCommand;
import network.skypvp.paper.command.LagCommand;

import network.skypvp.paper.command.NpcCommand;
import network.skypvp.paper.command.PaperStatusCommand;

import network.skypvp.paper.command.NetworkInfoCommand;
import network.skypvp.paper.command.ServerLifecycleCommand;

import network.skypvp.paper.command.WebAuthCommand;
import network.skypvp.paper.command.WorldStateCommand;
import network.skypvp.paper.platform.PlatformScheduler;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.database.DbQueryService;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.integration.PaperChatFormatRefreshSubscriber;
import network.skypvp.paper.integration.PaperDecorationRefreshSubscriber;
import network.skypvp.paper.integration.PaperSocialSettingsRefreshSubscriber;
import network.skypvp.paper.integration.SkyPvPChatPlaceholderExpansion;
import network.skypvp.paper.integration.SkyPvPPlaceholderExpansion;
import network.skypvp.paper.integration.PaperStaffChatSubscriber;
import network.skypvp.paper.integration.PlayerLocaleListener;
import network.skypvp.paper.library.ItemsLibrary;
import network.skypvp.paper.library.HolographicLibrary;
import network.skypvp.paper.library.NametagLibrary;
import network.skypvp.paper.library.NpcLibrary;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import network.skypvp.paper.listener.DeathMessageListener;
import network.skypvp.paper.listener.HologramInteractListener;
import network.skypvp.paper.listener.NpcInteractListener;
import network.skypvp.paper.listener.AfkDetectionListener;
import network.skypvp.paper.listener.NetworkChatListener;
import network.skypvp.paper.listener.NetworkJoinListener;
import network.skypvp.paper.listener.PlayerQuitListener;
import network.skypvp.paper.listener.PlayerStatsListener;
import network.skypvp.paper.listener.VanillaAdvancementDisableListener;
import network.skypvp.paper.listener.WorldEnvironmentListener;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.NametagDefinition;
import network.skypvp.paper.model.NpcDefinition;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.paper.chat.ChatFormatRepository;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.chat.ChatModerationService;
import network.skypvp.paper.chat.ChatTranslationDeliveryService;
import network.skypvp.paper.chat.PartyMembershipRepository;
import network.skypvp.paper.repository.PaperPunishmentRepository;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.ServerTextCatalog;
import network.skypvp.paper.text.ServerTextBridge;
import network.skypvp.shared.chat.ChatTranslationDiagnostics;
import network.skypvp.shared.chat.ChatTranslationService;
import network.skypvp.shared.chat.ChatTranslator;
import network.skypvp.shared.chat.ChatTranslatorFactory;
import network.skypvp.shared.chat.TextModerationClient;
import network.skypvp.paper.persistence.PostgresConnectionSettings;

import network.skypvp.paper.integration.PaperMenuListener;
import network.skypvp.paper.listener.CoreHotbarListener;
import network.skypvp.paper.listener.CoreHotbarLockListener;
import network.skypvp.paper.repository.ExtractionInventoryRepository;
import network.skypvp.paper.repository.PlayerSocialSettingsRepository;
import network.skypvp.paper.repository.SocialGraphRepository;
import network.skypvp.paper.repository.FriendGraphRepository;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.paper.inventory.vault.VaultDecorationTags;
import network.skypvp.paper.inventory.vault.VaultGuiListener;
import network.skypvp.paper.inventory.vault.VaultGuiService;
import network.skypvp.paper.service.NetworkMenuService;
import network.skypvp.paper.service.PlayerInventoryManager;
import network.skypvp.paper.service.PlayerLocaleService;
import network.skypvp.paper.service.RewardClaimMenuService;
import network.skypvp.paper.service.SocialMenuService;
import network.skypvp.paper.gamemode.api.BreachCapacityProvider;
import network.skypvp.paper.repository.HologramRepository;
import network.skypvp.paper.repository.NametagRepository;
import network.skypvp.paper.repository.NetworkServerDirectoryRepository;
import network.skypvp.paper.repository.NpcRepository;
import network.skypvp.paper.repository.PartyGraphRepository;
import network.skypvp.paper.repository.PlayerSessionRepository;
import network.skypvp.paper.repository.PlayerCurrencyRepository;
import network.skypvp.paper.repository.PlayerStatsRepository;
import network.skypvp.paper.service.ActionBarService;
import network.skypvp.paper.service.BossBarService;

import network.skypvp.paper.service.GameModeBehaviorService;
import network.skypvp.paper.service.GracefulDrainService;
import network.skypvp.paper.service.HudProviderService;
import network.skypvp.paper.service.PerformanceMonitorService;
import network.skypvp.paper.service.PlayerSessionService;


import network.skypvp.paper.service.RankService;
import network.skypvp.paper.service.ScoreboardService;
import network.skypvp.paper.service.ServerLifecycleService;
import network.skypvp.paper.service.SocialTeleportPolicyService;
import network.skypvp.paper.service.TabListService;
import network.skypvp.paper.service.VanishService;
import network.skypvp.paper.service.WebAdminIntegrationService;
import network.skypvp.paper.service.WorldStateService;
import network.skypvp.paper.world.VoidChunkGenerator;
import network.skypvp.shared.SocialSettingsRefreshEvent;
import network.skypvp.shared.ChatFormatRefreshEvent;
import network.skypvp.shared.DecorationRefreshEvent;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.RedisEventPublisher;
import network.skypvp.shared.ServerHeartbeatEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.scheduler.BukkitTask;

public final class PaperCorePlugin extends JavaPlugin {
   private static final int DEFAULT_REDIS_PORT = 6379;
   private static final int DEFAULT_REDIS_DATABASE = 0;
   private static final int DEFAULT_POSTGRES_PORT = 5432;
   private static final String DEFAULT_POSTGRES_DATABASE = "skypvp_network";
   private static final String DEFAULT_POSTGRES_USERNAME = "skypvp";
   private NetworkServerRole serverRole;
   private String serverId;
   private RedisEventPublisher redisPublisher;
   private DatabaseManager databaseManager;
   private AsyncDbExecutor asyncDbExecutor;
   private ServerPlatform platform;
   private DbQueryService dbQueryService;
   private NetworkServerDirectoryRepository networkServerDirectoryRepository;
   private PlayerSessionService sessionService;
   private RankService rankService;

   private PaperStaffChatSubscriber staffChatSubscriber;
   private PaperDecorationRefreshSubscriber decorationRefreshSubscriber;
   private PaperChatFormatRefreshSubscriber chatFormatRefreshSubscriber;
   private PaperSocialSettingsRefreshSubscriber socialSettingsRefreshSubscriber;
   private NpcRepository npcRepository;
   private HologramRepository hologramRepository;
   private NametagRepository nametagRepository;
   private CurrencyRegistry currencyRegistry;

   private FriendGraphRepository friendGraphRepository;
   private PartyGraphRepository partyGraphRepository;
   private PlayerStatsRepository playerStatsRepository;
   private PlayerCurrencyRepository playerCurrencyRepository;
   private PlayerStatsListener playerStatsListener;
   private VanishService vanishService;
   private IgnoreCommand ignoreCommand;
   private TabListService tabListService;
   private ScoreboardService scoreboardService;
   private BossBarService bossBarService;

   private ActionBarService actionBarService;
   private GuiManager guiManager;
   private NpcLibrary npcLibrary;
   private HolographicLibrary holographicLibrary;
   private NametagLibrary nametagLibrary;
   private WorldStateService worldStateService;
   private PlatformTask heartbeatTask;
   private Thread shutdownHeartbeatHook;
   private PlatformTask tabListTask;
   private PlatformTask scoreboardTask;
   private PlatformTask bossBarTask;
   private PlatformTask actionBarTask;
   private PlatformTask rankReconcileTask;
   private BukkitTask socialGraphRefreshTask;
   private long bootEpochMillis;
   private long lastRedisWarningEpochMillis;
   private String lifecycleSource;
   private long orchestrationGeneration;
   private String advertisedHost;
   private int advertisedPort;
   private ServerLifecycleService serverLifecycleService;
   private PerformanceMonitorService performanceMonitorService;
   private network.skypvp.paper.library.packet.PacketEntityInteractionService packetEntityInteractionService;
   private GameModeBehaviorService gameModeBehaviorService;
   private SocialTeleportPolicyService socialTeleportPolicyService;
   private HudProviderService hudProviderService;
   private WebAdminIntegrationService webAdminIntegrationService;
   private GracefulDrainService gracefulDrainService;
   private SkyPvPPlaceholderExpansion placeholderExpansion;
   private SkyPvPChatPlaceholderExpansion chatPlaceholderExpansion;
   private CoreHotbarService coreHotbarService;
   private SocialGraphRepository socialGraphRepository;
   private ExtractionInventoryRepository extractionInventoryRepository;
   private PlayerInventoryManager playerInventoryManager;
   private RewardClaimMenuService rewardClaimMenuService;
   private NetworkMenuService networkMenuService;
   private PlayerSocialSettingsRepository playerSocialSettingsRepository;
   private PlayerSocialSettingsService playerSocialSettingsService;
   private SocialMenuService socialMenuService;
   private ChatFormatService chatFormatService;
   private ChatModerationService chatModerationService;
   private ChatTranslationDeliveryService chatTranslationDeliveryService;
   private ServerTextBridge serverTextBridge;
   private PlayerLocaleService playerLocaleService;
   private PlayerLocaleListener playerLocaleListener;
   private PartyMembershipRepository partyMembershipRepository;
   private PaperMenuListener paperMenuListener;

   public PaperCorePlugin() {
   }

   public void onEnable() {
      this.saveDefaultConfig();
      this.reloadConfig();
      this.serverTextBridge = new ServerTextBridge();
      this.bootEpochMillis = System.currentTimeMillis();
      this.serverRole = this.loadServerRole();
      this.gameModeBehaviorService = new GameModeBehaviorService(this, this.serverRole);
      this.socialTeleportPolicyService = new SocialTeleportPolicyService(this, this.gameModeBehaviorService);
      this.hudProviderService = new HudProviderService(this);
      this.serverId = this.resolveServerId();
      this.lifecycleSource = this.resolveLifecycleSource();
      this.orchestrationGeneration = this.resolveOrchestrationGeneration();
      this.advertisedHost = this.resolveAdvertisedHost();
      this.advertisedPort = this.resolveAdvertisedPort();
      this.redisPublisher = this.createRedisPublisher();
      if (this.isRedisEnabled()) {
         RedisConnectionSettings redisSettings = new RedisConnectionSettings(
            this.redisHost(),
            this.redisPort(),
            this.redisPassword(),
            this.redisDatabase()
         );
         this.webAdminIntegrationService = new WebAdminIntegrationService(redisSettings, this.serverId, this);
         this.webAdminIntegrationService.start();
      }

      this.platform = PlatformScheduler.create(this);
      if (this.platform.isFolia()) {
         this.getLogger().info("[SkyPvPCore] Folia region threading enabled — plugins should use ServerPlatform.");
      } else {
         this.getLogger().info("[SkyPvPCore] Paper single-thread scheduler enabled.");
      }
      this.databaseManager = this.createDatabaseManager();
      this.asyncDbExecutor = this.databaseManager != null ? new AsyncDbExecutor(this.databaseManager, this, this.platform, this.getLogger()) : null;
      this.dbQueryService = this.databaseManager != null ? new DbQueryService(this.databaseManager, this, this.platform, this.getLogger()) : null;
      this.networkServerDirectoryRepository = this.createNetworkServerDirectoryRepository();
      this.sessionService = this.createSessionService();
      this.rankService = this.createRankService();

      if (this.asyncDbExecutor != null) {
         this.friendGraphRepository = new FriendGraphRepository(this.asyncDbExecutor);
         this.partyGraphRepository = new PartyGraphRepository(this.asyncDbExecutor);
         this.socialGraphRepository = new SocialGraphRepository(this.asyncDbExecutor);
         this.extractionInventoryRepository = new ExtractionInventoryRepository(this.asyncDbExecutor);
         this.playerSocialSettingsRepository = new PlayerSocialSettingsRepository(this.asyncDbExecutor);
         this.playerSocialSettingsService = new PlayerSocialSettingsService(this.playerSocialSettingsRepository);
         this.playerSocialSettingsService.setChangeListener(this::publishSocialSettingsRefresh);
         ChatFormatRepository chatFormatRepository = new ChatFormatRepository(this.asyncDbExecutor);
         this.chatFormatService = new ChatFormatService(this, chatFormatRepository, this.getLogger());
         this.chatFormatService.setChangeListener((formatId, action) -> {
            this.publishChatFormatRefresh(formatId, action);
            TabListService tabList = this.tabListService;
            if (tabList != null) {
               tabList.refresh();
            }
         });
         this.chatFormatService.reload();
         PaperPunishmentRepository punishmentRepository = new PaperPunishmentRepository(this.asyncDbExecutor);
         TextModerationClient moderationClient = new TextModerationClient(
                 this.chatModerationEndpoint(),
                 this.chatModerationApiKey(),
                 this.chatModerationContentSafetyMinSeverity(),
                 this.getLogger()
         );
         if (this.chatModerationEnabled()) {
            if (moderationClient.enabled()) {
               this.getLogger().info("[chat-moderation] Enabled with Azure endpoint configured.");
            } else {
               this.getLogger().warning(
                       "[chat-moderation] Enabled but endpoint/api-key missing; messages will not be moderated."
               );
            }
         } else {
            this.getLogger().info("[chat-moderation] Disabled.");
         }
         this.chatModerationService = new ChatModerationService(
                 this,
                 moderationClient,
                 punishmentRepository,
                 this.getLogger()
         );
         this.playerLocaleService = new PlayerLocaleService();
         ChatTranslator azureTranslator = ChatTranslatorFactory.createAzure(
                 this.chatTranslationEndpoint(),
                 this.chatTranslationApiKey(),
                 this.chatTranslationRegion(),
                 this.getLogger()
         );
         ChatTranslationService chatTranslationService = new ChatTranslationService(
                 azureTranslator,
                 this.getLogger(),
                 "paper"
         );
         ChatTranslationDiagnostics.logStartup(
                 this.getLogger(),
                 this.chatTranslationEnabled(),
                 azureTranslator
         );
         if (this.chatTranslationEnabled()) {
            if (azureTranslator.enabled()) {
               this.getLogger().info(
                       "[chat-translation] Enabled with "
                               + azureTranslator.providerId()
                               + " configured."
               );
            } else {
               this.getLogger().warning(
                       "[chat-translation] Enabled but "
                               + azureTranslator.providerId()
                               + " is not configured; auto-translate will not run."
               );
            }
         } else {
            this.getLogger().info("[chat-translation] Disabled (SPVP_CHAT_TRANSLATION_ENABLED=false).");
         }
         this.chatTranslationDeliveryService = new ChatTranslationDeliveryService(
                 chatTranslationService,
                 this.playerLocaleService,
                 this.playerSocialSettingsService
         );
         this.partyMembershipRepository = new PartyMembershipRepository(this.asyncDbExecutor);
         this.friendGraphRepository.refreshAsync();
         this.partyGraphRepository.refreshAsync();
      }

      this.guiManager = new GuiManager(this, this.platform);
      this.coreHotbarService = new CoreHotbarService(this);
      if (this.extractionInventoryRepository != null) {
         this.playerInventoryManager = new PlayerInventoryManager(this, this.extractionInventoryRepository, this.coreHotbarService, this.guiManager);
      }
      this.rewardClaimMenuService = new RewardClaimMenuService(this, this.guiManager);
      this.networkMenuService = new NetworkMenuService(this, this.guiManager, this.socialGraphRepository, this.playerInventoryManager, this.rewardClaimMenuService);
      if (this.playerSocialSettingsService != null) {
         this.socialMenuService = new SocialMenuService(
            this,
            this.guiManager,
            this.socialGraphRepository,
            this.playerSocialSettingsService,
            null,
            this.networkMenuService::openRootMenu
         );
         this.networkMenuService.bindSocialMenuService(this.socialMenuService);
      }
      if (this.playerInventoryManager != null && this.extractionInventoryRepository != null) {
         VaultGuiService vaultGuiService = new VaultGuiService(
                 this,
                 this.extractionInventoryRepository,
                 this.networkMenuService,
                 this.guiManager
         );
         this.playerInventoryManager.bindVaultGui(vaultGuiService);
         VaultDecorationTags.init(this);
         this.extractionInventoryRepository.ensureVaultProgressSchema();
         this.getServer().getPluginManager().registerEvents(new VaultGuiListener(vaultGuiService, this.coreHotbarService), this);
      }
      this.scoreboardService = new ScoreboardService(this, this.rankService);
      this.bossBarService = new BossBarService(this);
      if (this.rankService != null) {
         this.actionBarService = new ActionBarService(this, this.rankService);
      }
      this.npcLibrary = new NpcLibrary(this);
      this.holographicLibrary = new HolographicLibrary(this);
      this.nametagLibrary = new NametagLibrary(this);
      if (PacketEventsBridge.isAvailable()) {
         this.getLogger().info("[SkyPvPCore] PacketEvents detected — NPC packet visuals use PacketEvents 2.x.");
         if (this.playerLocaleService != null) {
            this.playerLocaleListener = new PlayerLocaleListener(this, this.playerLocaleService);
            this.playerLocaleListener.start();
         }
      } else {
         this.getLogger().warning("[SkyPvPCore] PacketEvents not loaded — player/mob NPC visuals will fail until packetevents-spigot is installed.");
      }
      this.worldStateService = new WorldStateService(this);
      this.worldStateService.initializeStartupReadiness();
      this.getServer()
         .getPluginManager()
         .registerEvents(
            new Listener() {
               {
                  Objects.requireNonNull(PaperCorePlugin.this);
               }

               @EventHandler
               public void onServerLoad(ServerLoadEvent event) {
                  if (PaperCorePlugin.this.worldStateService != null) {
                     PaperCorePlugin.this.worldStateService.markServerLoadComplete();
                     PaperCorePlugin.this.publishRoutingHeartbeat();
                  }
               }
            },
            this
         );
      this.paperMenuListener = new PaperMenuListener(this.networkMenuService);
      PaperMenuListener.register(this, this.paperMenuListener);
      this.getServer().getPluginManager().registerEvents(this.rewardClaimMenuService, this);
      this.getServer().getPluginManager().registerEvents(new CoreHotbarListener(this, this.coreHotbarService, this.networkMenuService), this);
      this.getServer().getPluginManager().registerEvents(new CoreHotbarLockListener(this.coreHotbarService), this);
      this.getServer().getPluginManager().registerEvents(new WorldEnvironmentListener(this), this);
      this.getServer().getPluginManager().registerEvents(new VanillaAdvancementDisableListener(this), this);
      this.getServer().getPluginManager().registerEvents(this.guiManager, this);
      this.getServer().getPluginManager().registerEvents(new AfkDetectionListener(this), this);
      this.getServer().getPluginManager().registerEvents(new HologramInteractListener(this.holographicLibrary), this);
      this.getServer().getPluginManager().registerEvents(new NpcInteractListener(this.npcLibrary), this);

      this.getServer().getPluginManager().registerEvents(new NetworkJoinListener(this, this.sessionService, this.rankService, this.scoreboardService, this.playerSocialSettingsService, this.playerLocaleService), this);
      this.getServer().getPluginManager().registerEvents(new PlayerQuitListener(this, this.sessionService, this.rankService, this.scoreboardService, this.playerSocialSettingsService, this.playerLocaleService), this);



      if (this.rankService != null && this.chatFormatService != null) {
         this.getServer().getPluginManager().registerEvents(
                 new NetworkChatListener(
                         this,
                         this.rankService,
                         this.playerSocialSettingsService,
                         this.chatFormatService,
                         this.chatModerationService,
                         this.chatTranslationDeliveryService,
                         this.partyMembershipRepository
                 ),
                 this
         );
      }

      if (this.rankService != null) {
         this.tabListService = new TabListService(this, this.rankService);
         int tabRefreshTicks = Math.max(
            20, this.gameModeBehaviorService.intValue("core.tab.refresh.ticks", 20)
         );
         int scoreboardRefreshTicks = Math.max(
            1, this.gameModeBehaviorService.intValue("core.hud.scoreboard.refresh-ticks", 1)
         );
         int bossBarRefreshTicks = Math.max(
            1, this.gameModeBehaviorService.intValue("core.hud.boss-bar.refresh-ticks", 1)
         );
         int actionBarRefreshTicks = Math.max(
            1, this.gameModeBehaviorService.intValue("core.hud.action-bar.refresh-ticks", 1)
         );
         this.tabListTask = this.platform.runGlobalTimer(this.tabListService::refresh, (long)tabRefreshTicks, (long)tabRefreshTicks);
         this.scoreboardTask = this.platform.runGlobalTimer(this.scoreboardService::refresh, (long)scoreboardRefreshTicks, (long)scoreboardRefreshTicks);
         this.bossBarTask = this.platform.runGlobalTimer(this.bossBarService::refresh, (long)bossBarRefreshTicks, (long)bossBarRefreshTicks);
         if (this.actionBarService != null) {
            this.actionBarTask = this.platform.runGlobalTimer(this.actionBarService::refresh, (long)actionBarRefreshTicks, (long)actionBarRefreshTicks);
         }

         this.getLogger()
            .info(
               String.format(
                  Locale.ROOT,
                  "HUD refresh ticks: tab=%d (%.1f updates/s), scoreboard=%d (%.1f updates/s), bossbar=%d (%.1f updates/s), actionbar=%d (%.1f updates/s). Paper is tick-bound, so 20 updates/s is the practical ceiling.",
                  tabRefreshTicks,
                  20.0 / (double)tabRefreshTicks,
                  scoreboardRefreshTicks,
                  20.0 / (double)scoreboardRefreshTicks,
                  bossBarRefreshTicks,
                  20.0 / (double)bossBarRefreshTicks,
                  actionBarRefreshTicks,
                  20.0 / (double)actionBarRefreshTicks
               )
            );
         this.socialGraphRefreshTask = this.platform.runAsyncTimer(() -> {
            if (this.friendGraphRepository != null) {
               this.friendGraphRepository.refreshAsync();
            }

            if (this.partyGraphRepository != null) {
               this.partyGraphRepository.refreshAsync();
            }
         }, 40L, 40L);
         if (this.isRedisEnabled()) {
            RedisConnectionSettings redisSettings = new RedisConnectionSettings(
               this.redisHost(),
               this.redisPort(),
               this.redisPassword(),
               this.redisDatabase()
            );
            this.staffChatSubscriber = new PaperStaffChatSubscriber(redisSettings, this.serverId, this, this.chatFormatService, this.getLogger());
            this.staffChatSubscriber.start();
            this.decorationRefreshSubscriber = new PaperDecorationRefreshSubscriber(
               redisSettings, this.decorationScope(), this.serverId, this, this.getLogger()
            );
            this.decorationRefreshSubscriber.start();
            if (this.chatFormatService != null) {
               this.chatFormatRefreshSubscriber = new PaperChatFormatRefreshSubscriber(
                  redisSettings, this.serverId, this, this.chatFormatService, this.getLogger()
               );
               this.chatFormatRefreshSubscriber.start();
            }
            if (this.playerSocialSettingsService != null) {
               this.socialSettingsRefreshSubscriber = new PaperSocialSettingsRefreshSubscriber(
                  redisSettings, this.serverId, this, this.playerSocialSettingsService, this.getLogger()
               );
               this.socialSettingsRefreshSubscriber.start();
            }
         }
      } else {
         this.getLogger().warning("PostgreSQL is unavailable; social graph, staff chat subscriber, and HUD refresh tasks are disabled (LuckPerms rank reads still work when the plugin is installed).");
      }



      PluginCommand command = this.getCommand("skypvp");
      if (command != null) {
         command.setExecutor(new PaperStatusCommand(this));
      }

      PluginCommand worldStateCmd = this.getCommand("worldstate");
      if (worldStateCmd != null) {
         WorldStateCommand worldStateCommand = new WorldStateCommand(this, this.worldStateService);
         worldStateCmd.setExecutor(worldStateCommand);
         worldStateCmd.setTabCompleter(worldStateCommand);
      }

      this.scheduleHeartbeat();
      this.packetEntityInteractionService = new network.skypvp.paper.library.packet.PacketEntityInteractionService(this, this.platform);
      this.packetEntityInteractionService.start();
      this.serverLifecycleService = new ServerLifecycleService(this);
      this.serverLifecycleService.start();
      this.performanceMonitorService = new PerformanceMonitorService(this);
      this.performanceMonitorService.start();
      String lobbyServer = "lobby";
      this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
      this.getServer().getMessenger().registerOutgoingPluginChannel(this, "skypvp:route");
      this.getServer().getMessenger().registerOutgoingPluginChannel(this, "skypvp:social");


      PluginCommand lifecycleCmd = this.getCommand("lifecycle");
      if (lifecycleCmd != null && this.serverLifecycleService != null) {
         ServerLifecycleCommand lifecycleExecutor = new ServerLifecycleCommand(this.serverLifecycleService);
         lifecycleCmd.setExecutor(lifecycleExecutor);
         lifecycleCmd.setTabCompleter(lifecycleExecutor);
      }

      PluginCommand lagCmd = this.getCommand("lag");
      if (lagCmd != null && this.performanceMonitorService != null) {
         LagCommand lagExecutor = new LagCommand(this.performanceMonitorService);
         lagCmd.setExecutor(lagExecutor);
         lagCmd.setTabCompleter(lagExecutor);
      }

      PluginCommand tpsCmd = this.getCommand("tps");
      if (tpsCmd != null && this.performanceMonitorService != null) {
         tpsCmd.setExecutor(new LagCommand(this.performanceMonitorService));
      }

      this.ignoreCommand = new IgnoreCommand(this);
      PluginCommand ignC = this.getCommand("ignore");
      if (ignC != null) {
         ignC.setExecutor(this.ignoreCommand);
         ignC.setTabCompleter(this.ignoreCommand);
      }

      PluginCommand ignL = this.getCommand("ignorelist");
      if (ignL != null) {
         ignL.setExecutor(this.ignoreCommand);
         ignL.setTabCompleter(this.ignoreCommand);
      }

      if (this.chatFormatService != null && this.playerSocialSettingsService != null) {
         ChatCommand chatCommand = new ChatCommand(this, this.chatFormatService, this.playerSocialSettingsService);
         PluginCommand chatCmd = this.getCommand("chat");
         if (chatCmd != null) {
            chatCmd.setExecutor(chatCommand);
            chatCmd.setTabCompleter(chatCommand);
         }
      }



      String serverRoleLabel = this.serverRole.name();

      this.currencyRegistry = new CurrencyRegistry(this);
      this.currencyRegistry.init();
      
      PluginCommand currencyCmd = this.getCommand("currency");
      if (currencyCmd != null) {
          CurrencyCommand currencyExec = new CurrencyCommand(this, this.currencyRegistry);
          currencyCmd.setExecutor(currencyExec);
          currencyCmd.setTabCompleter(currencyExec);
      }

      if (this.databaseManager != null) {






      }





      if (this.databaseManager != null) {
         this.playerStatsRepository = new PlayerStatsRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         this.platform.runAsync(this.playerStatsRepository::init);
         this.playerCurrencyRepository = new PlayerCurrencyRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         this.platform.runAsync(this.playerCurrencyRepository::init);
         this.playerStatsListener = new PlayerStatsListener(this, this.playerStatsRepository);
         this.getServer().getPluginManager().registerEvents(this.playerStatsListener, this);
         this.playerStatsListener.seedOnlinePlayers();

      }

      if (this.databaseManager != null) {

      }

      this.vanishService = new VanishService(this);
      this.getServer().getPluginManager().registerEvents(this.vanishService, this);
      PluginCommand vanishCmd = this.getCommand("vanish");
      if (vanishCmd != null) {
         vanishCmd.setExecutor(this.vanishService);
      }





      this.getServer().getPluginManager().registerEvents(new DeathMessageListener(this.rankService), this);
      NetworkInfoCommand networkCmd = new NetworkInfoCommand(this, this.serverId, this.serverRole.name());
      PluginCommand netCmd = this.getCommand("network");
      if (netCmd != null) {
         netCmd.setExecutor(networkCmd);
      }

      PluginCommand hubCmd = this.getCommand("hub");
      if (hubCmd != null) {
         hubCmd.setExecutor(new HubCommand(this));
      }



      if (this.databaseManager != null) {
         String decoScope = this.decorationScope();
         this.npcRepository = new NpcRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         this.hologramRepository = new HologramRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         this.nametagRepository = new NametagRepository(this, this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         this.npcRepository.setMutationListener(scope -> this.publishDecorationRefresh(scope, "npc"));
         this.hologramRepository.setMutationListener(scope -> this.publishDecorationRefresh(scope, "hologram"));
         this.nametagRepository.setMutationListener(scope -> this.publishDecorationRefresh(scope, "nametag"));
         NpcCommand npcCmd = new NpcCommand(this.npcRepository, this.hologramRepository, this.npcLibrary, decoScope, this);
         PluginCommand npcPluginCmd = this.getCommand("npc");
         if (npcPluginCmd != null) {
            npcPluginCmd.setExecutor(npcCmd);
            npcPluginCmd.setTabCompleter(npcCmd);
         }

         HologramCommand holoCmd = new HologramCommand(this.hologramRepository, this.holographicLibrary, decoScope, this);
         PluginCommand holoPluginCmd = this.getCommand("hologram");
         if (holoPluginCmd != null) {
            holoPluginCmd.setExecutor(holoCmd);
            holoPluginCmd.setTabCompleter(holoCmd);
         }

         PluginCommand holoAliasCmd = this.getCommand("holo");
         if (holoAliasCmd != null) {
            holoAliasCmd.setExecutor(holoCmd);
            holoAliasCmd.setTabCompleter(holoCmd);
         }

         NametagCommand nametagCmd = new NametagCommand(this, this.nametagRepository, this.nametagLibrary, decoScope);
         PluginCommand nametagPluginCmd = this.getCommand("nametag");
         if (nametagPluginCmd != null) {
            nametagPluginCmd.setExecutor(nametagCmd);
            nametagPluginCmd.setTabCompleter(nametagCmd);
         }

         this.platform.runGlobalLater(this::reloadDecorationsWhenReady, 60L);
      }

      PluginCommand webAuthCmd = this.getCommand("webauth");
      if (webAuthCmd != null) {
         webAuthCmd.setExecutor(new WebAuthCommand(this));
      }

      this.gracefulDrainService = new GracefulDrainService(this);
      this.registerShutdownHeartbeatHook();
      this.registerPlaceholderApiExpansion();
      this.platform.runGlobalLater(this::registerPlaceholderApiExpansion, 20L);
      this.getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
         @org.bukkit.event.EventHandler
         public void onPluginEnable(org.bukkit.event.server.PluginEnableEvent event) {
            if ("PlaceholderAPI".equals(event.getPlugin().getName())) {
               PaperCorePlugin.this.registerPlaceholderApiExpansion();
            }
         }
      }, this);
      this.getLogger().info("SkyPvP Paper Core enabled for role " + this.serverRole);
   }

   private void registerPlaceholderApiExpansion() {
      if (!this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         return;
      }

      if (this.placeholderExpansion != null) {
         this.placeholderExpansion.unregister();
      }
      if (this.chatPlaceholderExpansion != null) {
         this.chatPlaceholderExpansion.unregister();
      }

      this.placeholderExpansion = new SkyPvPPlaceholderExpansion(this);
      if (this.placeholderExpansion.register()) {
         this.getLogger().info("Registered PlaceholderAPI expansion: skypvp");
      } else {
         this.getLogger().warning("Failed to register PlaceholderAPI expansion: skypvp");
      }

      this.chatPlaceholderExpansion = new SkyPvPChatPlaceholderExpansion(this);
      if (this.chatPlaceholderExpansion.register()) {
         this.getLogger().info("Registered PlaceholderAPI expansion: skypvpchat");
      } else {
         this.getLogger().warning("Failed to register PlaceholderAPI expansion: skypvpchat");
      }
   }

   public boolean voidGeneratorEnabled() {
      boolean defaultVoid = this.serverRole == NetworkServerRole.LOBBY || this.serverRole == NetworkServerRole.EXTRACTION;
      return this.gameModeBehaviorService != null
         ? this.gameModeBehaviorService.booleanValue("core.world.void-generator.enabled", defaultVoid)
         : defaultVoid;
   }

   public WorldCreator configureWorldCreator(WorldCreator creator) {
      if (creator != null && this.voidGeneratorEnabled()) {
         creator.generator(VoidChunkGenerator.INSTANCE);
      }
      return creator;
   }

   public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
      return this.voidGeneratorEnabled()
         ? VoidChunkGenerator.INSTANCE
         : super.getDefaultWorldGenerator(worldName, id);
   }

   public void onDisable() {
      if (this.heartbeatTask != null) {
         this.heartbeatTask.cancel();
         this.heartbeatTask = null;
      }
      if (this.playerLocaleListener != null) {
         this.playerLocaleListener.shutdown();
         this.playerLocaleListener = null;
      }
      if (this.packetEntityInteractionService != null) {
         this.packetEntityInteractionService.shutdown();
         this.packetEntityInteractionService = null;
      }
      if (this.nametagLibrary != null) {
         this.nametagLibrary.shutdown();
         this.nametagLibrary = null;
      }
      this.publishNotJoinableHeartbeatNow();
      if (this.shutdownHeartbeatHook != null) {
         try {
            Runtime.getRuntime().removeShutdownHook(this.shutdownHeartbeatHook);
         } catch (IllegalStateException ignored) {
         }
         this.shutdownHeartbeatHook = null;
      }
      if (this.placeholderExpansion != null) {
         this.placeholderExpansion.unregister();
         this.placeholderExpansion = null;
      }

      if (this.tabListTask != null) {
         this.tabListTask.cancel();
      }

      if (this.scoreboardTask != null) {
         this.scoreboardTask.cancel();
      }

      if (this.bossBarTask != null) {
         this.bossBarTask.cancel();
      }

      if (this.actionBarTask != null) {
         this.actionBarTask.cancel();
      }



      if (this.socialGraphRefreshTask != null) {
         this.socialGraphRefreshTask.cancel();
      }

      if (this.platform != null) {
         this.platform.shutdown();
      }

      if (this.serverLifecycleService != null) {
         this.serverLifecycleService.stop();
      }

      if (this.performanceMonitorService != null) {
         this.performanceMonitorService.stop();
      }

      if (this.webAdminIntegrationService != null) {
         this.webAdminIntegrationService.close();
      }



      if (this.staffChatSubscriber != null) {
         this.staffChatSubscriber.close();
      }

      if (this.decorationRefreshSubscriber != null) {
         this.decorationRefreshSubscriber.close();
      }

      if (this.chatFormatRefreshSubscriber != null) {
         this.chatFormatRefreshSubscriber.close();
      }

      if (this.socialSettingsRefreshSubscriber != null) {
         this.socialSettingsRefreshSubscriber.close();
      }

      if (this.redisPublisher != null) {
         this.redisPublisher.close();
      }

      if (this.playerStatsListener != null) {
         this.playerStatsListener.flushTrackedPlaytime();
      }

      if (this.databaseManager != null) {
         this.databaseManager.close();
      }
   }

   public NetworkServerRole serverRole() {
      return this.serverRole;
   }

   public String serverId() {
      return this.serverId;
   }

   public String decorationScope() {
      return switch (this.serverRole) {
         case LOBBY -> "lobby";
         case EXTRACTION -> "extraction";
         default -> this.serverRole.name().toLowerCase(Locale.ROOT);
      };
   }

   public void publishDecorationRefresh(String scope, String kind) {
      RedisEventPublisher publisher = this.redisPublisher;
      if (publisher != null && scope != null && !scope.isBlank()) {
         try {
            publisher.publishJson("skypvp:network:decorations", new DecorationRefreshEvent(scope, kind, this.serverId, System.currentTimeMillis()));
         } catch (Exception var5) {
            this.getLogger().warning("[Decorations] Failed to publish refresh: " + var5.getMessage());
         }
      }
   }

   public void publishChatFormatRefresh(String formatId, String action) {
      RedisEventPublisher publisher = this.redisPublisher;
      if (publisher == null) {
         return;
      }
      try {
         publisher.publishJson(
            NetworkChannels.CHAT_FORMAT_REFRESH,
            new ChatFormatRefreshEvent(
               this.serverId,
               action == null ? ChatFormatRefreshEvent.ACTION_RELOAD : action,
               formatId,
               System.currentTimeMillis()
            )
         );
      } catch (Exception ex) {
         this.getLogger().warning("[ChatFormats] Failed to publish refresh: " + ex.getMessage());
      }
   }

   public void publishSocialSettingsRefresh(UUID playerId) {
      RedisEventPublisher publisher = this.redisPublisher;
      if (publisher == null || playerId == null) {
         return;
      }
      try {
         publisher.publishJson(
            NetworkChannels.SOCIAL_SETTINGS_REFRESH,
            new SocialSettingsRefreshEvent(playerId.toString(), this.serverId, System.currentTimeMillis())
         );
      } catch (Exception ex) {
         this.getLogger().warning("[SocialSettings] Failed to publish refresh: " + ex.getMessage());
      }
   }

   public void reloadDecorationsWhenReady() {
      this.reloadNpcDecorations();
      this.reloadHologramDecorations();
      this.reloadNametagDecorations();
   }

   public void reloadNpcDecorations() {
      NpcRepository repo = this.npcRepository;
      if (repo != null) {
         this.asyncDbExecutor()
            .handleOnMainThread(
               repo.loadAllAsync(this.decorationScope()),
               definitions -> {
                  this.getLogger().info("[Decorations] Loaded " + definitions.size() + " NPC definition(s) for scope '" + this.decorationScope() + "'.");
                  this.npcLibrary.clearAllPacketViewers();
                  Map<String, List<NpcDefinition>> byWorld = definitions.stream()
                     .filter(d -> d.location != null && d.location.world != null)
                     .collect(Collectors.groupingBy(d -> d.location.world));

                  for (World world : this.getServer().getWorlds()) {
                     List<NpcDefinition> worldDefinitions = byWorld.getOrDefault(world.getName(), List.of());
                     this.applyNpcDefinitionsForWorld(world, worldDefinitions);
                  }
                  this.npcLibrary.resyncAllViewers();
               },
               throwable -> this.getLogger().warning("[Decorations] NPC refresh load failed: " + throwable.getMessage())
            );
      }
   }

   public void reloadHologramDecorations() {
      HologramRepository repo = this.hologramRepository;
      if (repo != null) {
         this.asyncDbExecutor()
            .handleOnMainThread(
               repo.loadAllAsync(this.decorationScope()),
               definitions -> {
                  this.getLogger().info("[Decorations] Loaded " + definitions.size() + " hologram definition(s) for scope '" + this.decorationScope() + "'.");
                  Map<String, List<HologramDefinition>> byWorld = definitions.stream()
                     .filter(d -> d.anchor != null && d.anchor.world != null)
                     .collect(Collectors.groupingBy(d -> d.anchor.world));

                  for (World world : this.getServer().getWorlds()) {
                     List<HologramDefinition> worldDefinitions = byWorld.getOrDefault(world.getName(), List.of());
                     this.applyHologramDefinitionsForWorld(world, worldDefinitions);
                  }
               },
               throwable -> this.getLogger().warning("[Decorations] Hologram refresh load failed: " + throwable.getMessage())
            );
      }
   }

   public void reloadNametagDecorations() {
      NametagRepository repo = this.nametagRepository;
      NametagLibrary library = this.nametagLibrary;
      if (repo == null || library == null) {
         return;
      }
      String serverScope = this.decorationScope();
      this.asyncDbExecutor().handleOnMainThread(
         repo.loadAllAsync(),
         definitions -> {
            repo.writeLocalCache(definitions);
            NametagDefinition active = NametagRepository.resolveActive(definitions, serverScope);
            library.applyDefinition(active);
            this.getLogger().info("[Nametags] Applied layout for server scope '" + serverScope + "' from "
               + definitions.size() + " stored definition(s).");
         },
         throwable -> {
            this.getLogger().warning("[Nametags] Database load failed, using local cache: " + throwable.getMessage());
            List<NametagDefinition> cached = repo.readLocalCache();
            NametagDefinition active = NametagRepository.resolveActive(cached, serverScope);
            library.applyDefinition(active);
         }
      );
   }

   private void applyNpcDefinitionsForWorld(World world, List<NpcDefinition> definitions) {
      if (this.platform.isFolia()) {
         Set<String> desiredIds = definitions.stream()
            .filter(def -> def.id != null && !def.id.isBlank())
            .map(def -> def.id.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
         this.platform.runGlobal(() -> this.npcLibrary.removeMissingDefinitions(world, desiredIds));
         if (definitions.isEmpty()) {
            return;
         }
         for (NpcDefinition definition : definitions) {
            Location anchor = this.npcLibrary.resolveLocation(world, definition);
            // Partial upsert: only this NPC is reconciled on its region thread. Deletions are handled above by
            // removeMissingDefinitions, so apply() must NOT remove the other NPCs in this world (a full reconcile
            // per definition made each NPC wipe every other NPC sharing the server).
            this.platform.runAtLocation(anchor, () -> this.npcLibrary.apply(world, List.of(definition), false));
         }
         return;
      }

      Location anchor = world.getSpawnLocation();
      this.platform.runAtLocation(anchor, () -> this.npcLibrary.apply(world, definitions));
   }

   private void applyHologramDefinitionsForWorld(World world, List<HologramDefinition> definitions) {
      if (this.platform.isFolia()) {
         if (definitions.isEmpty()) {
            Location anchor = world.getSpawnLocation();
            this.platform.runAtLocation(anchor, () -> this.holographicLibrary.apply(world, List.of()));
            return;
         }
         for (HologramDefinition definition : definitions) {
            Location anchor = this.resolveHologramAnchor(world, definition);
            this.platform.runAtLocation(anchor, () -> this.holographicLibrary.apply(world, List.of(definition)));
         }
         return;
      }

      Location anchor = world.getSpawnLocation();
      this.platform.runAtLocation(anchor, () -> this.holographicLibrary.apply(world, definitions));
   }

   private Location resolveHologramAnchor(World world, HologramDefinition definition) {
      if (definition == null || definition.anchor == null) {
         return world.getSpawnLocation();
      }
      World anchorWorld = this.getServer().getWorld(definition.anchor.world);
      if (anchorWorld == null) {
         anchorWorld = world;
      }
      return new Location(
         anchorWorld,
         definition.anchor.x,
         definition.anchor.y,
         definition.anchor.z,
         definition.anchor.yaw,
         definition.anchor.pitch
      );
   }

   public DatabaseManager databaseManager() {
      return this.databaseManager;
   }

   public DbQueryService dbQueryService() {
      return this.dbQueryService;
   }

   public ServerPlatform platform() {
      return this.platform;
   }

   public network.skypvp.paper.library.packet.PacketEntityInteractionService packetEntityInteractionService() {
      return this.packetEntityInteractionService;
   }

   /** @deprecated use {@link #platform()} */
   @Deprecated
   public ServerPlatform platformScheduler() {
      return this.platform;
   }

   public AsyncDbExecutor asyncDbExecutor() {
      return this.asyncDbExecutor;
   }

   public FriendGraphRepository friendGraphRepository() {
      return this.friendGraphRepository;
   }

   public NetworkServerDirectoryRepository networkServerDirectoryRepository() {
      return this.networkServerDirectoryRepository;
   }

   public PlayerSessionService sessionService() {
      return this.sessionService;
   }

   public RankService rankService() {
      return this.rankService;
   }



   public TabListService tabListService() {
      return this.tabListService;
   }

   public ScoreboardService scoreboardService() {
      return this.scoreboardService;
   }

   public BossBarService bossBarService() {
      return this.bossBarService;
   }



   public ActionBarService actionBarService() {
      return this.actionBarService;
   }

   public HudProviderService hudProviderService() {
      return this.hudProviderService;
   }

   public CoreHotbarService coreHotbarService() {
      return this.coreHotbarService;
   }

   public PartyGraphRepository partyGraphRepository() {
      return this.partyGraphRepository;
   }

   public SocialGraphRepository socialGraphRepository() {
      return this.socialGraphRepository;
   }

   public PlayerInventoryManager playerInventoryManager() {
      return this.playerInventoryManager;
   }

   public ExtractionInventoryRepository extractionInventoryRepository() {
      return this.extractionInventoryRepository;
   }

   public NetworkMenuService networkMenuService() {
      return this.networkMenuService;
   }

   public PlayerSocialSettingsService playerSocialSettingsService() {
      return this.playerSocialSettingsService;
   }

   public PlayerLocaleService playerLocaleService() {
      return this.playerLocaleService;
   }

   public ServerTextBridge serverTextBridge() {
      return this.serverTextBridge;
   }

   public ChatFormatService chatFormatService() {
      return this.chatFormatService;
   }

   public GameModeBehaviorService gameModeBehaviorService() {
      return this.gameModeBehaviorService;
   }

   public SocialTeleportPolicyService socialTeleportPolicyService() {
      return this.socialTeleportPolicyService;
   }

   public PerformanceMonitorService performanceMonitorService() {
      return this.performanceMonitorService;
   }

   public GuiManager guiManager() {
      return this.guiManager;
   }

   public NpcLibrary npcLibrary() {
      return this.npcLibrary;
   }

   public HolographicLibrary holographicLibrary() {
      return this.holographicLibrary;
   }

   public NametagLibrary nametagLibrary() {
      return this.nametagLibrary;
   }

   public NametagRepository nametagRepository() {
      return this.nametagRepository;
   }

   public RedisEventPublisher redisPublisher() {
      return this.redisPublisher;
   }

   public WorldStateService worldStateService() {
      return this.worldStateService;
   }





   public PlayerStatsRepository playerStatsRepository() {
      return this.playerStatsRepository;
   }

   public PlayerCurrencyRepository playerCurrencyRepository() {
      return this.playerCurrencyRepository;
   }

   public PlayerStatsListener playerStatsListener() {
      return this.playerStatsListener;
   }

   public GracefulDrainService gracefulDrainService() {
      return this.gracefulDrainService;
   }

   public long bootEpochMillis() {
      return this.bootEpochMillis;
   }







   private NetworkServerRole loadServerRole() {
      String roleFromEnv = System.getenv("SPVP_SERVER_ROLE");
      if (roleFromEnv != null && !roleFromEnv.isBlank()) {
         try {
            return NetworkServerRole.valueOf(roleFromEnv.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var5) {
            this.getLogger().warning("Invalid SPVP_SERVER_ROLE '" + roleFromEnv + "'; falling back to LOBBY");
         }
      }

      return NetworkServerRole.LOBBY;
   }

   private String resolveServerId() {
      String fromEnv = System.getenv("SPVP_SERVER_ID");
      if (fromEnv != null && !fromEnv.isBlank()) {
         return this.sanitizeServerId(fromEnv);
      } else {
         String podName = System.getenv("HOSTNAME");
         return podName != null && !podName.isBlank() ? this.sanitizeServerId(podName) : "unknown";
      }
   }

   private String sanitizeServerId(String raw) {
      String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "-");
      if (normalized.isBlank()) {
         return "unknown";
      } else {
         return normalized.length() > 63 ? normalized.substring(0, 63) : normalized;
      }
   }

   private String resolveLifecycleSource() {
      String fromEnv = System.getenv("SPVP_LIFECYCLE_SOURCE");
      return fromEnv != null && !fromEnv.isBlank() ? fromEnv.trim() : "paper-runtime";
   }

   private long resolveOrchestrationGeneration() {
      String fromEnv = System.getenv("SPVP_ORCHESTRATION_GENERATION");
      if (fromEnv != null && !fromEnv.isBlank()) {
         try {
            return Math.max(0L, Long.parseLong(fromEnv.trim()));
         } catch (NumberFormatException var3) {
            this.getLogger().warning("Invalid SPVP_ORCHESTRATION_GENERATION '" + fromEnv + "'; defaulting to 0");
            return 0L;
         }
      } else {
         return 0L;
      }
   }

   private String resolveAdvertisedHost() {
      String fromEnv = System.getenv("SPVP_ADVERTISED_HOST");
      return fromEnv != null && !fromEnv.isBlank() ? fromEnv.trim() : "";
   }

   private int resolveAdvertisedPort() {
      String fromEnv = System.getenv("SPVP_ADVERTISED_PORT");
      if (fromEnv != null && !fromEnv.isBlank()) {
         try {
            return Integer.parseInt(fromEnv.trim());
         } catch (NumberFormatException var3) {
            this.getLogger().warning("Invalid SPVP_ADVERTISED_PORT '" + fromEnv + "'; falling back to config/default");
         }
      }

      return 25565;
   }

   private boolean isRedisEnabled() {
      return this.getConfig().getBoolean("redis.enabled", true);
   }

   private String redisHost() {
      return this.resolveConfigValue("redis.host", "SPVP_REDIS_HOST", "redis");
   }

   private int redisPort() {
      return this.resolveConfigInt("redis.port", "SPVP_REDIS_PORT", DEFAULT_REDIS_PORT);
   }

   private String redisPassword() {
      return this.resolveConfigValue("redis.password", "SPVP_REDIS_PASSWORD", "");
   }

   private int redisDatabase() {
      return this.getConfig().getInt("redis.database", DEFAULT_REDIS_DATABASE);
   }

   private boolean isPostgresEnabled() {
      return this.getConfig().getBoolean("postgres.enabled", true);
   }

   private String postgresHost() {
      return this.resolveConfigValue("postgres.host", "SPVP_POSTGRES_HOST", "postgres");
   }

   private int postgresPort() {
      return this.resolveConfigInt("postgres.port", "SPVP_POSTGRES_PORT", DEFAULT_POSTGRES_PORT);
   }

   private String postgresDatabase() {
      return this.resolveConfigValue("postgres.database", "SPVP_POSTGRES_DATABASE", DEFAULT_POSTGRES_DATABASE);
   }

   private String postgresUsername() {
      return this.resolveConfigValue("postgres.username", "SPVP_POSTGRES_USERNAME", DEFAULT_POSTGRES_USERNAME);
   }

   private String postgresPassword() {
      return this.resolveConfigValue("postgres.password", "SPVP_POSTGRES_PASSWORD", "change-me");
   }

   public boolean chatModerationEnabled() {
      return this.resolveConfigBoolean("chat.moderation.enabled", "SPVP_CHAT_MODERATION_ENABLED", false);
   }

   public String chatModerationEndpoint() {
      return this.resolveConfigValue("chat.moderation.endpoint", "SPVP_CHAT_MODERATION_ENDPOINT", "");
   }

   public String chatModerationApiKey() {
      return this.resolveConfigValue("chat.moderation.api-key", "SPVP_CHAT_MODERATION_API_KEY", "");
   }

   public String chatModerationLanguage() {
      String fromEnv = System.getenv("CHAT_MODERATION_LANGUAGE");
      if (fromEnv != null && !fromEnv.isBlank()) {
         return fromEnv.trim();
      }
      return this.getConfig().getString("chat.moderation.language", "eng");
   }

   public double chatModerationCategory3Threshold() {
      return this.resolveConfigDouble("chat.moderation.category3-threshold", "CHAT_MODERATION_CATEGORY3_THRESHOLD", 0.75D);
   }

   public boolean chatModerationHonorReviewRecommended() {
      return this.resolveConfigBoolean(
              "chat.moderation.honor-review-recommended",
              "CHAT_MODERATION_HONOR_REVIEW_RECOMMENDED",
              true
      );
   }

   public int chatModerationFlagsBeforeWarn() {
      return this.resolveConfigInt("chat.moderation.flags-before-warn", "CHAT_MODERATION_FLAGS_BEFORE_WARN", 2);
   }

   public int chatModerationWarnsBeforeMute() {
      return this.resolveConfigInt("chat.moderation.warns-before-mute", "CHAT_MODERATION_WARNS_BEFORE_MUTE", 2);
   }

   public int chatModerationMuteDurationSeconds() {
      return this.resolveConfigInt("chat.moderation.mute-duration-seconds", "CHAT_MODERATION_MUTE_DURATION_SECONDS", 3600);
   }

   public int chatModerationContentSafetyMinSeverity() {
      return this.resolveConfigInt(
              "chat.moderation.content-safety-min-severity",
              "CHAT_MODERATION_CONTENT_SAFETY_MIN_SEVERITY",
              2
      );
   }

   public boolean chatTranslationEnabled() {
      return this.resolveConfigBoolean("chat.translation.enabled", "SPVP_CHAT_TRANSLATION_ENABLED", false);
   }

   public String chatTranslationEndpoint() {
      String dedicated = this.resolveConfigValue(
              "chat.translation.endpoint",
              "SPVP_CHAT_TRANSLATION_ENDPOINT",
              ""
      );
      if (!dedicated.isBlank()) {
         return dedicated;
      }
      return "https://api.cognitive.microsofttranslator.com";
   }

   public String chatTranslationApiKey() {
      String dedicated = this.resolveConfigValue("chat.translation.api-key", "SPVP_CHAT_TRANSLATION_API_KEY", "");
      if (!dedicated.isBlank()) {
         return dedicated;
      }
      return this.chatModerationApiKey();
   }

   public String chatTranslationRegion() {
      return this.resolveConfigValue("chat.translation.region", "SPVP_CHAT_TRANSLATION_REGION", "");
   }

   private String resolveConfigValue(String configPath, String envVar, String defaultValue) {
      String fromEnv = System.getenv(envVar);
      if (fromEnv != null && !fromEnv.isBlank()) {
         return ChatTranslationDiagnostics.sanitizeEnv(fromEnv);
      }
      String fromConfig = this.getConfig().getString(configPath);
      if (fromConfig != null && !fromConfig.isBlank() && !fromConfig.contains("${")) {
         return ChatTranslationDiagnostics.sanitizeEnv(fromConfig);
      }
      return defaultValue;
   }

   private int resolveConfigInt(String configPath, String envVar, int defaultValue) {
      String fromEnv = System.getenv(envVar);
      if (fromEnv != null && !fromEnv.isBlank()) {
         try {
            return Integer.parseInt(fromEnv.trim());
         } catch (NumberFormatException ex) {
            this.getLogger().warning("Invalid " + envVar + " '" + fromEnv + "'; using config/default.");
         }
      }
      return this.getConfig().getInt(configPath, defaultValue);
   }

   private boolean resolveConfigBoolean(String configPath, String envVar, boolean defaultValue) {
      String fromEnv = System.getenv(envVar);
      if (fromEnv != null && !fromEnv.isBlank()) {
         return "true".equalsIgnoreCase(fromEnv.trim()) || "1".equals(fromEnv.trim());
      }
      String fromConfig = this.getConfig().getString(configPath);
      if (fromConfig != null && !fromConfig.isBlank() && !fromConfig.contains("${")) {
         return this.getConfig().getBoolean(configPath, defaultValue);
      }
      return defaultValue;
   }

   private double resolveConfigDouble(String configPath, String envVar, double defaultValue) {
      String fromEnv = System.getenv(envVar);
      if (fromEnv != null && !fromEnv.isBlank()) {
         try {
            return Double.parseDouble(fromEnv.trim());
         } catch (NumberFormatException ex) {
            this.getLogger().warning("Invalid " + envVar + " '" + fromEnv + "'; using config/default.");
         }
      }
      return this.getConfig().getDouble(configPath, defaultValue);
   }

   private RedisEventPublisher createRedisPublisher() {
      if (!this.isRedisEnabled()) {
         this.getLogger().info("Redis integration is disabled in config.yml; heartbeat publishing is off.");
         return null;
      } else {
         String host = this.redisHost();
         int port = this.redisPort();
         String password = this.redisPassword();
         int database = this.redisDatabase();

         try {
            return new RedisEventPublisher(new RedisConnectionSettings(host, port, password, database));
         } catch (RuntimeException var6) {
            this.getLogger().warning("Failed to initialize Redis publisher: " + var6.getMessage());
            return null;
         }
      }
   }

   private DatabaseManager createDatabaseManager() {
      if (!this.isPostgresEnabled()) {
         this.getLogger().info("PostgreSQL integration is disabled; SQL features are off.");
         return null;
      } else {
         String host = this.postgresHost();
         int port = this.postgresPort();
         String database = this.postgresDatabase();
         String username = this.postgresUsername();
         String password = this.postgresPassword();
         PostgresConnectionSettings settings = new PostgresConnectionSettings(host, port, database, username, password);

         try {
            DatabaseManager manager = new DatabaseManager(
               settings.host(),
               settings.port(),
               settings.database(),
               settings.username(),
               settings.password(),
               "SkyPvPPaperCore",
               10
            );
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
               try {
                  manager.validateConnection();
                  manager.runMigrations();
                  this.getLogger().info(
                        "PostgreSQL connected (host=" + settings.host() + ":" + settings.port()
                              + ", database=" + settings.database() + ", attempt=" + attempt + ") and migrations applied."
                  );
                  return manager;
               } catch (RuntimeException failure) {
                  lastFailure = failure;
                  this.getLogger().warning(
                        "PostgreSQL connect attempt " + attempt + "/3 failed: " + failure.getMessage()
                  );
                  if (attempt < 3) {
                     try {
                        Thread.sleep(2000L * attempt);
                     } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                     }
                  }
               }
            }
            throw lastFailure == null ? new RuntimeException("PostgreSQL connect failed") : lastFailure;
         } catch (RuntimeException var9) {
            Throwable cause = (Throwable)(var9.getCause() != null ? var9.getCause() : var9);
            this.getLogger().warning("Failed to connect to PostgreSQL: " + var9.getMessage() + ": " + cause.getMessage());
            return null;
         }
      }
   }

   private NetworkServerDirectoryRepository createNetworkServerDirectoryRepository() {
      return this.databaseManager == null ? null : new NetworkServerDirectoryRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
   }

   private PlayerSessionService createSessionService() {
      if (this.databaseManager == null) {
         return null;
      } else {
         PlayerSessionRepository repository = new PlayerSessionRepository(this.databaseManager, this.getLogger(), this.asyncDbExecutor());
         return new PlayerSessionService(repository, this.getLogger());
      }
   }

   private RankService createRankService() {
      return new RankService(this, this.getLogger());
   }



   private void scheduleHeartbeat() {
      int intervalTicks = 200;
      this.publishRoutingHeartbeat();
      this.heartbeatTask = this.platform.runGlobalTimer(
            () -> this.publishRoutingHeartbeat(),
            40L,
            Math.max(20L, (long)intervalTicks)
         );
   }

   private void registerShutdownHeartbeatHook() {
      if (this.redisPublisher == null || this.shutdownHeartbeatHook != null) {
         return;
      }
      this.shutdownHeartbeatHook = new Thread(this::publishNotJoinableHeartbeatNow, "SkyPvP-shutdown-heartbeat");
      Runtime.getRuntime().addShutdownHook(this.shutdownHeartbeatHook);
   }

   private boolean isJoinableForRoutingHeartbeat() {
      return (this.worldStateService == null || this.worldStateService.isJoinableForRouting())
         && (this.serverLifecycleService == null || !this.serverLifecycleService.isCountdownActive())
         && (this.gracefulDrainService == null || !this.gracefulDrainService.isDraining());
   }

   private void publishRoutingHeartbeat() {
      this.publishHeartbeat(this.isJoinableForRoutingHeartbeat(), false);
   }

   public void publishNotJoinableHeartbeatNow() {
      if (this.redisPublisher == null) {
         return;
      }
      this.publishHeartbeat(false, true);
      this.getLogger().info("[SkyPvPCore] Published not-joinable heartbeat (server shutting down or draining).");
   }

   private void publishHeartbeat(boolean joinable, boolean forceZeroPlayers) {
      if (this.redisPublisher != null) {
         String heartbeatChannel = "skypvp:network:heartbeats";
         int onlinePlayers = forceZeroPlayers ? 0 : this.getServer().getOnlinePlayers().size();
         int maxPlayers = this.getServer().getMaxPlayers();
         int openBreachSlots = 0;
         int activeBreaches = 0;
         int queuedPlayers = 0;
         int maxPlayersPerPod = 0;
         BreachCapacityProvider capacityProvider = this.getServer().getServicesManager().load(BreachCapacityProvider.class);
         if (capacityProvider != null) {
            openBreachSlots = capacityProvider.openBreachSlots();
            activeBreaches = capacityProvider.activeBreaches();
            queuedPlayers = capacityProvider.queuedPlayers();
            maxPlayersPerPod = capacityProvider.maxPlayersPerPod();
         }

         try {
            this.redisPublisher
               .publishJson(
                  heartbeatChannel,
                  new ServerHeartbeatEvent(
                     this.serverId,
                     this.serverRole,
                     onlinePlayers,
                     maxPlayers,
                     joinable,
                     System.currentTimeMillis(),
                     this.lifecycleSource,
                     this.orchestrationGeneration,
                     this.advertisedHost != null && !this.advertisedHost.isBlank() ? this.advertisedHost : null,
                     this.advertisedPort,
                     openBreachSlots,
                     activeBreaches,
                     queuedPlayers,
                     maxPlayersPerPod
                  )
               );
         } catch (RuntimeException var9) {
            long now = System.currentTimeMillis();
            if (now - this.lastRedisWarningEpochMillis >= 30000L) {
               this.getLogger().warning("Heartbeat publish skipped: Redis unavailable: " + var9.getMessage());
               this.lastRedisWarningEpochMillis = now;
            }
         }
      }
   }

   public void publishJoinableHeartbeatNow() {
      this.publishRoutingHeartbeat();
   }
}
