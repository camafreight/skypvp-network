package network.skypvp.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import network.skypvp.proxy.command.FriendCommand;
import network.skypvp.proxy.command.PartyCommand;
import network.skypvp.proxy.command.ProxyBroadcastCommand;
import network.skypvp.proxy.command.ProxyDynamicServerTestCommand;
import network.skypvp.proxy.command.ProxyHubCommand;
import network.skypvp.proxy.command.ProxyPlayCommand;
import network.skypvp.proxy.command.ProxyMessageCommand;
import network.skypvp.proxy.command.ProxyReplyCommand;
import network.skypvp.proxy.command.ProxySendCommand;
import network.skypvp.proxy.command.ProxyServerInfoCommand;
import network.skypvp.proxy.command.ProxyServerCommand;
import network.skypvp.proxy.command.ProxyServerLifecycleCommand;
import network.skypvp.proxy.command.ProxyStatusCommand;
import network.skypvp.proxy.command.QueueCommand;
import network.skypvp.proxy.command.ProxyMaintenanceCommand;
import network.skypvp.proxy.command.ProxyPresetCommand;
import network.skypvp.proxy.command.ProxyPoolStatusCommand;
import network.skypvp.proxy.command.ProxyPunishCommand;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.proxy.chat.ProxyChatFormatRepository;
import network.skypvp.proxy.chat.ProxyChatFormatService;
import network.skypvp.proxy.integration.RedisNetworkEventSubscriber;
import network.skypvp.proxy.listener.BackendServerListener;
import network.skypvp.proxy.listener.AuthenticatedLoginListener;
import network.skypvp.proxy.listener.FriendPresenceListener;
import network.skypvp.proxy.listener.BanLoginListener;
import network.skypvp.proxy.listener.BreachDisconnectedReconnectListener;
import network.skypvp.proxy.listener.InitialServerSelectionListener;
import network.skypvp.proxy.listener.MaintenanceLoginListener;

import network.skypvp.proxy.listener.PartyAtomicAdmissionListener;
import network.skypvp.proxy.listener.PartyFollowLeaderListener;
import network.skypvp.proxy.listener.PartyNavigationGuardListener;
import network.skypvp.proxy.listener.PartyProxyLifecycleListener;
import network.skypvp.proxy.listener.ProxyConnectionListener;
import network.skypvp.proxy.listener.ProxyLimboLifecycleListener;
import network.skypvp.proxy.listener.ProxyLimboLoginListener;
import network.skypvp.proxy.listener.ProxyPingListener;
import network.skypvp.proxy.listener.ProxyResourcePackListener;
import network.skypvp.proxy.listener.ProxyRouteRequestListener;
import network.skypvp.proxy.listener.ProxySocialRequestListener;
import network.skypvp.proxy.listener.QueueDisconnectListener;
import network.skypvp.proxy.listener.ReconnectFloodGuard;
import network.skypvp.proxy.listener.VersionCompatibilityGateListener;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.registry.PrivateMessageRegistry;
import network.skypvp.proxy.resourcepack.ProxyResourcePackService;
import network.skypvp.proxy.service.ProxyChatModerationService;
import network.skypvp.proxy.service.PlayerLocaleService;
import network.skypvp.proxy.service.ProxyChatTranslationDeliveryService;
import network.skypvp.proxy.service.ProxyPrivateMessageService;
import network.skypvp.proxy.service.Fabric8KubernetesApiService;
import network.skypvp.proxy.service.KubernetesApiService;
import network.skypvp.proxy.service.KubernetesAutoscalerService;
import network.skypvp.proxy.service.ServerDrainMigrationService;

import network.skypvp.proxy.repository.FriendRepository;
import network.skypvp.proxy.repository.PartyRepository;
import network.skypvp.proxy.repository.PlayerSocialSettingsRepository;
import network.skypvp.proxy.repository.PunishmentRepository;
import network.skypvp.proxy.repository.ServerRegistryRepository;
import network.skypvp.proxy.repository.WorldPresetRegistryRepository;
import network.skypvp.proxy.service.AdmissionControlService;

import network.skypvp.proxy.service.BreachPlayMatchmakingService;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;
import network.skypvp.proxy.service.ProxyDestinationRouter;
import network.skypvp.proxy.service.ProxyHoldService;
import network.skypvp.proxy.service.ProxyLimboService;

import network.skypvp.proxy.service.QueueDrainService;
import network.skypvp.proxy.service.QueueService;
import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.proxy.state.ServerLifecycleState;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.RedisEventPublisher;
import network.skypvp.shared.chat.ChatModerationSettings;
import network.skypvp.shared.chat.TextModerationClient;
import org.slf4j.Logger;

