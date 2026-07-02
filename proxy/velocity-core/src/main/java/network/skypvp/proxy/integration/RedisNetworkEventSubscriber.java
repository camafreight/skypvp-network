package network.skypvp.proxy.integration;

import network.skypvp.shared.ServerTextUtil;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextDecoration.State;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.chat.ProxyChatFormatService;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.repository.ServerRegistryRepository;
import network.skypvp.shared.BrandStyle;
import network.skypvp.shared.ChatFormatRefreshEvent;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.NetworkChatEvent;
import network.skypvp.shared.PartyChatEvent;
import network.skypvp.shared.PlayerSessionEvent;
import network.skypvp.shared.PrivateMessageEvent;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.ServerHeartbeatEvent;
import network.skypvp.shared.SocialSettingsRefreshEvent;
import network.skypvp.shared.SocialChatRules;
import network.skypvp.shared.VanishStateEvent;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class RedisNetworkEventSubscriber implements AutoCloseable {
   /** Heartbeat gap that proves a backend restarted (vs. an autoscaler drain that heart-beats continuously). */
   private static final long DRAIN_RESTART_GAP_MILLIS = 25000L;
   private final RedisConnectionSettings settings;
   private final String sessionChannel;
   private final String heartbeatChannel;
   private final String rankChannel;
   private final String chatChannel;
   private final String partyChatChannel;
   private final String socialSettingsChannel;
   private final String chatFormatChannel;
   private final String privateMessageChannel;
   private final NetworkStateRegistry stateRegistry;
   private final String reportsChannel;
   private final String vanishChannel;
   private final ProxyServer proxyServer;
   private final ServerRegistryRepository serverRegistryRepository;
   private final Logger logger;
   private final network.skypvp.proxy.service.ServerRoutingService serverRoutingService;
   private final network.skypvp.proxy.repository.PlayerSocialSettingsRepository socialSettingsRepository;
   private final ProxyChatFormatService chatFormatService;
   private final network.skypvp.proxy.service.ProxyPrivateMessageService privateMessageService;
   private final network.skypvp.proxy.service.ProxyChatTranslationDeliveryService translationDeliveryService;
   private final String drainingChannel;
   private final Map<String, InetSocketAddress> dynamicBackends = new ConcurrentHashMap<>();
   private volatile boolean running;
   private JedisPubSub pubSub;
   private Thread worker;
   private network.skypvp.proxy.service.ProxyHoldService proxyHoldService;

   public void setProxyHoldService(network.skypvp.proxy.service.ProxyHoldService proxyHoldService) {
      this.proxyHoldService = proxyHoldService;
   }

   public RedisNetworkEventSubscriber(
      RedisConnectionSettings settings,
      String sessionChannel,
      String heartbeatChannel,
      String rankChannel,
      NetworkStateRegistry stateRegistry,
      ServerRegistryRepository serverRegistryRepository,
      Logger logger
   ) {
      this(settings, sessionChannel, heartbeatChannel, rankChannel, stateRegistry, serverRegistryRepository, null, logger, null, null, null, null);
   }

   public RedisNetworkEventSubscriber(
      RedisConnectionSettings settings,
      String sessionChannel,
      String heartbeatChannel,
      String rankChannel,
      NetworkStateRegistry stateRegistry,
      ServerRegistryRepository serverRegistryRepository,
      ProxyServer proxyServer,
      Logger logger,
      network.skypvp.proxy.service.ServerRoutingService serverRoutingService
   ) {
      this(settings, sessionChannel, heartbeatChannel, rankChannel, stateRegistry, serverRegistryRepository, proxyServer, logger, serverRoutingService, null, null, null);
   }

   public RedisNetworkEventSubscriber(
      RedisConnectionSettings settings,
      String sessionChannel,
      String heartbeatChannel,
      String rankChannel,
      NetworkStateRegistry stateRegistry,
      ServerRegistryRepository serverRegistryRepository,
      ProxyServer proxyServer,
      Logger logger,
      network.skypvp.proxy.service.ServerRoutingService serverRoutingService,
      network.skypvp.proxy.repository.PlayerSocialSettingsRepository socialSettingsRepository
   ) {
      this(settings, sessionChannel, heartbeatChannel, rankChannel, stateRegistry, serverRegistryRepository, proxyServer, logger, serverRoutingService, socialSettingsRepository, null, null);
   }

   public RedisNetworkEventSubscriber(
      RedisConnectionSettings settings,
      String sessionChannel,
      String heartbeatChannel,
      String rankChannel,
      NetworkStateRegistry stateRegistry,
      ServerRegistryRepository serverRegistryRepository,
      ProxyServer proxyServer,
      Logger logger,
      network.skypvp.proxy.service.ServerRoutingService serverRoutingService,
      network.skypvp.proxy.repository.PlayerSocialSettingsRepository socialSettingsRepository,
      ProxyChatFormatService chatFormatService,
      network.skypvp.proxy.service.ProxyPrivateMessageService privateMessageService
   ) {
      this(settings, sessionChannel, heartbeatChannel, rankChannel, stateRegistry, serverRegistryRepository, proxyServer, logger, serverRoutingService, socialSettingsRepository, chatFormatService, privateMessageService, null);
   }

   public RedisNetworkEventSubscriber(
      RedisConnectionSettings settings,
      String sessionChannel,
      String heartbeatChannel,
      String rankChannel,
      NetworkStateRegistry stateRegistry,
      ServerRegistryRepository serverRegistryRepository,
      ProxyServer proxyServer,
      Logger logger,
      network.skypvp.proxy.service.ServerRoutingService serverRoutingService,
      network.skypvp.proxy.repository.PlayerSocialSettingsRepository socialSettingsRepository,
      ProxyChatFormatService chatFormatService,
      network.skypvp.proxy.service.ProxyPrivateMessageService privateMessageService,
      network.skypvp.proxy.service.ProxyChatTranslationDeliveryService translationDeliveryService
   ) {
      this.settings = Objects.requireNonNull(settings, "settings");
      this.sessionChannel = Objects.requireNonNull(sessionChannel, "sessionChannel");
      this.heartbeatChannel = Objects.requireNonNull(heartbeatChannel, "heartbeatChannel");
      this.rankChannel = Objects.requireNonNull(rankChannel, "rankChannel");
      this.chatChannel = "SkyPvP:network:chat";
      this.partyChatChannel = NetworkChannels.PARTY_CHAT;
      this.socialSettingsChannel = NetworkChannels.SOCIAL_SETTINGS_REFRESH;
      this.chatFormatChannel = NetworkChannels.CHAT_FORMAT_REFRESH;
      this.privateMessageChannel = NetworkChannels.PRIVATE_MESSAGE;
      this.stateRegistry = Objects.requireNonNull(stateRegistry, "stateRegistry");
      this.serverRegistryRepository = serverRegistryRepository;
      this.logger = Objects.requireNonNull(logger, "logger");
      this.reportsChannel = "SkyPvP:network:reports";
      this.vanishChannel = "SkyPvP:network:vanish";
      this.drainingChannel = network.skypvp.shared.NetworkChannels.SERVER_DRAINING;
      this.proxyServer = proxyServer;
      this.serverRoutingService = serverRoutingService;
      this.socialSettingsRepository = socialSettingsRepository;
      this.chatFormatService = chatFormatService;
      this.privateMessageService = privateMessageService;
      this.translationDeliveryService = translationDeliveryService;
   }

   public void start() {
      if (!this.running) {
         this.running = true;
         this.worker = new Thread(this::runSubscriptionLoop, "SkyPvP-redis-subscriber");
         this.worker.setDaemon(true);
         this.worker.start();
      }
   }

   private void runSubscriptionLoop() {
      while (this.running) {
         try (Jedis jedis = new Jedis(this.settings.host(), this.settings.port())) {
            if (!this.settings.sanitizedPassword().isBlank()) {
               jedis.auth(this.settings.sanitizedPassword());
            }

            if (this.settings.database() != 0) {
               jedis.select(this.settings.database());
            }

            this.pubSub = new JedisPubSub() {
               {
                  Objects.requireNonNull(RedisNetworkEventSubscriber.this);
               }

               public void onMessage(String channel, String message) {
                  RedisNetworkEventSubscriber.this.handleMessage(channel, message);
               }
            };
            this.logger.info("Subscribing to Redis channels: '{}', '{}' and '{}'", this.sessionChannel, this.heartbeatChannel, this.rankChannel);
            this.logger.info("Subscribing to Redis channels: session, heartbeat, ranks, reports, chat, partychat, socialsettings, chatformats, privatemessage, vanish, draining");
            jedis.subscribe(
               this.pubSub, this.sessionChannel, this.heartbeatChannel, this.rankChannel, this.reportsChannel, this.chatChannel, this.partyChatChannel, this.socialSettingsChannel, this.chatFormatChannel, this.privateMessageChannel, this.vanishChannel, this.drainingChannel
            );
         } catch (Exception var7) {
            if (this.running) {
               this.logger.warn("Redis subscription failed (will retry in 5s): {}", var7.getMessage());

               try {
                  Thread.sleep(5000L);
                  continue;
               } catch (InterruptedException var6) {
                  Thread.currentThread().interrupt();
               }
            }
            break;
         }
      }
   }

   private void handleMessage(String channel, String payload) {
      try {
         if (this.sessionChannel.equals(channel)) {
            PlayerSessionEvent sessionEvent = JsonCodec.gson().fromJson(payload, PlayerSessionEvent.class);
            this.stateRegistry.applyPlayerSession(sessionEvent);
            return;
         }

         if (this.heartbeatChannel.equals(channel)) {
            ServerHeartbeatEvent heartbeatEvent = JsonCodec.gson().fromJson(payload, ServerHeartbeatEvent.class);
            ServerHeartbeatEvent previousHeartbeat = heartbeatEvent != null && heartbeatEvent.serverId() != null
               ? this.stateRegistry.heartbeatFor(heartbeatEvent.serverId()).orElse(null)
               : null;
            this.stateRegistry.applyHeartbeat(heartbeatEvent);
            this.clearStaleDrainOnRestart(previousHeartbeat, heartbeatEvent);
            this.registerDynamicBackend(heartbeatEvent);
            if (this.serverRegistryRepository != null && heartbeatEvent != null) {
               this.serverRegistryRepository.upsertHeartbeat(heartbeatEvent);
            }

            return;
         }

         if (this.rankChannel.equals(channel)) {
            // Rank system has been migrated to LuckPerms, ignoring custom rank updates.
            return;
         }

         if (this.reportsChannel.equals(channel) && this.proxyServer != null) {
            this.handleReportEvent(payload);
            return;
         }

         if (this.chatChannel.equals(channel) && this.proxyServer != null) {
            this.handleGlobalChat(payload);
            return;
         }

         if (this.partyChatChannel.equals(channel) && this.proxyServer != null) {
            this.handlePartyChat(payload);
            return;
         }

         if (this.socialSettingsChannel.equals(channel)) {
            this.handleSocialSettingsRefresh(payload);
            return;
         }

         if (this.chatFormatChannel.equals(channel)) {
            this.handleChatFormatRefresh(payload);
            return;
         }

         if (this.privateMessageChannel.equals(channel) && this.proxyServer != null) {
            this.handlePrivateMessage(payload);
            return;
         }

         if (this.vanishChannel.equals(channel)) {
            this.handleVanishEvent(payload);
            return;
         }

         if (this.drainingChannel.equals(channel) && this.proxyServer != null) {
            this.handleServerDraining(payload);
         }
      } catch (Exception var4) {
         this.logger.warn("Failed to parse Redis event from channel '{}': {}", channel, var4.getMessage());
      }
   }

   private void handleServerDraining(String payload) {
      try {
         network.skypvp.shared.ServerDrainEvent evt = JsonCodec.gson().fromJson(payload, network.skypvp.shared.ServerDrainEvent.class);
         if (evt == null || evt.serverId() == null) {
            return;
         }

         if (this.serverRoutingService != null) {
            this.serverRoutingService.markServerAsDraining(evt.serverId());
         }

         this.proxyServer.getServer(evt.serverId()).ifPresent(server -> {
            this.logger.info("[Lifecycle] Draining server {} via proxy...", evt.serverId());
            for (com.velocitypowered.api.proxy.Player p : server.getPlayersConnected()) {
               if (this.serverRoutingService != null) {
                  java.util.Optional<com.velocitypowered.api.proxy.server.RegisteredServer> target = this.serverRoutingService.selectFallback(evt.serverId());
                  if (target.isPresent()) {
                     p.sendMessage(ServerTextUtil.component("&aServer is restarting. Moving you to &e" + target.get().getServerInfo().getName() + "&a..."));
                     p.createConnectionRequest(target.get()).fireAndForget();
                  } else if (this.proxyHoldService != null && this.proxyHoldService.available()) {
                     String queueKey = this.serverRoutingService != null ? this.serverRoutingService.queueKeyForServer(evt.serverId()) : "lobby";
                     this.proxyHoldService.holdForOutage(p, queueKey, evt.serverId());
                  } else {
                     p.disconnect(ServerTextUtil.component("&cServer is restarting and no fallback is available."));
                  }
               }
            }
         });
      } catch (Exception e) {
         this.logger.warn("Failed to handle draining event: {}", e.getMessage());
      }
   }

   /**
    * Auto-clears a stale draining flag when a backend comes back after a restart.
    *
    * <p>Background: a backend's lifecycle auto-restart (e.g. {@code restart-after=360m}) publishes a
    * {@link network.skypvp.shared.ServerDrainEvent}, which marks the server draining in the proxy's in-memory
    * {@code drainingServers} set. The proxy process does not restart, so without this hook the flag is never
    * cleared — once the backend restarts and resumes heart-beating it stays {@code DRAINING}/unjoinable forever
    * and the network reports "no backends available".
    *
    * <p>We only clear the flag when the heartbeat proves an actual restart: the server reports {@code joinable}
    * AND there was a heartbeat gap long enough that the process must have gone down and come back. That gap is
    * what distinguishes a lifecycle restart from an in-progress autoscaler scale-down drain (which keeps
    * heart-beating continuously and must remain flagged until the pod is removed).
    */
   private void clearStaleDrainOnRestart(ServerHeartbeatEvent previous, ServerHeartbeatEvent current) {
      if (this.serverRoutingService == null || current == null || current.serverId() == null || current.serverId().isBlank()) {
         return;
      }
      if (!current.joinable() || !this.serverRoutingService.isServerDraining(current.serverId())) {
         return;
      }

      // Lifecycle restarts publish a drain event then resume heart-beating every ~10s. The old
      // gap-based check never fired because consecutive heartbeats are too close. A not-joinable →
      // joinable transition means the backend finished booting; autoscaler drains keep joinable=true
      // throughout so they are not cleared here.
      if (previous == null || !previous.joinable()) {
         this.serverRoutingService.unmarkServerAsDraining(current.serverId());
         this.logger.info("Cleared stale drain flag for '{}' after joinable heartbeat resumed.", current.serverId());
         return;
      }

      long gapMillis = Math.max(0L, current.occurredAtEpochMillis() - previous.occurredAtEpochMillis());
      if (gapMillis >= DRAIN_RESTART_GAP_MILLIS) {
         this.serverRoutingService.unmarkServerAsDraining(current.serverId());
         this.logger.info("Cleared stale drain flag for '{}' after restart (joinable heartbeat, gap={}ms).",
            current.serverId(), gapMillis);
      }
   }

   private void registerDynamicBackend(ServerHeartbeatEvent heartbeatEvent) {
      if (this.proxyServer != null && heartbeatEvent != null && heartbeatEvent.serverId() != null && !heartbeatEvent.serverId().isBlank()) {
         String host = heartbeatEvent.advertisedHost();
         int port = heartbeatEvent.advertisedPort();
         if (host != null && !host.isBlank() && port > 0) {
            InetSocketAddress address = InetSocketAddress.createUnresolved(host.trim(), port);
            InetSocketAddress existing = this.dynamicBackends.get(heartbeatEvent.serverId());
            if (!address.equals(existing)) {
               if (!this.proxyServer.getServer(heartbeatEvent.serverId()).isPresent() || existing != null) {
                  if (existing != null) {
                     this.proxyServer.getServer(heartbeatEvent.serverId()).ifPresent(server -> this.proxyServer.unregisterServer(server.getServerInfo()));
                  }

                  this.proxyServer.registerServer(new ServerInfo(heartbeatEvent.serverId(), address));
                  this.dynamicBackends.put(heartbeatEvent.serverId(), address);
                  this.logger.info("Registered dynamic backend '{}' at {}:{} from heartbeat.", heartbeatEvent.serverId(), host, port);
               }
            }
         }
      }
   }

   public void pruneStaleDynamicBackends(long staleCutoffEpochMillis) {
      if (this.proxyServer != null) {
         this.dynamicBackends.forEach((serverId, address) -> {
            long lastHeartbeat = this.stateRegistry.heartbeatFor(serverId).map(ServerHeartbeatEvent::occurredAtEpochMillis).orElse(0L);
            if (lastHeartbeat < staleCutoffEpochMillis) {
               this.proxyServer.getServer(serverId).ifPresent(server -> this.proxyServer.unregisterServer(server.getServerInfo()));
               this.dynamicBackends.remove(serverId, address);
               this.logger.info("Unregistered stale dynamic backend '{}' after heartbeat timeout.", serverId);
            }
         });
         this.stateRegistry.pruneStaleHeartbeats(staleCutoffEpochMillis);
      }
   }

   private void handleChatFormatRefresh(String payload) {
      if (this.chatFormatService == null) {
         return;
      }
      try {
         ChatFormatRefreshEvent event = JsonCodec.gson().fromJson(payload, ChatFormatRefreshEvent.class);
         if (event == null) {
            return;
         }
         this.chatFormatService.reload();
      } catch (Exception ex) {
         this.logger.warn("Failed to handle chat format refresh: {}", ex.getMessage());
      }
   }

   private void handlePrivateMessage(String payload) {
      if (this.privateMessageService == null) {
         return;
      }
      try {
         PrivateMessageEvent event = JsonCodec.gson().fromJson(payload, PrivateMessageEvent.class);
         if (event == null) {
            return;
         }
         this.privateMessageService.deliverFromEvent(event);
      } catch (Exception ex) {
         this.logger.warn("Failed to handle private message event: {}", ex.getMessage());
      }
   }

   private void handleSocialSettingsRefresh(String payload) {
      if (this.socialSettingsRepository == null) {
         return;
      }
      try {
         SocialSettingsRefreshEvent event = JsonCodec.gson().fromJson(payload, SocialSettingsRefreshEvent.class);
         if (event == null || event.playerUuid() == null || event.playerUuid().isBlank()) {
            return;
         }
         this.socialSettingsRepository.refresh(UUID.fromString(event.playerUuid()));
      } catch (Exception ex) {
         this.logger.warn("Failed to handle social settings refresh: {}", ex.getMessage());
      }
   }

   private void handlePartyChat(String payload) {
      try {
         PartyChatEvent partyEvent = JsonCodec.gson().fromJson(payload, PartyChatEvent.class);
         if (partyEvent == null || partyEvent.memberUuids() == null || partyEvent.memberUuids().isEmpty()) {
            return;
         }

         java.util.Set<UUID> members = new java.util.HashSet<>();
         for (String rawUuid : partyEvent.memberUuids()) {
            try {
               members.add(UUID.fromString(rawUuid));
            } catch (IllegalArgumentException ignored) {
            }
         }
         if (members.isEmpty()) {
            return;
         }

         String sender = partyEvent.senderName() == null ? "Player" : partyEvent.senderName();
         String message = partyEvent.plainMessage() == null ? "" : partyEvent.plainMessage();
         String senderLocale = partyEvent.senderLocale();
         UUID senderId = partyEvent.senderUuidAsUuid();
         String origin = partyEvent.originServerId() == null ? "" : partyEvent.originServerId();
         com.velocitypowered.api.proxy.Player senderPlayer = senderId == null
            ? null
            : this.proxyServer.getPlayer(senderId).orElse(null);
         this.proxyServer.getAllPlayers().stream()
            .filter(player -> members.contains(player.getUniqueId()))
            .filter(player -> !player.getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("").equals(origin))
            .forEach(player -> {
               String body = this.translateForViewer(senderId, senderLocale, message, player.getUniqueId());
               Component line = this.chatFormatService != null
                  ? this.chatFormatService.renderParty(sender, body, senderPlayer, null)
                  : this.legacyPartyLine(sender, body);
               player.sendMessage(line);
            });
      } catch (Exception ex) {
         this.logger.warn("Failed to handle party chat event: {}", ex.getMessage());
      }
   }

   private void handleGlobalChat(String payload) {
      try {
         NetworkChatEvent chatEvent = JsonCodec.gson().fromJson(payload, NetworkChatEvent.class);
         if (chatEvent == null) {
            return;
         }

         String rankKey = chatEvent.rankKey() == null ? "default" : chatEvent.rankKey();
         boolean staffMessage = SocialChatRules.isStaffRank(rankKey);
         String senderName = chatEvent.senderName() == null ? "Player" : chatEvent.senderName();
         String message = chatEvent.plainMessage() == null ? "" : chatEvent.plainMessage();
         String senderLocale = chatEvent.senderLocale();
         UUID senderId = null;
         if (chatEvent.senderUuid() != null) {
            try {
               senderId = UUID.fromString(chatEvent.senderUuid());
            } catch (IllegalArgumentException ignored) {
            }
         }
         com.velocitypowered.api.proxy.Player sender = null;
         if (senderId != null && this.proxyServer != null) {
            sender = this.proxyServer.getPlayer(senderId).orElse(null);
         }
         final com.velocitypowered.api.proxy.Player resolvedSender = sender;
         String origin = chatEvent.originServerId();
         UUID resolvedSenderId = senderId;
         this.proxyServer.getAllPlayers().stream().filter(p -> {
            String serverName = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
            return !serverName.equals(origin);
         }).filter(p -> staffMessage || this.shouldDeliverChat(p)).forEach(p -> {
            String body = this.translateForViewer(resolvedSenderId, senderLocale, message, p.getUniqueId());
            Component finalMsg = this.chatFormatService != null
               ? this.chatFormatService.renderGlobal(resolvedSender, senderName, rankKey, body)
               : this.legacyGlobalLine(chatEvent, rankKey, staffMessage, senderName, body);
            p.sendMessage(finalMsg);
         });
      } catch (Exception var16) {
         this.logger.warn("Failed to handle global chat event: {}", var16.getMessage());
      }
   }

   private Component legacyPartyLine(String sender, String message) {
      return ServerTextUtil.miniMessageComponent(
         "<#88CCFF><bold>[Party]</bold> <#FFFFFF>" + sanitize(sender) + "<#888888>: <#AAAAAA>" + sanitize(message)
      );
   }

   private Component legacyGlobalLine(NetworkChatEvent chatEvent, String rankKey, boolean staffMessage, String senderName, String message) {
      String rankLabel = chatEvent.rankDisplayName() == null ? "" : chatEvent.rankDisplayName();
      String rankColor = BrandStyle.hexForRankKey(rankKey);
      String msgColor = BrandStyle.chatMessageColor(rankKey);
      Component prefix = (!rankLabel.isBlank() && !"Player".equalsIgnoreCase(rankLabel) && !"default".equalsIgnoreCase(rankKey)
         ? ServerTextUtil.miniMessageComponent("<" + rankColor + "><bold>[" + rankLabel + "]</bold><reset> ")
         : Component.text(""));
      Component name = ServerTextUtil.miniMessageComponent("<" + rankColor + ">" + senderName + "<reset>");
      TextColor mc = TextColor.fromHexString(msgColor);
      if (mc == null) {
         mc = NamedTextColor.GRAY;
      }
      Component body = ((TextComponent) ServerTextUtil.component(message).color(mc)).decorationIfAbsent(TextDecoration.ITALIC, State.FALSE);
      Component separator = Component.text(": ", NamedTextColor.DARK_GRAY);
      if (prefix.equals(Component.text(""))) {
         return name.append(separator).append(body);
      }
      return prefix.append(name).append(separator).append(body);
   }

   private boolean shouldDeliverChat(com.velocitypowered.api.proxy.Player player) {
      if (player.hasPermission(network.skypvp.shared.SocialChatRules.PERMISSION_CHAT_TOGGLE_BYPASS)) {
         return true;
      }
      if (this.socialSettingsRepository == null) {
         return true;
      }
      return this.socialSettingsRepository.isChatEnabled(player.getUniqueId());
   }

   private String translateForViewer(UUID senderId, String senderLocale, String message, UUID viewerId) {
      if (this.translationDeliveryService == null) {
         return message;
      }
      return this.translationDeliveryService.messageForViewer(senderId, senderLocale, message, viewerId);
   }

   private void handleReportEvent(String payload) {
      try {
         JsonObject obj = JsonCodec.gson().fromJson(payload, JsonObject.class);
         String reporterName = sanitize(obj.get("reporterName").getAsString());
         String targetName = sanitize(obj.get("targetName").getAsString());
         String reason = sanitize(obj.get("reason").getAsString());
         String serverId = sanitize(obj.get("serverId").getAsString());
         MiniMessage mm = MiniMessage.miniMessage();
         Component alert = ServerTextUtil.miniMessageComponent(
            "\n<#FF5555><bold>[REPORT]</bold><reset> <#888888>from <reset><#FFFFFF>"
               + reporterName
               + "<reset><#888888> on <reset><#FFD700>"
               + serverId
               + "<reset>\n  <#888888>Target: <reset><#FFFFFF>"
               + targetName
               + "<reset>\n  <#888888>Reason: <reset><white>"
               + reason
               + "<reset>\n  <#888888>Actions: <reset><click:run_command:/lfhistory "
               + targetName
               + "><hover:show_text:'<gray>View punishment history'><#FFD700>[History]<reset></hover></click> <click:suggest_command:/lfmute "
               + targetName
               + " ><hover:show_text:'<gray>Mute this player'><#FF9900>[Mute]<reset></hover></click> <click:suggest_command:/lfkick "
               + targetName
               + " ><hover:show_text:'<gray>Kick this player'><#FFFFFF>[Kick]<reset></hover></click> <click:suggest_command:/lfban "
               + targetName
               + " ><hover:show_text:'<gray>Ban this player'><#FF5555>[Ban]<reset></hover></click>"
         );
         this.proxyServer.getAllPlayers().stream().filter(p -> p.hasPermission("skypvp.staff")).forEach(p -> p.sendMessage(alert));
         this.logger.info("[REPORT] {} → {}: {}", reporterName, targetName, reason);
      } catch (Exception var9) {
         this.logger.warn("Failed to handle report event: {}", var9.getMessage());
      }
   }

   private static String sanitize(String input) {
      return input == null ? "" : input.replace("<", "\\<").replace(">", "\\>");
   }

   private void handleVanishEvent(String payload) {
      try {
         VanishStateEvent evt = JsonCodec.gson().fromJson(payload, VanishStateEvent.class);
         if (evt == null || evt.playerUuid() == null) {
            return;
         }

         UUID uid = UUID.fromString(evt.playerUuid());
         this.stateRegistry.setVanished(uid, evt.vanished());
         this.logger.debug("[VANISH] {} is now {}", evt.username(), evt.vanished() ? "vanished" : "visible");
      } catch (Exception var4) {
         this.logger.warn("Failed to handle vanish event: {}", var4.getMessage());
      }
   }

   @Override
   public void close() {
      this.running = false;
      if (this.pubSub != null) {
         this.pubSub.unsubscribe();
      }

      if (this.worker != null) {
         this.worker.interrupt();

         try {
            this.worker.join(2000L);
         } catch (InterruptedException var2) {
            Thread.currentThread().interrupt();
         }
      }
   }
}
