package network.skypvp.proxy.service;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualBlock;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.material.Item;
import net.elytrium.limboapi.api.material.VirtualItem;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.MaintenanceRegistry;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.QueueStatusSnapshot;
import network.skypvp.shared.ServerTextUtil;
import org.slf4j.Logger;

public final class ProxyLimboService implements ProxyHoldService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final int MAINTENANCE_STAFF_THRESHOLD = 500;
   private static final long REROUTE_RETRY_MILLIS = 3000L;
   private static final long LOGIN_ALTERNATE_REMINDER_MILLIS = 30000L;
   private static final long HELD_REROUTE_RELEASE_DELAY_MILLIS = 350L;
   private static final int HOTBAR_FIRST_SLOT = 36;
   private static final int HOTBAR_LAST_SLOT = 44;
   private final Object taskOwner;
   private final ProxyServer proxyServer;
   private final Logger logger;
   private final ProxyBootstrapConfig config;
   private final MaintenanceRegistry maintenanceRegistry;
   private final ServerRoutingService routingService;
   private final QueueService queueService;
   private final Object limboPluginInstance;
   private final LimboFactory limboFactory;
   private final Method setKickCallbackMethod;
   private final Method unsetLimboJoinedMethod;
   private final Method removeKickCallbackMethod;
   private final Method removeLoginQueueMethod;
   private final Method removeNextServerMethod;
   private final VirtualItem airItem;
   private final Map<UUID, ProxyLimboService.LimboTicket> tickets = new ConcurrentHashMap<>();
   private final Map<UUID, LimboPlayer> activeLimboPlayers = new ConcurrentHashMap<>();
   private final Map<UUID, Long> rerouteAttemptAtMillis = new ConcurrentHashMap<>();
   private final Map<UUID, Set<String>> announcedLoginAlternates = new ConcurrentHashMap<>();
   private final Map<UUID, Long> loginAlternateReminderAtMillis = new ConcurrentHashMap<>();
   private final Map<UUID, ScheduledFuture<?>> hudRefreshTasks = new ConcurrentHashMap<>();
   private final Set<UUID> uiSuppressedPlayers = ConcurrentHashMap.newKeySet();
   private final Limbo loginLimbo;
   private final Limbo outageLimbo;
   private final ScheduledTask statusTask;

   public ProxyLimboService(
      Object taskOwner,
      ProxyServer proxyServer,
      Logger logger,
      ProxyBootstrapConfig config,
      MaintenanceRegistry maintenanceRegistry,
      ServerRoutingService routingService,
      QueueService queueService
   ) {
      this.taskOwner = taskOwner;
      this.proxyServer = proxyServer;
      this.logger = logger;
      this.config = config;
      this.maintenanceRegistry = maintenanceRegistry;
      this.routingService = routingService;
      this.queueService = queueService;
      this.limboPluginInstance = this.resolvePluginInstance(proxyServer);
      this.limboFactory = this.resolveFactory(this.limboPluginInstance);
      this.setKickCallbackMethod = this.resolveSetKickCallbackMethod(this.limboPluginInstance);
      this.unsetLimboJoinedMethod = this.resolvePlayerCleanupMethod(this.limboPluginInstance, "unsetLimboJoined");
      this.removeKickCallbackMethod = this.resolvePlayerCleanupMethod(this.limboPluginInstance, "removeKickCallback");
      this.removeLoginQueueMethod = this.resolvePlayerCleanupMethod(this.limboPluginInstance, "removeLoginQueue");
      this.removeNextServerMethod = this.resolvePlayerCleanupMethod(this.limboPluginInstance, "removeNextServer");
      this.airItem = this.limboFactory.getItem(Item.AIR);
      this.loginLimbo = this.buildLimbo("SkyPvPLoginLimbo", false, true);
      this.outageLimbo = this.buildLimbo("SkyPvPOutageLimbo", true, true);
      this.statusTask = proxyServer.getScheduler()
         .buildTask(taskOwner, this::tick)
         .repeat((long)config.limbo.statusRefreshMillis, TimeUnit.MILLISECONDS)
         .schedule();
   }

   @Override
   public boolean available() {
      return this.loginLimbo != null && this.outageLimbo != null;
   }

   @Override
   public boolean shouldHoldLogin(Player player) {
      if (!this.available()) {
         return false;
      } else {
         return this.maintenanceRegistry.isEnabled() && this.config.limbo.holdPlayersDuringMaintenance && !this.isStaffExempt(player)
            ? true
            : this.config.limbo.holdPlayersWhenNoInitialServer && this.routingService.selectBestLoginServer().isEmpty();
      }
   }

   @Override
   public void holdLogin(Player player) {
      this.enterLimbo(player, this.routingService.loginQueueKey(), "proxy-login", ProxyLimboService.HoldReason.LOGIN);
   }

   @Override
   public boolean holdForOutage(Player player, String queueKey, String originServerId) {
      this.clearReleasedPlayerLimboState(player);
      return this.enterLimbo(player, queueKey, originServerId, ProxyLimboService.HoldReason.OUTAGE);
   }

   @Override
   public boolean rerouteHeld(Player player, String queueKey, RegisteredServer target) {
      if (player != null && target != null) {
         ProxyLimboService.LimboTicket ticket = this.tickets.get(player.getUniqueId());
         if (ticket == null) {
            return false;
         } else {
            String desiredQueueKey = this.normalizeQueueKey(queueKey);
            if (desiredQueueKey.isBlank()) {
               desiredQueueKey = this.routingService.queueKeyForServer(target.getServerInfo().getName());
            }

            String normalizedQueueKey = desiredQueueKey;
            boolean alternateFromLoginHold = ticket.holdReason() == ProxyLimboService.HoldReason.LOGIN
               && !normalizedQueueKey.equals(this.routingService.loginQueueKey());
            if (!alternateFromLoginHold) {
               return false;
            } else {
               String targetServerId = target.getServerInfo().getName();
               this.proxyServer
                  .getScheduler()
                  .buildTask(
                     this.taskOwner,
                     () -> {
                        LimboPlayer activeLimboPlayer = this.activeLimboPlayers.get(player.getUniqueId());
                        Player livePlayer = activeLimboPlayer != null ? activeLimboPlayer.getProxyPlayer() : player;
                        if (livePlayer != null) {
                           String existingQueueKey = this.queueService.queueKeyFor(player.getUniqueId()).orElse(null);
                           if (existingQueueKey != null && !existingQueueKey.equalsIgnoreCase(normalizedQueueKey)) {
                              this.queueService.leaveQueue(player.getUniqueId());
                           }

                           QueueService.QueueJoinResult joinResult = this.queueService
                              .joinQueue(player.getUniqueId(), livePlayer.getUsername(), normalizedQueueKey);
                           if (joinResult.valid()) {
                              ProxyLimboService.LimboTicket reroutedTicket = new ProxyLimboService.LimboTicket(
                                 player.getUniqueId(),
                                 livePlayer.getUsername(),
                                 normalizedQueueKey,
                                 "proxy-play",
                                 ProxyLimboService.HoldReason.OUTAGE,
                                 System.currentTimeMillis()
                              );
                              this.tickets.put(player.getUniqueId(), reroutedTicket);
                              this.rerouteAttemptAtMillis.remove(player.getUniqueId());
                              this.announcedLoginAlternates.remove(player.getUniqueId());
                              this.loginAlternateReminderAtMillis.remove(player.getUniqueId());
                              this.renderHeldHud(livePlayer, reroutedTicket, System.currentTimeMillis());
                              this.loginLimbo.respawnPlayer(livePlayer);
                              this.proxyServer
                                 .getScheduler()
                                 .buildTask(
                                    this.taskOwner,
                                    () -> {
                                       ProxyLimboService.LimboTicket currentTicket = this.tickets.get(player.getUniqueId());
                                       if (currentTicket != null
                                          && currentTicket.holdReason() != ProxyLimboService.HoldReason.LOGIN
                                          && currentTicket.queueKey().equalsIgnoreCase(normalizedQueueKey)) {
                                          boolean targetHealthy = this.routingService
                                             .describeServer(targetServerId)
                                             .map(ServerRoutingService.ServerRouteStatus::isHealthyJoinTarget)
                                             .orElse(false);
                                          if (targetHealthy) {
                                             this.releaseHeld(player.getUniqueId(), target);
                                          }
                                       }
                                    }
                                 )
                                 .delay(350L, TimeUnit.MILLISECONDS)
                                 .schedule();
                           }
                        }
                     }
                  )
                  .schedule();
               this.logger.info("Rerouting '{}' from login limbo to queue '{}' via backend '{}'.", player.getUsername(), normalizedQueueKey, targetServerId);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   @Override
   public boolean releaseHeld(Player player, RegisteredServer target) {
      return this.releaseHeld(player.getUniqueId(), target);
   }

   @Override
   public boolean releaseHeld(UUID playerId, RegisteredServer target) {
      ProxyLimboService.LimboTicket ticket = this.tickets.get(playerId);
      if (ticket == null) {
         return false;
      } else {
         LimboPlayer activeLimboPlayer = this.activeLimboPlayers.get(playerId);
         if (activeLimboPlayer != null) {
            this.uiSuppressedPlayers.add(playerId);
            this.stopHudRefresh(playerId);
            activeLimboPlayer.disconnect(target);
            this.logger.info("Releasing '{}' from proxy limbo to backend '{}'.", ticket.username(), target.getServerInfo().getName());
            return true;
         } else {
            Player player = (Player)this.proxyServer.getPlayer(playerId).orElse(null);
            if (player != null && this.isLimboSessionActive(player)) {
               try {
                  this.uiSuppressedPlayers.add(playerId);
                  this.stopHudRefresh(playerId);
                  Object limboPlayer = this.createInternalLimboPlayer(player, this.limboFor(ticket.holdReason()));
                  Method disconnectToServer = limboPlayer.getClass().getMethod("disconnect", RegisteredServer.class);
                  disconnectToServer.invoke(limboPlayer, target);
                  this.logger.info("Releasing '{}' from proxy limbo to backend '{}'.", player.getUsername(), target.getServerInfo().getName());
                  return true;
               } catch (ReflectiveOperationException var8) {
                  this.uiSuppressedPlayers.remove(playerId);
                  this.logger
                     .warn(
                        "Failed to release '{}' from LimboAPI session to '{}': {}", player.getUsername(), target.getServerInfo().getName(), var8.getMessage()
                     );
                  return false;
               }
            } else {
               return false;
            }
         }
      }
   }

   public void registerCommand(Collection<String> aliases, Command command) {
      if (this.available() && aliases != null && !aliases.isEmpty() && command != null) {
         this.loginLimbo.registerCommand(new LimboCommandMeta(aliases), command);
         this.outageLimbo.registerCommand(new LimboCommandMeta(aliases), command);
      }
   }

   @Override
   public void onBackendConnected(Player player) {
      if (!this.isLimboSessionActive(player)) {
         UUID playerId = player.getUniqueId();
         boolean hadLimboState = this.uiSuppressedPlayers.remove(playerId)
            | this.activeLimboPlayers.remove(playerId) != null
            | this.tickets.remove(playerId) != null
            | this.rerouteAttemptAtMillis.remove(playerId) != null
            | this.announcedLoginAlternates.remove(playerId) != null
            | this.loginAlternateReminderAtMillis.remove(playerId) != null;
         this.stopHudRefresh(playerId);
         if (hadLimboState) {
            this.queueService.leaveQueue(playerId);
            this.clearReleasedPlayerLimboState(player);
            player.clearTitle();
            player.sendActionBar(Component.text(""));
            player.sendPlayerListHeaderAndFooter(Component.text(""), Component.text(""));
         }

         this.installKickCallback(player);
      }
   }

   @Override
   public void onProxyDisconnect(UUID playerId) {
      this.uiSuppressedPlayers.remove(playerId);
      this.stopHudRefresh(playerId);
      this.activeLimboPlayers.remove(playerId);
      this.tickets.remove(playerId);
      this.rerouteAttemptAtMillis.remove(playerId);
      this.announcedLoginAlternates.remove(playerId);
      this.loginAlternateReminderAtMillis.remove(playerId);
   }

   @Override
   public void close() {
      this.statusTask.cancel();
      this.hudRefreshTasks.values().forEach(task -> task.cancel(false));
      this.uiSuppressedPlayers.clear();
      this.activeLimboPlayers.clear();
      this.tickets.clear();
      this.rerouteAttemptAtMillis.clear();
      this.announcedLoginAlternates.clear();
      this.loginAlternateReminderAtMillis.clear();
      this.hudRefreshTasks.clear();
      this.loginLimbo.dispose();
      this.outageLimbo.dispose();
   }

   private void tick() {
      this.evacuatePlayersIfNeeded();
      this.refreshHeldPlayers();
   }

   private void evacuatePlayersIfNeeded() {
      long now = System.currentTimeMillis();

      for (Player player : this.proxyServer.getAllPlayers()) {
         UUID playerId = player.getUniqueId();
         if (!this.tickets.containsKey(playerId)) {
            Optional<String> currentServer = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
            if (!currentServer.isEmpty()) {
               String currentServerId = currentServer.get();
               if (this.maintenanceRegistry.isEnabled()
                  && this.config.limbo.holdPlayersDuringMaintenance
                  && this.config.limbo.evacuatePlayersDuringMaintenance
                  && !this.isStaffExempt(player)) {
                  this.enterLimbo(player, this.routingService.queueKeyForServer(currentServerId), currentServerId, ProxyLimboService.HoldReason.MAINTENANCE);
               } else if (this.config.limbo.evacuatePlayersFromUnhealthyServers) {
                  Optional<ServerRoutingService.ServerRouteStatus> status = this.routingService.describeServer(currentServerId);
                  if (!status.isEmpty() && !status.get().isHealthyJoinTarget()) {
                     String queueKey = this.routingService.queueKeyForServer(currentServerId);
                     Optional<RegisteredServer> rerouteTarget = this.routingService.selectBestTargetForQueue(queueKey, Set.of(currentServerId));
                     if (rerouteTarget.isPresent()) {
                        long lastAttemptAt = this.rerouteAttemptAtMillis.getOrDefault(playerId, 0L);
                        if (now - lastAttemptAt >= 3000L) {
                           this.rerouteAttemptAtMillis.put(playerId, now);
                           RegisteredServer target = rerouteTarget.get();
                           player.createConnectionRequest(target).connectWithIndication();
                           this.logger
                              .info(
                                 "Attempting proactive same-queue reroute for '{}' from '{}' to '{}'.",
                                 player.getUsername(),
                                 currentServerId,
                                 target.getServerInfo().getName()
                              );
                        }
                     } else {
                        this.enterLimbo(player, queueKey, currentServerId, ProxyLimboService.HoldReason.OUTAGE);
                     }
                  }
               }
            }
         }
      }
   }

   private void refreshHeldPlayers() {
      long now = System.currentTimeMillis();

      for (ProxyLimboService.LimboTicket ticket : this.tickets.values()) {
         LimboPlayer limboPlayer = this.activeLimboPlayers.get(ticket.playerId());
         Player player = limboPlayer != null ? limboPlayer.getProxyPlayer() : (Player)this.proxyServer.getPlayer(ticket.playerId()).orElse(null);
         if (player != null) {
            this.refreshHeldPlayer(player, ticket, now);
         }
      }
   }

   private boolean enterLimbo(Player player, String queueKey, String originServerId, ProxyLimboService.HoldReason holdReason) {
      if (!this.available()) {
         return false;
      } else {
         String normalizedQueueKey = this.normalizeQueueKey(queueKey);
         if (normalizedQueueKey.isBlank()) {
            normalizedQueueKey = this.routingService.initialQueueKey();
         }

         String existingQueueKey = this.queueService.queueKeyFor(player.getUniqueId()).orElse(null);
         if (existingQueueKey != null && !existingQueueKey.equalsIgnoreCase(normalizedQueueKey)) {
            this.queueService.leaveQueue(player.getUniqueId());
         }

         QueueService.QueueJoinResult joinResult = this.queueService.joinQueue(player.getUniqueId(), player.getUsername(), normalizedQueueKey);
         if (!joinResult.valid()) {
            return false;
         } else {
            UUID playerId = player.getUniqueId();
            ProxyLimboService.LimboTicket existing = this.tickets
               .put(
                  playerId,
                  new ProxyLimboService.LimboTicket(
                     player.getUniqueId(), player.getUsername(), normalizedQueueKey, originServerId, holdReason, System.currentTimeMillis()
                  )
               );
            this.rerouteAttemptAtMillis.remove(playerId);
            if (existing == null
               || !existing.queueKey().equalsIgnoreCase(normalizedQueueKey)
               || existing.holdReason() != holdReason
               || !this.activeLimboPlayers.containsKey(playerId) && !this.isLimboSessionActive(player)) {
               this.limboFor(holdReason).spawnPlayer(player, new ProxyLimboService.WaitingSessionHandler(player.getUniqueId()));
               this.logger
                  .info(
                     "Moved '{}' into proxy limbo for queue '{}' (origin='{}', reason={}).",
                     player.getUsername(),
                     normalizedQueueKey,
                     originServerId,
                     holdReason
                  );
               return true;
            } else {
               return true;
            }
         }
      }
   }

   private void refreshHeldPlayer(Player player, ProxyLimboService.LimboTicket ticket, long now) {
      if (!this.isUiSuppressed(player.getUniqueId())) {
         this.ensureQueueMembership(ticket);
         this.maybeAnnounceLoginAlternates(player, ticket, now);
         Optional<String> currentServerId = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
         if (currentServerId.isPresent()) {
            if (!this.isLimboSessionActive(player) && !this.activeLimboPlayers.containsKey(ticket.playerId())) {
               this.onBackendConnected(player);
            }
         }
      }
   }

   private void renderHeldHud(Player player, ProxyLimboService.LimboTicket ticket, long now) {
      if (!this.isUiSuppressed(player.getUniqueId())) {
         Component footer = this.buildHudFooter(player, ticket, now);
         player.sendPlayerListHeaderAndFooter(this.buildHudHeader(ticket, now), footer);
         player.sendActionBar(footer);
      }
   }

   private Component buildHudHeader(ProxyLimboService.LimboTicket ticket, long now) {
      String mm = "\n"
         + NetworkAnimationEngine.networkGlare(now)
         + "\n"
         + this.holdModeLine(ticket)
         + "\n<#94a3b8>Stay online and the proxy will route you automatically.</#94a3b8>\n";
      return ServerTextUtil.miniMessageComponent(mm);
   }

   private Component buildHudFooter(Player player, ProxyLimboService.LimboTicket ticket, long now) {
      Optional<String> currentServerId = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
      if (currentServerId.isPresent() && this.isLimboSessionActive(player)) {
         return this.buildTransferStatusLine(ticket, currentServerId.get());
      } else {
         Optional<RegisteredServer> bestTarget = this.bestTargetFor(ticket);
         String bestTargetId = bestTarget.<String>map(server -> server.getServerInfo().getName()).orElse(null);
         QueueStatusSnapshot status = this.queueService.status(ticket.playerId(), bestTargetId).orElse(null);
         return status == null ? this.buildFallbackStatusLine(ticket, bestTargetId, now) : this.buildStatusLine(status, ticket, now);
      }
   }

   private void startHudRefresh(LimboPlayer limboPlayer) {
      UUID playerId = limboPlayer.getProxyPlayer().getUniqueId();
      this.stopHudRefresh(playerId);
      ScheduledFuture<?> task = limboPlayer.getScheduledExecutor().scheduleAtFixedRate(() -> {
         if (this.isUiSuppressed(playerId)) {
            this.stopHudRefresh(playerId);
         } else {
            ProxyLimboService.LimboTicket ticket = this.tickets.get(playerId);
            if (ticket != null && this.activeLimboPlayers.get(playerId) == limboPlayer) {
               this.renderHeldHud(limboPlayer.getProxyPlayer(), ticket, System.currentTimeMillis());
            } else {
               this.stopHudRefresh(playerId);
            }
         }
      }, (long)this.config.limbo.statusRefreshMillis, (long)this.config.limbo.statusRefreshMillis, TimeUnit.MILLISECONDS);
      this.hudRefreshTasks.put(playerId, task);
   }

   private void stopHudRefresh(UUID playerId) {
      ScheduledFuture<?> task = this.hudRefreshTasks.remove(playerId);
      if (task != null) {
         task.cancel(false);
      }
   }

   private void ensureQueueMembership(ProxyLimboService.LimboTicket ticket) {
      Optional<QueueStatusSnapshot> currentStatus = this.queueService.status(ticket.playerId(), null);
      if (!currentStatus.isPresent() || !currentStatus.get().queueKey().equalsIgnoreCase(ticket.queueKey())) {
         this.queueService.leaveQueue(ticket.playerId());
         QueueService.QueueJoinResult repaired = this.queueService.joinQueue(ticket.playerId(), ticket.username(), ticket.queueKey());
         if (repaired.valid() && repaired.joined()) {
            this.logger.info("Repaired missing queue membership for '{}' in proxy limbo on queue '{}'.", ticket.username(), ticket.queueKey());
         }
      }
   }

   private void installKickCallback(Player player) {
      if (this.setKickCallbackMethod != null) {
         try {
            // setKickCallback(Player, Function) — the method reference needs an explicit
            // functional-interface target type when passed through reflective varargs.
            this.setKickCallbackMethod.invoke(
               this.limboPluginInstance, player, (Function<KickedFromServerEvent, Boolean>) this::handleKickCallback);
         } catch (ReflectiveOperationException var3) {
            this.logger.warn("Failed to install LimboAPI kick callback for '{}': {}", player.getUsername(), var3.getMessage());
         }
      }
   }

   private boolean handleKickCallback(KickedFromServerEvent event) {
      if (!this.available()) {
         return false;
      } else if (event.getResult() instanceof RedirectPlayer) {
         return false;
      } else {
         UUID playerId = event.getPlayer().getUniqueId();
         if (event.kickedDuringServerConnect() && this.isUiSuppressed(playerId)) {
            return false;
         } else {
            String originServerId = event.getServer().getServerInfo().getName();
            String queueKey = this.routingService.queueKeyForServer(originServerId);
            this.uiSuppressedPlayers.remove(playerId);
            this.stopHudRefresh(playerId);
            this.clearReleasedPlayerLimboState(event.getPlayer());
            if (!this.enterLimbo(event.getPlayer(), queueKey, originServerId, ProxyLimboService.HoldReason.OUTAGE)) {
               return false;
            } else {
               if (event.kickedDuringServerConnect()) {
                  this.logger.warn("Re-captured '{}' into proxy limbo after connect failure to backend '{}'.", event.getPlayer().getUsername(), originServerId);
               } else {
                  this.logger
                     .info(
                        "Captured '{}' into proxy limbo through LimboAPI kick callback after backend '{}' became unavailable.",
                        event.getPlayer().getUsername(),
                        originServerId
                     );
               }

               return true;
            }
         }
      }
   }

   private boolean isLimboSessionActive(Player player) {
      Object sessionHandler = this.activeSessionHandler(player);
      return sessionHandler != null && sessionHandler.getClass().getName().equals("net.elytrium.limboapi.server.LimboSessionHandlerImpl");
   }

   private Object activeSessionHandler(Player player) {
      try {
         Method getConnection = player.getClass().getMethod("getConnection");
         Object connection = getConnection.invoke(player);
         if (connection == null) {
            return null;
         } else {
            Method getActiveSessionHandler = connection.getClass().getMethod("getActiveSessionHandler");
            return getActiveSessionHandler.invoke(connection);
         }
      } catch (ReflectiveOperationException var5) {
         this.logger.warn("Failed to inspect active session handler for '{}': {}", player.getUsername(), var5.getMessage());
         return null;
      }
   }

   private Component buildStatusLine(QueueStatusSnapshot status, ProxyLimboService.LimboTicket ticket, long now) {
      String bestTarget = this.targetLabel(ticket, status.bestTargetServerId());
      String timerKey = this.maintenanceRegistry.isEnabled() ? "Maintenance" : "Hold";
      long timerMillis = this.maintenanceRegistry.isEnabled()
         ? this.maintenanceRegistry.activeForMillis(now)
         : Math.max(0L, now - ticket.joinedAtEpochMillis());
      String mm = "<gradient:#dbeafe:#60a5fa><bold>Queue</bold></gradient> <gradient:#93c5fd:#2563eb><bold>"
         + this.queueLabel(ticket.queueKey())
         + "</bold></gradient> <#475569>•</#475569> <#e2e8f0>#"
         + status.position()
         + "/"
         + status.queueSize()
         + "</#e2e8f0> <#475569>•</#475569> <#94a3b8>Target <#e2e8f0>"
         + bestTarget
         + "</#e2e8f0></#94a3b8> <#475569>•</#475569> <#94a3b8>"
         + timerKey
         + " <#e2e8f0>"
         + this.formatDuration(timerMillis)
         + "</#e2e8f0></#94a3b8>";
      return ServerTextUtil.miniMessageComponent(mm);
   }

   private Component buildTransferStatusLine(ProxyLimboService.LimboTicket ticket, String targetServerId) {
      String mm = "<gradient:#dbeafe:#60a5fa><bold>Queue</bold></gradient> <gradient:#93c5fd:#2563eb><bold>"
         + this.queueLabel(ticket.queueKey())
         + "</bold></gradient> <#475569>•</#475569> <#94a3b8>Routing to <#e2e8f0>"
         + targetServerId
         + "</#e2e8f0></#94a3b8> <#475569>•</#475569> <#e2e8f0>Stand by while the proxy completes the transfer.</#e2e8f0>";
      return ServerTextUtil.miniMessageComponent(mm);
   }

   private Component buildFallbackStatusLine(ProxyLimboService.LimboTicket ticket, String bestTargetId, long now) {
      String targetLabel = this.targetLabel(ticket, bestTargetId);
      String timerKey = this.maintenanceRegistry.isEnabled() ? "Maintenance" : "Hold";
      long timerMillis = this.maintenanceRegistry.isEnabled()
         ? this.maintenanceRegistry.activeForMillis(now)
         : Math.max(0L, now - ticket.joinedAtEpochMillis());
      String mm = "<gradient:#dbeafe:#60a5fa><bold>Queue</bold></gradient> <gradient:#93c5fd:#2563eb><bold>"
         + this.queueLabel(ticket.queueKey())
         + "</bold></gradient> <#475569>•</#475569> <#94a3b8>Target <#e2e8f0>"
         + targetLabel
         + "</#e2e8f0></#94a3b8> <#475569>•</#475569> <#94a3b8>Position <#e2e8f0>syncing</#e2e8f0></#94a3b8> <#475569>•</#475569> <#94a3b8>"
         + timerKey
         + " <#e2e8f0>"
         + this.formatDuration(timerMillis)
         + "</#e2e8f0></#94a3b8>";
      return ServerTextUtil.miniMessageComponent(mm);
   }

   private void sendIntro(Player player, ProxyLimboService.LimboTicket ticket) {
      if (!this.isUiSuppressed(player.getUniqueId())) {
         long now = System.currentTimeMillis();
         String queueLabel = this.queueLabel(ticket.queueKey());
         String modeLine = this.holdModeLine(ticket);
         String targetLabel = this.targetLabel(ticket, this.bestTargetFor(ticket).map(server -> server.getServerInfo().getName()).orElse(null));
         String body = this.maintenanceRegistry.isEnabled()
            ? "<#94a3b8>Your <#e2e8f0>"
               + queueLabel
               + "</#e2e8f0> route is paused while maintenance is active. Stay connected and you will be moved automatically.</#94a3b8>"
            : "<#94a3b8>Your route to <#e2e8f0>"
               + targetLabel
               + "</#e2e8f0> is being held at the proxy. Stay connected and you will be moved automatically.</#94a3b8>";
         String footerLine = "\n<#94a3b8>Queue <#e2e8f0>"
            + queueLabel
            + "</#e2e8f0> <#475569>•</#475569> <#94a3b8>Target <#e2e8f0>"
            + targetLabel
            + "</#e2e8f0></#94a3b8>\n";
         player.sendPlayerListHeaderAndFooter(
            ServerTextUtil.miniMessageComponent(
               "\n"
                  + NetworkAnimationEngine.networkGlare(now)
                  + "\n"
                  + modeLine
                  + "\n<#94a3b8>Stay online and the proxy will route you automatically.</#94a3b8>\n"
            ),
            ServerTextUtil.miniMessageComponent(footerLine)
         );
         player.showTitle(
            Title.title(
               ServerTextUtil.miniMessageComponent(modeLine),
               ServerTextUtil.miniMessageComponent("<#94a3b8>Queue: <#e2e8f0>" + queueLabel + "</#e2e8f0></#94a3b8>"),
               Times.times(Duration.ofMillis(250L), Duration.ofSeconds(2L), Duration.ofMillis(250L))
            )
         );
         player.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#dbeafe:#60a5fa><bold>SkyPvP Hold</bold></gradient> " + body));
         if (this.maintenanceRegistry.isEnabled()) {
            player.sendMessage(
               ServerTextUtil.miniMessageComponent(
                  "<#fbbf24>Maintenance is active.</#fbbf24> <#94a3b8>The timer in your limbo footer tracks the active maintenance window.</#94a3b8>"
               )
            );
         }
      }
   }

   private String targetLabel(ProxyLimboService.LimboTicket ticket, String bestTargetId) {
      return bestTargetId != null && !bestTargetId.isBlank() ? bestTargetId : this.queueLabel(ticket.queueKey());
   }

   private String holdModeLine(ProxyLimboService.LimboTicket ticket) {
      return this.maintenanceRegistry.isEnabled()
         ? "<gradient:#f59e0b:#fb923c><bold>Maintenance Hold</bold></gradient>"
         : "<gradient:#dbeafe:#60a5fa><bold>SkyPvP Hold</bold></gradient>";
   }

   private Optional<RegisteredServer> bestTargetFor(ProxyLimboService.LimboTicket ticket) {
      String normalizedQueueKey = this.normalizeQueueKey(ticket.queueKey());
      return normalizedQueueKey.equals(this.routingService.loginQueueKey())
         ? this.routingService.selectBestLoginServer()
         : this.routingService.selectBestTargetForQueue(normalizedQueueKey, Set.of());
   }

   private void maybeAnnounceLoginAlternates(Player player, ProxyLimboService.LimboTicket ticket, long now) {
      if (!this.isUiSuppressed(player.getUniqueId())) {
         if (ticket.holdReason() == ProxyLimboService.HoldReason.LOGIN && !this.maintenanceRegistry.isEnabled()) {
            if (this.routingService.selectBestLoginServer().isPresent()) {
               this.announcedLoginAlternates.remove(player.getUniqueId());
               this.loginAlternateReminderAtMillis.remove(player.getUniqueId());
            } else {
               Map<String, ProxyLimboService.LoginAlternateTarget> alternates = this.availableLoginAlternateTargets();
               if (alternates.isEmpty()) {
                  this.announcedLoginAlternates.remove(player.getUniqueId());
                  this.loginAlternateReminderAtMillis.remove(player.getUniqueId());
               } else {
                  Set<String> optionKeys = new LinkedHashSet<>(alternates.keySet());
                  Set<String> previousOptionKeys = this.announcedLoginAlternates.get(player.getUniqueId());
                  boolean availabilityChanged = previousOptionKeys == null || !previousOptionKeys.equals(optionKeys);
                  long lastReminderAt = this.loginAlternateReminderAtMillis.getOrDefault(player.getUniqueId(), 0L);
                  boolean reminderDue = now - lastReminderAt >= 30000L;
                  if (availabilityChanged || reminderDue) {
                     this.announcedLoginAlternates.put(player.getUniqueId(), Set.copyOf(optionKeys));
                     this.loginAlternateReminderAtMillis.put(player.getUniqueId(), now);
                     player.sendMessage(this.buildLoginAlternateTargetsMessage(alternates.values(), availabilityChanged));
                  }
               }
            }
         }
      }
   }

   private Map<String, ProxyLimboService.LoginAlternateTarget> availableLoginAlternateTargets() {
      Map<String, ProxyLimboService.LoginAlternateTarget> alternates = new LinkedHashMap<>();

      for (ServerRoutingService.ServerRouteStatus status : this.routingService.snapshotStatuses()) {
         if (status.isHealthyJoinTarget() && !"LOBBY".equalsIgnoreCase(status.role())) {
            String queueKey = this.alternateQueueKey(status);
            if (!queueKey.isBlank() && !queueKey.equalsIgnoreCase(this.routingService.loginQueueKey()) && !alternates.containsKey(queueKey)) {
               Optional<RegisteredServer> bestTarget = this.routingService.selectBestTargetForQueue(queueKey, Set.of());
               if (!bestTarget.isEmpty()) {
                  alternates.put(
                     queueKey, new ProxyLimboService.LoginAlternateTarget(queueKey, this.queueLabel(queueKey), bestTarget.get().getServerInfo().getName())
                  );
               }
            }
         }
      }

      return alternates;
   }

   private String alternateQueueKey(ServerRoutingService.ServerRouteStatus status) {
      if (status.cluster() != null && !status.cluster().isBlank()) {
         return this.normalizeQueueKey(status.cluster());
      } else {
         return status.role() != null && !status.role().isBlank() ? this.normalizeQueueKey(status.role()) : this.normalizeQueueKey(status.serverId());
      }
   }

   private Component buildLoginAlternateTargetsMessage(Collection<ProxyLimboService.LoginAlternateTarget> alternates, boolean availabilityChanged) {
      String intro = availabilityChanged
         ? "<#94a3b8>Lobby is still offline. Another mode just came online if you would rather play now:</#94a3b8> "
         : "<#94a3b8>Still waiting on <#e2e8f0>Lobby</#e2e8f0>. You can join another live mode instead:</#94a3b8> ";
      Component message = ServerTextUtil.miniMessageComponent("<gradient:#dbeafe:#60a5fa><bold>Live Modes</bold></gradient> " + intro);
      boolean first = true;

      for (ProxyLimboService.LoginAlternateTarget alternate : alternates) {
         if (!first) {
            message = message.append(ServerTextUtil.miniMessageComponent(" <#475569>•</#475569> "));
         }

         message = message.append(
            ServerTextUtil.commandChip(
               alternate.label(),
               "/play " + alternate.queueKey(),
               "<#94a3b8>Route to <#e2e8f0>" + alternate.label() + "</#e2e8f0> through the player-safe <#e2e8f0>/play</#e2e8f0> command.</#94a3b8>"
            )
         );
         first = false;
      }

      return message;
   }

   private Object createInternalLimboPlayer(Player player, Limbo limbo) throws ReflectiveOperationException {
      Class<?> limboPlayerImplClass = Class.forName("net.elytrium.limboapi.server.LimboPlayerImpl");

      for (Constructor<?> constructor : limboPlayerImplClass.getConstructors()) {
         if (constructor.getParameterCount() == 3) {
            return constructor.newInstance(this.limboPluginInstance, limbo, player);
         }
      }

      throw new NoSuchMethodException("LimboPlayerImpl constructor not found.");
   }

   private void prepareLimboInventory(LimboPlayer player) {
      for (int slot = 36; slot <= 44; slot++) {
         player.setInventory(this.airItem, slot, 0);
      }
   }

   private boolean isStaffExempt(Player player) {
      return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.staff") || player.hasPermission("skypvp.maintenance.bypass");
   }

   private Object resolvePluginInstance(ProxyServer proxyServer) {
      return proxyServer.getPluginManager()
         .getPlugin("luckyfeed-limbo")
         .flatMap(PluginContainer::getInstance)
         .orElseThrow(() -> new IllegalStateException("SkyPvP Limbo Core is not loaded on the proxy."));
   }

   private LimboFactory resolveFactory(Object instance) {
      if (instance instanceof LimboFactory) {
         return (LimboFactory)instance;
      } else {
         throw new IllegalStateException("SkyPvP Limbo Core does not expose LimboFactory.");
      }
   }

   private Method resolveSetKickCallbackMethod(Object instance) {
      try {
         return instance.getClass().getMethod("setKickCallback", Player.class, Function.class);
      } catch (NoSuchMethodException var3) {
         this.logger
            .warn("Installed SkyPvP Limbo Core build does not expose kick callback registration; current-backend outage capture will remain limited.");
         return null;
      }
   }

   private Method resolvePlayerCleanupMethod(Object instance, String methodName) {
      try {
         return instance.getClass().getMethod(methodName, Player.class);
      } catch (NoSuchMethodException var4) {
         return null;
      }
   }

   private void clearReleasedPlayerLimboState(Player player) {
      this.invokePlayerCleanupMethod(this.unsetLimboJoinedMethod, player, "unset limbo-joined state");
      this.invokePlayerCleanupMethod(this.removeKickCallbackMethod, player, "remove kick callback state");
      this.invokePlayerCleanupMethod(this.removeLoginQueueMethod, player, "remove login queue state");
      this.invokePlayerCleanupMethod(this.removeNextServerMethod, player, "remove next-server state");
   }

   private void invokePlayerCleanupMethod(Method method, Player player, String description) {
      if (method != null && player != null) {
         try {
            method.invoke(this.limboPluginInstance, player);
         } catch (ReflectiveOperationException var5) {
            this.logger.warn("Failed to {} for '{}': {}", description, player.getUsername(), var5.getMessage());
         }
      }
   }

   private Limbo buildLimbo(String limboName, boolean shouldRejoin, boolean shouldRespawn) {
      VirtualWorld world = this.limboFactory
         .createVirtualWorld(
            Dimension.OVERWORLD,
            this.config.limbo.spawnX,
            this.config.limbo.spawnY,
            this.config.limbo.spawnZ,
            this.config.limbo.spawnYaw,
            this.config.limbo.spawnPitch
         );
      world.fillBlockLight(this.config.limbo.platformLightLevel);
      world.fillSkyLight(this.config.limbo.platformLightLevel);
      VirtualBlock platform = this.limboFactory.createSimpleBlock("minecraft:polished_deepslate");
      VirtualBlock rim = this.limboFactory.createSimpleBlock("minecraft:sea_lantern");

      for (int x = -this.config.limbo.platformRadius; x <= this.config.limbo.platformRadius; x++) {
         for (int z = -this.config.limbo.platformRadius; z <= this.config.limbo.platformRadius; z++) {
            boolean edge = Math.abs(x) == this.config.limbo.platformRadius || Math.abs(z) == this.config.limbo.platformRadius;
            world.setBlock(x, this.config.limbo.platformY, z, edge ? rim : platform);
         }
      }

      return this.limboFactory
         .createLimbo(world)
         .setName(limboName)
         .setReadTimeout(this.config.limbo.readTimeoutMillis)
         .setWorldTime(this.config.limbo.worldTime)
         .setGameMode(GameMode.ADVENTURE)
         .setShouldRejoin(shouldRejoin)
         .setShouldRespawn(shouldRespawn)
         .setReducedDebugInfo(true)
         .setViewDistance(6)
         .setSimulationDistance(4);
   }

   private Limbo limboFor(ProxyLimboService.HoldReason holdReason) {
      return holdReason == ProxyLimboService.HoldReason.LOGIN ? this.loginLimbo : this.outageLimbo;
   }

   private String normalizeQueueKey(String queueKey) {
      return queueKey == null ? "" : queueKey.trim().toLowerCase(Locale.ROOT);
   }

   private String queueLabel(String queueKey) {
      String normalized = this.normalizeQueueKey(queueKey);
      if (this.routingService.isInitialQueueKey(normalized)) {
         return "Network Entry";
      } else {
         return switch (normalized) {
            case "survival", "smp", "minigame", "minigames" -> "Extraction";
            case "extraction" -> "Extraction";
            case "lobby" -> "Lobby";
            default -> this.titleCase(normalized.replace('-', ' ').replace('_', ' '));
         };
      }
   }

   private String originLabel(String originServerId, String queueKey) {
      return originServerId != null && !originServerId.isBlank() && !"proxy-login".equalsIgnoreCase(originServerId)
         ? originServerId
         : this.queueLabel(queueKey);
   }

   private String titleCase(String value) {
      if (value != null && !value.isBlank()) {
         String[] parts = value.split("\\s+");
         StringBuilder out = new StringBuilder();

         for (String part : parts) {
            if (!part.isBlank()) {
               if (!out.isEmpty()) {
                  out.append(' ');
               }

               out.append(Character.toUpperCase(part.charAt(0)));
               if (part.length() > 1) {
                  out.append(part.substring(1).toLowerCase(Locale.ROOT));
               }
            }
         }

         return out.isEmpty() ? "Queue" : out.toString();
      } else {
         return "Queue";
      }
   }

   private String formatDuration(long millis) {
      long totalSeconds = Math.max(0L, millis / 1000L);
      long hours = totalSeconds / 3600L;
      long minutes = totalSeconds % 3600L / 60L;
      long seconds = totalSeconds % 60L;
      return hours > 0L ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds) : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
   }

   private boolean isUiSuppressed(UUID playerId) {
      return this.uiSuppressedPlayers.contains(playerId);
   }

   private static enum HoldReason {
      LOGIN,
      MAINTENANCE,
      OUTAGE;

      private HoldReason() {
      }
   }

   private static record LimboTicket(
      UUID playerId, String username, String queueKey, String originServerId, ProxyLimboService.HoldReason holdReason, long joinedAtEpochMillis
   ) {
   }

   private static record LoginAlternateTarget(String queueKey, String label, String targetServerId) {
   }

   private final class WaitingSessionHandler implements LimboSessionHandler {
      private final UUID playerId;

      private WaitingSessionHandler(UUID playerId) {
         Objects.requireNonNull(ProxyLimboService.this);
         super();
         this.playerId = playerId;
      }

      public void onSpawn(Limbo server, LimboPlayer player) {
         ProxyLimboService.LimboTicket ticket = ProxyLimboService.this.tickets.get(this.playerId);
         if (ticket != null) {
            ProxyLimboService.this.uiSuppressedPlayers.remove(this.playerId);
            ProxyLimboService.this.activeLimboPlayers.put(this.playerId, player);
            Player proxyPlayer = player.getProxyPlayer();
            ProxyLimboService.this.prepareLimboInventory(player);
            ProxyLimboService.this.sendIntro(proxyPlayer, ticket);
            ProxyLimboService.this.refreshHeldPlayer(proxyPlayer, ticket, System.currentTimeMillis());
            ProxyLimboService.this.renderHeldHud(proxyPlayer, ticket, System.currentTimeMillis());
            ProxyLimboService.this.startHudRefresh(player);
         }
      }

      public void onChat(String message) {
         LimboPlayer activeLimboPlayer = ProxyLimboService.this.activeLimboPlayers.get(this.playerId);
         Player proxyPlayer = activeLimboPlayer == null ? null : activeLimboPlayer.getProxyPlayer();
         if (proxyPlayer != null) {
            String trimmed = message == null ? "" : message.trim();
            if (!trimmed.isBlank()) {
               if (trimmed.startsWith("/")) {
                  String commandLine = trimmed.substring(1).trim();
                  if (!commandLine.isBlank()) {
                     ProxyLimboService.this.proxyServer
                        .getCommandManager()
                        .executeImmediatelyAsync(proxyPlayer, commandLine)
                        .thenAccept(
                           executed -> {
                              if (!executed) {
                                 proxyPlayer.sendMessage(
                                    ServerTextUtil
                                       .miniMessageComponent(
                                          "<#fbbf24>Unknown limbo command.</#fbbf24> <#94a3b8>Try <#e2e8f0>/play lobby</#e2e8f0>, <#e2e8f0>/play extraction</#e2e8f0>, or <#e2e8f0>/queue status</#e2e8f0>.</#94a3b8>"
                                       )
                                 );
                              }
                           }
                        );
                  }
               } else {
                  proxyPlayer.sendMessage(
                     ServerTextUtil
                        .miniMessageComponent(
                           "<#94a3b8>Chat is limited while you are held in proxy limbo.</#94a3b8> <#94a3b8>Use <#e2e8f0>/play <mode></#e2e8f0> from the live mode suggestions, or stay connected and the proxy will route you automatically.</#94a3b8>"
                        )
                  );
               }
            }
         }
      }

      public void onDisconnect() {
         ProxyLimboService.this.stopHudRefresh(this.playerId);
         ProxyLimboService.this.activeLimboPlayers.remove(this.playerId);
      }
   }
}