@Plugin(
   id = "skypvp-proxy-core",
   name = "SkyPvP Proxy Core",
   version = "0.1.0-SNAPSHOT",
   description = "Core Velocity services for the SkyPvP network",
   authors = {"SkyPvP"},
   dependencies = {@Dependency(
      id = "luckyfeed-limbo",
      optional = true
   ), @Dependency(
      id = "luckperms",
      optional = true
   )}
)
public final class ProxyBootstrap {
   private final ProxyServer proxyServer;
   private final Logger logger;
   private final Path dataDirectory;
   private RedisEventPublisher redisPublisher;
   private RedisNetworkEventSubscriber redisSubscriber;
   private NetworkStateRegistry networkStateRegistry;
   private ProxyBootstrapConfig config;
   private ServerRegistryRepository serverRegistryRepository;
   private ServerRoutingService serverRoutingService;
   private QueueService queueService;
   private QueueDrainService queueDrainService;
   private AdmissionControlService admissionControlService;
   private PrivateMessageRegistry privateMessageRegistry;
   private MaintenanceRegistry maintenanceRegistry;
   private ProxyHoldService proxyHoldService;
   private PunishmentRepository punishmentRepository;
   private FriendRepository friendRepository;
   private PlayerSocialSettingsRepository playerSocialSettingsRepository;
   private ProxyChatFormatService proxyChatFormatService;
   private ProxyChatModerationService proxyChatModerationService;
   private ProxyPrivateMessageService proxyPrivateMessageService;
   private PlayerLocaleService playerLocaleService;
   private ProxyChatTranslationDeliveryService proxyChatTranslationDeliveryService;
   private PartyRepository partyRepository;
   private PartyService partyService;
   private PartyQueueService partyQueueService;
   private PartyTransferGate partyTransferGate;
   private PartyMemberMover partyMemberMover;
   private BreachPlayMatchmakingService breachPlayMatchmakingService;
   private WorldPresetRegistryRepository worldPresetRegistryRepository;
   private DatabaseManager proxyDataSource;

   private ScheduledTask queueDrainTask;
   private ScheduledTask partyQueueDrainTask;
   private ScheduledTask lifecycleSweepTask;
   private ScheduledTask dynamicBackendSweepTask;
   private ScheduledTask autoscalerTask;
   private ScheduledTask resourcePackTimeoutTask;
   private ScheduledTask resourcePackMetaRefreshTask;
   private ProxyResourcePackService resourcePackService;

   @Inject
   public ProxyBootstrap(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
      this.proxyServer = proxyServer;
      this.logger = logger;
      this.dataDirectory = dataDirectory;
   }

   @Subscribe
   public void onProxyInitialization(ProxyInitializeEvent event) {
      this.config = this.loadConfig();
      this.config.normalizeDefaults();
      this.resourcePackService = new ProxyResourcePackService(
         this.proxyServer,
         this.logger,
         this.dataDirectory,
         this.config.resourcePack
      );
      this.resourcePackService.start();
      this.networkStateRegistry = new NetworkStateRegistry();
      this.networkStateRegistry.setConfig(this.config);
      this.serverRoutingService = new ServerRoutingService(this.proxyServer, this.networkStateRegistry, this.config, null);
      this.queueService = this.config.redisEnabled ? new QueueService(this.config.redis) : new QueueService();
      this.admissionControlService = new AdmissionControlService(this.config.queueTransferRatePerSecond, this.config.queueTransferBurstCapacity);
      this.partyService = new PartyService();
      this.privateMessageRegistry = new PrivateMessageRegistry();
      this.maintenanceRegistry = new MaintenanceRegistry();
      if (this.config.postgres != null && this.config.postgres.enabled) {
         try {
            this.proxyDataSource = new DatabaseManager(this.config.postgres.host, this.config.postgres.port, this.config.postgres.database, this.config.postgres.username, this.config.postgres.password, "SkyPvPProxy", 10);
            this.serverRegistryRepository = new ServerRegistryRepository(this.proxyDataSource, this.logger);
            this.punishmentRepository = new PunishmentRepository(this.proxyDataSource, this.logger);
            this.playerSocialSettingsRepository = new PlayerSocialSettingsRepository(this.proxyDataSource, this.logger);
            this.proxyChatFormatService = new ProxyChatFormatService(
               new ProxyChatFormatRepository(this.proxyDataSource, this.logger),
               this.logger
            );
            this.friendRepository = new FriendRepository(this.proxyDataSource, this.logger, this.playerSocialSettingsRepository);
            this.partyRepository = new PartyRepository(this.proxyDataSource, this.logger);
            this.partyService = new PartyService(this.partyRepository, this.playerSocialSettingsRepository);
            this.worldPresetRegistryRepository = new WorldPresetRegistryRepository(this.proxyDataSource, this.logger);
            this.serverRoutingService = new ServerRoutingService(this.proxyServer, this.networkStateRegistry, this.config, this.serverRegistryRepository);
         } catch (Exception var18) {
            this.logger.warn("Failed to initialize proxy PostgreSQL repositories: {}", var18.getMessage());
            this.proxyDataSource = null;
            this.serverRegistryRepository = null;
            this.punishmentRepository = null;
            this.friendRepository = null;
            this.playerSocialSettingsRepository = null;
            this.proxyChatFormatService = null;
            this.partyRepository = null;
            this.worldPresetRegistryRepository = null;
         }
      }

      this.partyTransferGate = new PartyTransferGate();
      if (this.config.redisEnabled) {
         this.redisPublisher = new RedisEventPublisher(this.config.redis);
      }
      this.partyQueueService = new PartyQueueService(this.proxyServer, this.serverRoutingService, this.partyTransferGate, this.redisPublisher, this.logger);
      this.partyMemberMover = new PartyMemberMover(this.proxyServer, this.partyTransferGate, this.partyQueueService, this.serverRoutingService);
      this.breachPlayMatchmakingService = new BreachPlayMatchmakingService(
         this.proxyServer,
         this.serverRoutingService,
         this.partyService,
         this.partyTransferGate,
         this.redisPublisher,
         this.logger
      );
      this.partyQueueService.bindBreachPlayMatchmaking(this.breachPlayMatchmakingService);
      this.playerLocaleService = new PlayerLocaleService();
      this.proxyServer.getEventManager().register(this, new network.skypvp.proxy.listener.PlayerLocaleListener(this.playerLocaleService));
      network.skypvp.shared.chat.ChatTranslator azureTranslator = network.skypvp.shared.chat.ChatTranslatorFactory.createAzure(
         this.chatTranslationEndpoint(),
         this.chatTranslationApiKey(),
         this.chatTranslationRegion(),
         java.util.logging.Logger.getLogger("SkyPvP-Proxy-Translation")
      );
      network.skypvp.shared.chat.ChatTranslationService chatTranslationService =
         new network.skypvp.shared.chat.ChatTranslationService(
            azureTranslator,
            java.util.logging.Logger.getLogger("SkyPvP-Proxy-Translation"),
            "proxy"
         );
      network.skypvp.shared.chat.ChatTranslationDiagnostics.logStartup(
         java.util.logging.Logger.getLogger("SkyPvP-Proxy-Translation"),
         this.chatTranslationEnabled(),
         azureTranslator
      );
      if (this.chatTranslationEnabled()) {
         if (azureTranslator.enabled()) {
            this.logger.info(
               "[chat-translation] Enabled with " + azureTranslator.providerId() + " configured."
            );
         } else {
            this.logger.warn(
               "[chat-translation] Enabled but "
                  + azureTranslator.providerId()
                  + " is not configured; auto-translate will not run."
            );
         }
      } else {
         this.logger.info("[chat-translation] Disabled.");
      }
      this.proxyChatTranslationDeliveryService = new ProxyChatTranslationDeliveryService(
         chatTranslationService,
         this.playerLocaleService,
         this.playerSocialSettingsRepository
      );
      TextModerationClient moderationClient = new TextModerationClient(
         this.envOrDefault("SPVP_CHAT_MODERATION_ENDPOINT", ""),
         this.envOrDefault("SPVP_CHAT_MODERATION_API_KEY", ""),
         this.envIntOrDefault("CHAT_MODERATION_CONTENT_SAFETY_MIN_SEVERITY", 2),
         java.util.logging.Logger.getLogger("SkyPvP-Proxy-Moderation")
      );
      if (this.config.chatModeration.enabled()) {
         if (moderationClient.enabled()) {
            this.logger.info("[chat-moderation] Enabled with Azure endpoint configured.");
         } else {
            this.logger.warn("[chat-moderation] Enabled but endpoint/api-key missing; messages will not be moderated.");
         }
      } else {
         this.logger.info("[chat-moderation] Disabled.");
      }
      this.proxyChatModerationService = new ProxyChatModerationService(
         this.punishmentRepository,
         moderationClient,
         this.config.chatModeration
      );
      this.proxyPrivateMessageService = new ProxyPrivateMessageService(
         this.proxyServer,
         this.privateMessageRegistry,
         this.networkStateRegistry,
         this.proxyChatFormatService,
         this.redisPublisher,
         this.proxyChatModerationService,
         this.proxyChatTranslationDeliveryService,
         this.logger
      );
      if (this.config.redisEnabled) {
         this.redisSubscriber = new RedisNetworkEventSubscriber(
            this.config.redis,
            this.config.sessionChannel,
            this.config.heartbeatChannel,
            this.config.rankChannel,
            this.networkStateRegistry,
            this.serverRegistryRepository,
            this.proxyServer,
            this.logger,
            this.serverRoutingService,
            this.playerSocialSettingsRepository,
            this.proxyChatFormatService,
            this.proxyPrivateMessageService,
            this.proxyChatTranslationDeliveryService
         );
         this.redisSubscriber.start();
      } else {
         this.logger.info("Redis integration is disabled; session/heartbeat streaming is off.");
      }

      this.queueDrainService = new QueueDrainService(
         this.proxyServer,
         this.queueService,
         this.serverRoutingService,
         this.proxyHoldService,
         this.admissionControlService,
         this.config.queueDrainMaxPerTick,
         this.maintenanceRegistry,
         this.redisPublisher,
         this.config.queueChannel,
         this.logger
      );
      if (this.config.limbo.enabled) {
         if (this.proxyServer.getPluginManager().getPlugin("luckyfeed-limbo").isPresent()) {
            this.proxyHoldService = new ProxyLimboService(
               this,
               this.proxyServer,
               this.logger,
               this.config,
               this.maintenanceRegistry,
               this.serverRoutingService,
               this.queueService
            );
            if (this.redisSubscriber != null) {
               this.redisSubscriber.setProxyHoldService(this.proxyHoldService);
            }
            this.queueDrainService = new QueueDrainService(
               this.proxyServer,
               this.queueService,
               this.serverRoutingService,
               this.proxyHoldService,
               this.admissionControlService,
               this.config.queueDrainMaxPerTick,
               this.maintenanceRegistry,
               this.redisPublisher,
               this.config.queueChannel,
               this.logger
            );
         } else {
            this.logger
               .warn("Proxy limbo is enabled but SkyPvP Limbo Core is not installed on the proxy; waiting-room handoff is disabled.");
         }
      }

      this.registerCommand();
      ProxyDestinationRouter destinationRouter = new ProxyDestinationRouter(
         this.proxyServer, this.serverRoutingService, this.queueService, this.logger
      );
      this.proxyServer.getChannelRegistrar().register(new ChannelIdentifier[]{MinecraftChannelIdentifier.from("skypvp:route")});
      this.proxyServer.getChannelRegistrar().register(new ChannelIdentifier[]{MinecraftChannelIdentifier.from("skypvp:social")});
      this.proxyServer.getChannelRegistrar().register(new ChannelIdentifier[]{MinecraftChannelIdentifier.from("skypvp:menu")});
      this.proxyServer
         .getEventManager()
         .register(this, new ProxyConnectionListener(this.logger, this.config, this.redisPublisher, this.networkStateRegistry, this.playerSocialSettingsRepository, this.privateMessageRegistry));
      if (this.friendRepository != null) {
         this.proxyServer.getEventManager().register(
            this,
            new FriendPresenceListener(this.proxyServer, this, this.friendRepository, this.networkStateRegistry, this.logger)
         );
      }
      this.proxyServer.getEventManager().register(this, new ProxyResourcePackListener(this.resourcePackService));
      this.resourcePackTimeoutTask = this.proxyServer.getScheduler()
         .buildTask(this, () -> this.resourcePackService.enforceTimeouts())
         .repeat(1L, TimeUnit.SECONDS)
         .schedule();
      if (this.resourcePackService.usesRemoteMeta()) {
         long refreshSeconds = this.resourcePackService.metaRefreshSeconds();
         this.resourcePackMetaRefreshTask = this.proxyServer.getScheduler()
            .buildTask(this, () -> this.resourcePackService.refreshFromMeta())
            .delay(refreshSeconds, TimeUnit.SECONDS)
            .repeat(refreshSeconds, TimeUnit.SECONDS)
            .schedule();
         this.logger.info("[resource-pack] Meta refresh every {}s", refreshSeconds);
      }
      this.proxyServer
         .getEventManager()
         .register(this, new BackendServerListener(this.serverRoutingService, this.proxyHoldService, this.queueService, this.logger));
      this.proxyServer
         .getEventManager()
         .register(this, new ProxyRouteRequestListener(
            this.proxyServer,
            destinationRouter,
            this.partyService,
            this.partyMemberMover,
            this.breachPlayMatchmakingService,
            this.logger
         ));
      this.proxyServer.getEventManager().register(this, new ProxySocialRequestListener(this.proxyServer, this.logger));
      this.proxyServer.getEventManager().register(this, new InitialServerSelectionListener(this.serverRoutingService));
      this.proxyServer.getEventManager().register(
         this,
         new BreachDisconnectedReconnectListener(this.serverRoutingService, this.networkStateRegistry, this.logger)
      );
      this.proxyServer.getEventManager().register(this, new VersionCompatibilityGateListener(this.config, this.logger));
      this.proxyServer.getEventManager().register(this, new QueueDisconnectListener(this.queueService, this.partyQueueService));
      this.proxyServer
         .getEventManager()
         .register(this, new PartyAtomicAdmissionListener(this.proxyServer, this.partyService, this.partyQueueService, this.serverRoutingService));
      this.proxyServer
         .getEventManager()
         .register(this, new PartyFollowLeaderListener(this.proxyServer, this.partyService, this.partyMemberMover));
      this.proxyServer.getEventManager().register(this, new PartyNavigationGuardListener(this.proxyServer, this.partyService, this.partyTransferGate));
      this.proxyServer.getEventManager().register(
         this,
         new PartyProxyLifecycleListener(this.proxyServer, this.partyService, this.partyTransferGate, this.breachPlayMatchmakingService)
      );
      // Custom ranks removed

      this.proxyServer.getEventManager().register(this, new ProxyPingListener(this.proxyServer, this.config, this.maintenanceRegistry, this.networkStateRegistry, this.logger));

      this.proxyServer
         .getEventManager()
         .register(this, new MaintenanceLoginListener(this.maintenanceRegistry, this.proxyHoldService, this.logger));
      if (this.proxyHoldService != null && this.proxyHoldService.available()) {
         this.proxyServer.getEventManager().register(this, new ProxyLimboLoginListener(this.proxyHoldService));
         this.proxyServer.getEventManager().register(this, new ProxyLimboLifecycleListener(this.proxyHoldService));
         this.logger.info("Proxy limbo layer registered: global hold routing is active for login, outage, and maintenance flows.");
      }

      if (this.punishmentRepository != null) {
         this.proxyServer.getEventManager().register(this, new BanLoginListener(this.proxyServer, this.punishmentRepository));
         this.logger.info("Proxy punishment system registered: ban enforcement active at login gate.");
      } else {
         this.logger.info("Punishment system disabled: PostgreSQL not configured.");
      }

      this.proxyServer.getEventManager().register(this, new AuthenticatedLoginListener(this.config));
      if (this.config.requireAuthenticatedAccounts) {
         this.logger.info("Authenticated login gate active: offline Java UUIDs are rejected.");
      } else {
         this.logger.info("Authenticated login gate disabled (SPVP_REQUIRE_AUTHENTICATED_ACCOUNTS=false).");
      }

      this.proxyServer.getEventManager().register(this, new ReconnectFloodGuard(java.util.logging.Logger.getLogger("SkyPvP.FloodGuard")));
      this.logger.info("ReconnectFloodGuard active: max 5 connects per 10s per IP.");
      ProxyBroadcastCommand broadcastCmd = new ProxyBroadcastCommand(this.proxyServer, this.networkStateRegistry);
      ProxySendCommand sendCmd = new ProxySendCommand(
         this.proxyServer, this.networkStateRegistry, this.serverRoutingService, this.logger
      );
      ProxyPlayCommand partyAwarePlayCmd = new ProxyPlayCommand(
         this.proxyServer, this.serverRoutingService, this.proxyHoldService, this.partyService, this.partyQueueService, this.partyMemberMover
      );
      ProxyHubCommand hubCmd = new ProxyHubCommand(this.proxyServer, destinationRouter);
      ProxyMessageCommand msgCmd = new ProxyMessageCommand(this.proxyServer, this.proxyPrivateMessageService);
      ProxyReplyCommand replyCmd = new ProxyReplyCommand(this.proxyServer, this.privateMessageRegistry, msgCmd);
      ProxyMaintenanceCommand maintenanceCmd = new ProxyMaintenanceCommand(this.proxyServer, this.maintenanceRegistry, this.networkStateRegistry);
      QueueCommand queueCmd = new QueueCommand(
         this.proxyServer,
         this.config,
         this.queueService,
         this.queueDrainService,
         this.serverRoutingService,
         this.partyService,
         this.partyQueueService,
         this.networkStateRegistry,
         this.friendRepository,
         this.redisPublisher
      );
      ProxyServerCommand serverCmd = new ProxyServerCommand(this.proxyServer, this.networkStateRegistry);
      ProxyServerInfoCommand serverInfoCmd = new ProxyServerInfoCommand(
         this.proxyServer, this.serverRoutingService, this.serverRegistryRepository, this.networkStateRegistry
      );
      ProxyServerLifecycleCommand serverLifecycleCmd = null;
      if (this.serverRegistryRepository != null) {
         serverLifecycleCmd = new ProxyServerLifecycleCommand(
            this.proxyServer, this.serverRegistryRepository, this.serverRoutingService, this.networkStateRegistry
         );
      }

      ProxyPresetCommand presetCmd = null;
      if (this.worldPresetRegistryRepository != null) {
         presetCmd = new ProxyPresetCommand(this.worldPresetRegistryRepository, this.networkStateRegistry);
      }

      KubernetesApiService kubeApiService = new Fabric8KubernetesApiService(this.logger);
      if (this.redisSubscriber != null) {
         this.redisSubscriber.setDrainMigrationService(
            new ServerDrainMigrationService(
               this,
               this.proxyServer,
               this.serverRoutingService,
               this.proxyHoldService,
               kubeApiService,
               this.logger
            )
         );
      }
      ProxyDynamicServerTestCommand dynTestCmd = new ProxyDynamicServerTestCommand(this.proxyServer, this.networkStateRegistry, kubeApiService);
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("broadcast").aliases(new String[]{"bc"}).plugin(this).build(), broadcastCmd.build());
      this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("send").plugin(this).build(), sendCmd.build());
      this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("play").plugin(this).build(), partyAwarePlayCmd.build());
      this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("hub").plugin(this).build(), hubCmd.build());
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("msg").aliases(new String[]{"tell", "whisper"}).plugin(this).build(), msgCmd.build());
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("reply").aliases(new String[]{"r"}).plugin(this).build(), replyCmd.build());
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("maintenance").plugin(this).build(), maintenanceCmd.build());
      this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("queue").plugin(this).build(), queueCmd.build());
      this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("server").plugin(this).build(), serverCmd.build());
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("scservers").aliases(new String[]{"serverinfo"}).plugin(this).build(), serverInfoCmd.build());
      if (this.proxyHoldService instanceof ProxyLimboService limboService) {
         limboService.registerCommand(List.of("play"), partyAwarePlayCmd.build());
         limboService.registerCommand(List.of("hub"), hubCmd.build());
         limboService.registerCommand(List.of("queue"), queueCmd.build());
         limboService.registerCommand(List.of("server"), serverCmd.build());
      }

      if (this.friendRepository != null) {
         FriendCommand friendCmd = new FriendCommand(this.proxyServer, this.friendRepository);
         this.proxyServer
            .getCommandManager()
            .register(this.proxyServer.getCommandManager().metaBuilder("friend").aliases(new String[]{"f"}).plugin(this).build(), friendCmd.build());
      }

      PartyCommand partyCmd = new PartyCommand(
         this.proxyServer,
         this.partyService,
         this.partyMemberMover,
         this.partyTransferGate,
         this.breachPlayMatchmakingService
      );
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("party").aliases(new String[]{"p"}).plugin(this).build(), partyCmd.build());
      if (serverLifecycleCmd != null) {
         this.proxyServer
            .getCommandManager()
            .register(
               this.proxyServer.getCommandManager().metaBuilder("serverlifecycle").aliases(new String[]{"lflifecycle"}).plugin(this).build(),
               serverLifecycleCmd.build()
            );
      }

      if (presetCmd != null) {
         this.proxyServer
            .getCommandManager()
            .register(this.proxyServer.getCommandManager().metaBuilder("presetctl").aliases(new String[]{"lfpreset"}).plugin(this).build(), presetCmd.build());
      }

      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("dynserver").aliases(new String[]{"dyntest"}).plugin(this).build(), dynTestCmd.build());
      this.logger.info("Dynamic server management command /dynserver registered.");
      ProxyPoolStatusCommand poolStatusCmd = new ProxyPoolStatusCommand(
         this.serverRoutingService, this.queueService, this.networkStateRegistry
      );
      this.proxyServer
         .getCommandManager()
         .register(this.proxyServer.getCommandManager().metaBuilder("scpools").aliases(new String[]{"pools"}).plugin(this).build(), poolStatusCmd.build());
      this.logger.info("Pool status command /scpools registered.");
      if (this.punishmentRepository != null) {
         ProxyPunishCommand punishCmd = new ProxyPunishCommand(this.proxyServer, this.punishmentRepository, this.logger);
         this.proxyServer.getCommandManager().register(this.proxyServer.getCommandManager().metaBuilder("punish").plugin(this).build(), punishCmd.build());
         this.logger.info("Staff commands registered: /punish");
      }

      this.queueDrainTask = this.proxyServer
         .getScheduler()
         .buildTask(this, () -> this.queueDrainService.drainAllQueues())
         .repeat(2L, TimeUnit.SECONDS)
         .schedule();
      this.partyQueueDrainTask = this.proxyServer
         .getScheduler()
         .buildTask(this, () -> this.partyQueueService.drainAll())
         .repeat(2L, TimeUnit.SECONDS)
         .schedule();
      if (this.serverRegistryRepository != null) {
         this.lifecycleSweepTask = this.proxyServer.getScheduler().buildTask(this, this::sweepStaleLifecycle).repeat(10L, TimeUnit.SECONDS).schedule();
      }

      if (this.redisSubscriber != null) {
         this.dynamicBackendSweepTask = this.proxyServer
            .getScheduler()
            .buildTask(this, this::sweepStaleDynamicBackends)
            .repeat(10L, TimeUnit.SECONDS)
            .schedule();
      }

      this.proxyServer
         .getScheduler()
         .buildTask(this, this::sweepProxyMemoryCaches)
         .repeat(60L, TimeUnit.SECONDS)
         .schedule();


      this.logger.info("Proxy operations layer registered: MOTD, /broadcast, /send, /play, /msg, /reply, /maintenance.");
      this.logger.info("Proxy routing layer registered: dynamic initial routing, fallback routing, /serverinfo.");
      this.logger.info("Proxy queue layer registered: /queue and automatic queue draining.");
      this.logger
         .info(
            "Proxy admission throttle active: {} transfer(s)/s burst {} (max {} transfer(s) per drain pass).",
            this.config.queueTransferRatePerSecond,
            this.config.queueTransferBurstCapacity,
            this.config.queueDrainMaxPerTick
         );

      // Initialize Kubernetes API and Orchestrator to wake up core backends if they are down
      network.skypvp.proxy.service.KubernetesOrchestratorService k8sOrchestrator = 
          new network.skypvp.proxy.service.KubernetesOrchestratorService(this.logger, kubeApiService);
      this.proxyServer.getScheduler().buildTask(this, k8sOrchestrator::wakeUpCoreBackends).schedule();

      // Initialize Kubernetes Autoscaler
      KubernetesAutoscalerService autoscaler = new KubernetesAutoscalerService(this.logger, kubeApiService, this.serverRoutingService);
      this.autoscalerTask = this.proxyServer.getScheduler().buildTask(this, autoscaler)
          .repeat(10L, TimeUnit.SECONDS)
          .schedule();

      this.logger.info("Loaded proxy core for network '{}' with fallback '{}'", this.config.networkName, this.config.fallbackServer);
   }

   @Subscribe
   public void onProxyShutdown(ProxyShutdownEvent event) {
      if (this.resourcePackTimeoutTask != null) {
         this.resourcePackTimeoutTask.cancel();
         this.resourcePackTimeoutTask = null;
      }
      if (this.resourcePackMetaRefreshTask != null) {
         this.resourcePackMetaRefreshTask.cancel();
         this.resourcePackMetaRefreshTask = null;
      }
      if (this.resourcePackService != null) {
         this.resourcePackService.shutdown();
         this.resourcePackService = null;
      }
      if (this.redisSubscriber != null) {
         this.redisSubscriber.close();
      }

      if (this.queueDrainTask != null) {
         this.queueDrainTask.cancel();
      }

      if (this.partyQueueDrainTask != null) {
         this.partyQueueDrainTask.cancel();
      }

      if (this.lifecycleSweepTask != null) {
         this.lifecycleSweepTask.cancel();
      }

      if (this.dynamicBackendSweepTask != null) {
         this.dynamicBackendSweepTask.cancel();
      }

      if (this.autoscalerTask != null) {
         this.autoscalerTask.cancel();
      }

      if (this.queueService != null) {
         this.queueService.close();
      }

      if (this.proxyHoldService != null) {
         this.proxyHoldService.close();
      }

      if (this.redisPublisher != null) {
         this.redisPublisher.close();
      }

      // Custom ranks removed

      if (this.friendRepository != null) {
         this.friendRepository.close();
      }

      if (this.partyRepository != null) {
         this.partyRepository.close();
      }

      if (this.serverRegistryRepository != null) {
         this.serverRegistryRepository.close();
      }

      if (this.worldPresetRegistryRepository != null) {
         this.worldPresetRegistryRepository.close();
      }



      if (this.proxyDataSource != null) {
         this.proxyDataSource.close();
      }
   }

   public NetworkStateRegistry networkStateRegistry() {
      return this.networkStateRegistry;
   }

   public ProxyBootstrapConfig config() {
      return this.config;
   }

   public ProxyServer proxyServer() {
      return this.proxyServer;
   }

   private void registerCommand() {
      CommandManager commandManager = this.proxyServer.getCommandManager();
      CommandMeta commandMeta = commandManager.metaBuilder("skypvpproxy").aliases(new String[]{"skypvp"}).build();
      commandManager.register(
         commandMeta,
         new ProxyStatusCommand(this, this.networkStateRegistry, this.queueService, this.queueDrainService, this.partyService, this.partyQueueService)
      );
   }

   private void sweepStaleLifecycle() {
      if (this.serverRegistryRepository != null) {
         long cutoffMillis = System.currentTimeMillis() - Math.max(30000L, this.config.serverLifecycleStaleHeartbeatMillis);
         Instant cutoff = Instant.ofEpochMilli(cutoffMillis);
         long deletionCutoffMillis = System.currentTimeMillis() - 120000L;
         Instant deletionCutoff = Instant.ofEpochMilli(deletionCutoffMillis);

         for (ServerRegistryRepository.ServerRegistrySnapshot snapshot : this.serverRegistryRepository.snapshotAll()) {
            if (snapshot.lifecycleState() != ServerLifecycleState.OFFLINE
               && (snapshot.lastHeartbeatAt() == null || !snapshot.lastHeartbeatAt().isAfter(cutoff))) {
               this.serverRegistryRepository.markOffline(snapshot.serverId());
               this.serverRegistryRepository
                  .updateLifecycle(
                     snapshot.serverId(),
                     ServerLifecycleState.OFFLINE,
                     ServerLifecycleState.OFFLINE,
                     "stale heartbeat timeout",
                     "proxy-lifecycle-sweeper",
                     snapshot.orchestrationGeneration()
                  );
            } else if (snapshot.lifecycleState() == ServerLifecycleState.OFFLINE 
               && snapshot.lastHeartbeatAt() != null && !snapshot.lastHeartbeatAt().isAfter(deletionCutoff)) {
               boolean isConfigured = this.config.backendServers.stream().anyMatch(s -> s.serverId.equals(snapshot.serverId()));
               if (!isConfigured) {
                  this.serverRegistryRepository.deleteServer(snapshot.serverId());
                  this.logger.info("Deleted permanently OFFLINE dynamic server '{}' from database.", snapshot.serverId());
               }
            }
         }
      }
   }

   private void sweepStaleDynamicBackends() {
      if (this.redisSubscriber != null) {
         long cutoffMillis = System.currentTimeMillis() - Math.max(30000L, this.config.serverLifecycleStaleHeartbeatMillis);
         this.redisSubscriber.pruneStaleDynamicBackends(cutoffMillis);
      }
   }

   private void sweepProxyMemoryCaches() {
      if (this.partyService != null) {
         this.partyService.sweepStaleInvites();
      }
      if (this.breachPlayMatchmakingService != null) {
         this.breachPlayMatchmakingService.sweepExpiredPendingDeploys();
      }
      if (this.queueService != null) {
         this.queueService.sweepExpiredSwapConfirmations();
      }
      if (this.networkStateRegistry != null) {
         java.util.Set<java.util.UUID> onlineIds = this.proxyServer.getAllPlayers().stream()
            .map(player -> player.getUniqueId())
            .collect(java.util.stream.Collectors.toSet());
         this.networkStateRegistry.pruneOfflineSessions(onlineIds, 120_000L);
      }
   }

   private ProxyBootstrapConfig loadConfig() {
      ProxyBootstrapConfig config = ProxyBootstrapConfig.defaultConfig();
      config.networkName = "SkyPvP Network";
      config.externalAddress = "play.skypvp.net";
      config.fallbackServer = "lobby";
      config.requireHeartbeatForRouting = true;
      config.redisEnabled = true;
      config.redis = new RedisConnectionSettings(
         this.envOrDefault("SPVP_REDIS_HOST", "redis"),
         this.envIntOrDefault("SPVP_REDIS_PORT", 6379),
         this.envOrDefault("SPVP_REDIS_PASSWORD", ""),
         0
      );
      config.postgres = new ProxyBootstrapConfig.PostgresProxySettings();
      config.postgres.enabled = true;
      config.postgres.host = this.envOrDefault("SPVP_POSTGRES_HOST", "postgres");
      config.postgres.port = this.envIntOrDefault("SPVP_POSTGRES_PORT", 5432);
      config.postgres.database = this.envOrDefault("SPVP_POSTGRES_DATABASE", "skypvp_network");
      config.postgres.username = this.envOrDefault("SPVP_POSTGRES_USERNAME", "skypvp");
      config.postgres.password = this.envOrDefault("SPVP_POSTGRES_PASSWORD", "change-me");
      config.requireAuthenticatedAccounts = !"false".equalsIgnoreCase(
         this.envOrDefault("SPVP_REQUIRE_AUTHENTICATED_ACCOUNTS", "true")
      );
      config.resourcePack = new ProxyBootstrapConfig.ResourcePackSettings();
      config.resourcePack.enabled = !"false".equalsIgnoreCase(
         this.envOrDefault("SPVP_RESOURCE_PACK_ENABLED", "true")
      );
      config.resourcePack.force = !"false".equalsIgnoreCase(
         this.envOrDefault("SPVP_RESOURCE_PACK_FORCE", "true")
      );
      config.resourcePack.serveLocally = !"false".equalsIgnoreCase(
         this.envOrDefault("SPVP_RESOURCE_PACK_SERVE_LOCALLY", "true")
      );
      config.resourcePack.servePort = this.envIntOrDefault("SPVP_RESOURCE_PACK_SERVE_PORT", 8765);
      config.resourcePack.url = this.envOrDefault("SPVP_RESOURCE_PACK_URL", "");
      config.resourcePack.sha1 = this.envOrDefault("SPVP_RESOURCE_PACK_SHA1", "");
      config.resourcePack.metaUrl = this.envOrDefault("SPVP_RESOURCE_PACK_META_URL", "");
      config.resourcePack.metaRefreshSeconds = this.envLongOrDefault("SPVP_RESOURCE_PACK_META_REFRESH_SECONDS", 60L);
      config.resourcePack.publicHost = this.envOrDefault("SPVP_RESOURCE_PACK_PUBLIC_HOST", "");
      config.chatModeration = new ChatModerationSettings(
         "true".equalsIgnoreCase(this.envOrDefault("SPVP_CHAT_MODERATION_ENABLED", "false")),
         this.envOrDefault("CHAT_MODERATION_LANGUAGE", "eng"),
         this.envDoubleOrDefault("CHAT_MODERATION_CATEGORY3_THRESHOLD", 0.75D),
         !"false".equalsIgnoreCase(this.envOrDefault("CHAT_MODERATION_HONOR_REVIEW_RECOMMENDED", "true")),
         this.envIntOrDefault("CHAT_MODERATION_FLAGS_BEFORE_WARN", 2),
         this.envIntOrDefault("CHAT_MODERATION_WARNS_BEFORE_MUTE", 2),
         this.envIntOrDefault("CHAT_MODERATION_MUTE_DURATION_SECONDS", 3600),
         this.envIntOrDefault("CHAT_MODERATION_CONTENT_SAFETY_MIN_SEVERITY", 2)
      );
      config.normalizeDefaults();
      return config;
   }

   private boolean chatTranslationEnabled() {
      return Boolean.parseBoolean(this.envOrDefault("SPVP_CHAT_TRANSLATION_ENABLED", "false"));
   }

   private String chatTranslationEndpoint() {
      String dedicated = this.envOrDefault("SPVP_CHAT_TRANSLATION_ENDPOINT", "");
      if (!dedicated.isBlank()) {
         return dedicated;
      }
      return "https://api.cognitive.microsofttranslator.com";
   }

   private String chatTranslationApiKey() {
      String dedicated = this.envOrDefault("SPVP_CHAT_TRANSLATION_API_KEY", "");
      if (!dedicated.isBlank()) {
         return dedicated;
      }
      return this.envOrDefault("SPVP_CHAT_MODERATION_API_KEY", "");
   }

   private String chatTranslationRegion() {
      return this.envOrDefault("SPVP_CHAT_TRANSLATION_REGION", "");
   }

   private String envOrDefault(String key, String defaultValue) {
      String value = System.getenv(key);
      return value != null && !value.isBlank()
              ? network.skypvp.shared.chat.ChatTranslationDiagnostics.sanitizeEnv(value)
              : defaultValue;
   }

   private int envIntOrDefault(String key, int defaultValue) {
      String value = System.getenv(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Integer.parseInt(value.trim());
      } catch (NumberFormatException ex) {
         this.logger.warn("Invalid {} '{}'; using default {}.", key, value, defaultValue);
         return defaultValue;
      }
   }

   private long envLongOrDefault(String key, long defaultValue) {
      String value = System.getenv(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Long.parseLong(value.trim());
      } catch (NumberFormatException ex) {
         this.logger.warn("Invalid {} '{}'; using default {}.", key, value, defaultValue);
         return defaultValue;
      }
   }

   private double envDoubleOrDefault(String key, double defaultValue) {
      String value = System.getenv(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException ex) {
         this.logger.warn("Invalid {} '{}'; using default {}.", key, value, defaultValue);
         return defaultValue;
      }
   }
}
