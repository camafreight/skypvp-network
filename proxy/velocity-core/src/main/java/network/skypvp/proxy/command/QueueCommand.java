package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.repository.FriendRepository;

import network.skypvp.proxy.service.AdmissionControlService;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.QueueDrainService;
import network.skypvp.proxy.service.QueueService;
import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.shared.QueueJoinRequest;
import network.skypvp.shared.QueueLeaveRequest;
import network.skypvp.shared.QueueStatusSnapshot;
import network.skypvp.shared.RedisEventPublisher;

public final class QueueCommand {
   private final ProxyServer proxyServer;
   private final ProxyBootstrapConfig config;
   private final QueueService queueService;
   private final QueueDrainService queueDrainService;
   private final ServerRoutingService routingService;
   private final PartyService partyService;
   private final PartyQueueService partyQueueService;
   private final NetworkStateRegistry stateRegistry;
   private final FriendRepository friendRepository;
   private final RedisEventPublisher redisPublisher;

   public QueueCommand(
      ProxyServer proxyServer,
      ProxyBootstrapConfig config,
      QueueService queueService,
      QueueDrainService queueDrainService,
      ServerRoutingService routingService,
      PartyService partyService,
      PartyQueueService partyQueueService,
      NetworkStateRegistry stateRegistry,
      FriendRepository friendRepository,
      RedisEventPublisher redisPublisher
   ) {
      this.proxyServer = proxyServer;
      this.config = config;
      this.queueService = queueService;
      this.queueDrainService = queueDrainService;
      this.routingService = routingService;
      this.partyService = partyService;
      this.partyQueueService = partyQueueService;
      this.stateRegistry = stateRegistry;
      this.friendRepository = friendRepository;
      this.redisPublisher = redisPublisher;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal(
                                    "queue"
                                 )
                                 .then(
                                    LiteralArgumentBuilder.literal("join")
                                       .then(RequiredArgumentBuilder.argument("queue", StringArgumentType.word()).suggests((ctx, builder) -> {
                                          this.knownQueueKeys().forEach(builder::suggest);
                                          return builder.buildFuture();
                                       }).executes(ctx -> {
                                          this.executeJoin((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "queue"));
                                          return 1;
                                       }))
                                 ))
                              .then(
                                 LiteralArgumentBuilder.literal("follow")
                                    .then(RequiredArgumentBuilder.argument("friend", StringArgumentType.word()).suggests((ctx, builder) -> {
                                       this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                                       return builder.buildFuture();
                                    }).executes(ctx -> {
                                       this.executeFollow((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "friend"));
                                       return 1;
                                    }))
                              ))
                              .then(LiteralArgumentBuilder.literal("leave").executes(ctx -> {
                                 this.executeLeave((CommandSource)ctx.getSource());
                                 return 1;
                              })))
                           .then(
                              ((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("status")
                                    .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
                                       this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                                       return builder.buildFuture();
                                    }).executes(ctx -> {
                                       this.executeStatus((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "player"));
                                       return 1;
                                    })))
                                 .executes(ctx -> {
                                    this.executeOwnStatus((CommandSource)ctx.getSource());
                                    return 1;
                                 })
                           ))
                        .then(LiteralArgumentBuilder.literal("list").executes(ctx -> {
                           this.executeList((CommandSource)ctx.getSource());
                           return 1;
                        })))
                     .then(
                        LiteralArgumentBuilder.literal("drain")
                           .then(RequiredArgumentBuilder.argument("queue", StringArgumentType.word()).suggests((ctx, builder) -> {
                              this.queueService.queueKeys().forEach(builder::suggest);
                              return builder.buildFuture();
                           }).executes(ctx -> {
                              this.executeDrain((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "queue"));
                              return 1;
                           }))
                     ))
                  .then(
                     LiteralArgumentBuilder.literal("clear")
                        .then(RequiredArgumentBuilder.argument("queue", StringArgumentType.word()).suggests((ctx, builder) -> {
                           this.queueService.queueKeys().forEach(builder::suggest);
                           return builder.buildFuture();
                        }).executes(ctx -> {
                           this.executeClear((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "queue"));
                           return 1;
                        }))
                  ))
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("throttle")
                              .then(LiteralArgumentBuilder.literal("status").executes(ctx -> {
                                 this.executeThrottleStatus((CommandSource)ctx.getSource());
                                 return 1;
                              })))
                           .then(LiteralArgumentBuilder.literal("enable").executes(ctx -> {
                              this.executeThrottleToggle((CommandSource)ctx.getSource(), true);
                              return 1;
                           })))
                        .then(LiteralArgumentBuilder.literal("disable").executes(ctx -> {
                           this.executeThrottleToggle((CommandSource)ctx.getSource(), false);
                           return 1;
                        })))
                     .then(
                        LiteralArgumentBuilder.literal("set")
                           .then(
                              RequiredArgumentBuilder.argument("perSecond", IntegerArgumentType.integer(1, 5000))
                                 .then(
                                    RequiredArgumentBuilder.argument("burst", IntegerArgumentType.integer(1, 10000))
                                       .executes(
                                          ctx -> {
                                             this.executeThrottleSet(
                                                (CommandSource)ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "perSecond"),
                                                IntegerArgumentType.getInteger(ctx, "burst")
                                             );
                                             return 1;
                                          }
                                       )
                                 )
                           )
                     )
               ))
            .executes(ctx -> {
               ((CommandSource)ctx.getSource())
                  .sendMessage(ServerTextUtil.component("&eUsage: /queue <join|follow|leave|status|list|drain|clear|throttle>"));
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void executeJoin(CommandSource source, String rawQueueKey) {
      if (source instanceof Player player) {
         String queueKey = this.normalizeQueueKey(rawQueueKey);
         if (!this.knownQueueKeys().contains(queueKey)) {
            source.sendMessage(ServerTextUtil.component("&c" + "Unknown queue '" + rawQueueKey + "'."));
         } else {
            if (this.partyService != null && this.partyQueueService != null) {
               Optional<PartyService.PartyState> party = this.partyService.partyForMember(player.getUniqueId());
               if (party.isPresent()) {
                  PartyService.PartyState state = party.get();
                  if (!state.leaderId().equals(player.getUniqueId())) {
                     source.sendMessage(ServerTextUtil.component("&eOnly the party leader can queue the party."));
                     return;
                  }

                  List<UUID> onlineMembers = this.partyService.onlineMembers(this.proxyServer, state.partyId());
                  if (onlineMembers.size() > 1) {
                     PartyQueueService.QueueGroupResult grouped = this.partyQueueService.enqueue(queueKey, state.partyId(), state.leaderId(), onlineMembers);
                     if (grouped.alreadyQueued()) {
                        source.sendMessage(ServerTextUtil.component("&eYour party is already queued as a group."));
                        return;
                     }

                     if (!grouped.valid()) {
                        source.sendMessage(ServerTextUtil.component("&cParty queue request is invalid."));
                        return;
                     }

                     source.sendMessage(
                        ServerTextUtil.component("&a" + "Queued party for '" + queueKey + "' at group position " + grouped.position() + ".")
                     );
                     return;
                  }
               }
            }

            QueueService.QueueJoinResult result = this.queueService.joinQueue(player.getUniqueId(), player.getUsername(), queueKey);
            if (!result.valid()) {
               source.sendMessage(ServerTextUtil.component("&cQueue request is invalid."));
            } else if (result.requiresSwapConfirmation()) {
               source.sendMessage(
                  ServerTextUtil.component("You are currently in the '"
                        + result.queueKey()
                        + "' queue. Run the command again to leave it and join '"
                        + result.targetQueueKey()
                        + "'.",
                     NamedTextColor.YELLOW
                  )
               );
            } else if (result.alreadyQueued()) {
               source.sendMessage(
                  ServerTextUtil.component("&e" + "You are already queued for '" + result.queueKey() + "' at position " + result.position() + ".")
               );
            } else {
               if (this.redisPublisher != null) {
                  this.redisPublisher
                     .publishJson(
                        "SkyPvP:network:queues", new QueueJoinRequest(player.getUniqueId(), player.getUsername(), queueKey, System.currentTimeMillis())
                     );
               }

               String bestTarget = this.routingService
                  .selectBestTargetForQueue(queueKey, Set.of())
                  .map(server -> server.getServerInfo().getName())
                  .orElse("none yet");
               source.sendMessage(
                  ServerTextUtil.component("Joined queue '" + queueKey + "' at position " + result.position() + " (best target: " + bestTarget + ").", NamedTextColor.GREEN
                  )
               );
            }
         }
      } else {
         source.sendMessage(ServerTextUtil.component("&cOnly players can join queues."));
      }
   }

   private void executeFollow(CommandSource source, String friendName) {
      if (!(source instanceof Player player)) {
         source.sendMessage(ServerTextUtil.component("&cOnly players can follow friends through queues."));
         return;
      }

      if (this.friendRepository == null) {
         source.sendMessage(ServerTextUtil.component("&cFriend follow is unavailable right now."));
         return;
      }

      Optional<Player> friend = this.proxyServer.getPlayer(friendName);
      if (friend.isEmpty()) {
         source.sendMessage(ServerTextUtil.component("&c" + "Player '" + friendName + "' is not online."));
         return;
      }

      Player targetFriend = friend.get();
      if (targetFriend.getUniqueId().equals(player.getUniqueId())) {
         source.sendMessage(ServerTextUtil.component("&eYou are already with yourself."));
         return;
      }

      if (!this.friendRepository.areFriends(player.getUniqueId(), targetFriend.getUniqueId())) {
         source.sendMessage(ServerTextUtil.component("&cYou can only follow players on your friends list."));
         return;
      }

      Optional<String> targetServerId = targetFriend.getCurrentServer().map(connection -> connection.getServerInfo().getName());
      if (targetServerId.isEmpty()) {
         source.sendMessage(ServerTextUtil.component("&e" + targetFriend.getUsername() + " is not connected to a joinable server yet."));
         return;
      }

      String serverId = targetServerId.get();
      String currentServerId = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
      if (serverId.equalsIgnoreCase(currentServerId)) {
         source.sendMessage(ServerTextUtil.component("&eYou are already on " + serverId + " with " + targetFriend.getUsername() + "."));
         return;
      }

      Optional<ServerRoutingService.ServerRouteStatus> routeStatus = this.routingService.describeServer(serverId);
      if (routeStatus.isEmpty() || !routeStatus.get().isHealthyJoinTarget()) {
         source.sendMessage(ServerTextUtil.component("&c" + targetFriend.getUsername() + "'s server is not accepting joins right now."));
         return;
      }

      QueueService.QueueJoinResult result = this.queueService.joinQueue(player.getUniqueId(), player.getUsername(), this.normalizeQueueKey(serverId));
      this.sendFollowQueueResult(source, player, targetFriend, serverId, result);
   }

   private void sendFollowQueueResult(CommandSource source, Player player, Player friend, String serverId, QueueService.QueueJoinResult result) {
      if (!result.valid()) {
         source.sendMessage(ServerTextUtil.component("&cFollow queue request is invalid."));
      } else if (result.requiresSwapConfirmation()) {
         source.sendMessage(
            ServerTextUtil.component("You are currently in the '"
                  + result.queueKey()
                  + "' queue. Run /queue follow "
                  + friend.getUsername()
                  + " again to leave it and follow them to '"
                  + result.targetQueueKey()
                  + "'.",
               NamedTextColor.YELLOW
            )
         );
      } else if (result.alreadyQueued()) {
         source.sendMessage(
            ServerTextUtil.component("&e" + "You are already queued to follow " + friend.getUsername() + " to " + serverId + " at position " + result.position() + ".")
         );
      } else {
         if (this.redisPublisher != null) {
            this.redisPublisher
               .publishJson("SkyPvP:network:queues", new QueueJoinRequest(player.getUniqueId(), player.getUsername(), result.queueKey(), System.currentTimeMillis()));
         }

         int slots = this.routingService.availableSlotsForServer(serverId);
         source.sendMessage(
            ServerTextUtil.component("Queued to follow "
                  + friend.getUsername()
                  + " to "
                  + serverId
                  + " at position "
                  + result.position()
                  + (slots > 0 ? " (checking admission now)." : " (waiting for a slot)."),
               NamedTextColor.GREEN
            )
         );
      }
   }

   private void executeLeave(CommandSource source) {
      if (source instanceof Player player) {
         if (this.partyQueueService != null && this.partyQueueService.removeByMember(player.getUniqueId())) {
            source.sendMessage(ServerTextUtil.component("&aRemoved your party group from queue."));
         } else {
            QueueService.QueueLeaveResult result = this.queueService.leaveQueue(player.getUniqueId());
            if (!result.left()) {
               source.sendMessage(ServerTextUtil.component("&eYou are not currently queued."));
            } else {
               if (this.redisPublisher != null) {
                  this.redisPublisher
                     .publishJson("SkyPvP:network:queues", new QueueLeaveRequest(player.getUniqueId(), result.queueKey(), System.currentTimeMillis()));
               }

               source.sendMessage(ServerTextUtil.component("&a" + "Left queue '" + result.queueKey() + "'."));
            }
         }
      } else {
         source.sendMessage(ServerTextUtil.component("&cOnly players can leave queues."));
      }
   }

   private void executeOwnStatus(CommandSource source) {
      if (source instanceof Player player) {
         this.showStatus(source, player.getUniqueId(), player.getUsername());
      } else {
         source.sendMessage(ServerTextUtil.component("&eUsage: /queue status <player>"));
      }
   }

   private void executeStatus(CommandSource source, String targetName) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         Optional<Player> target = this.proxyServer.getPlayer(targetName);
         if (target.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&c" + "Player '" + targetName + "' is not online."));
         } else {
            this.showStatus(source, target.get().getUniqueId(), target.get().getUsername());
         }
      }
   }

   private void executeList(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         Map<String, Integer> sizes = this.queueService.queueSizes();
         source.sendMessage(ServerTextUtil.component("&b" + "Queue sizes: " + (sizes.isEmpty() ? "none" : sizes)));
         if (this.partyQueueService != null) {
            Map<String, Integer> groupedSizes = this.partyQueueService.queueSizes();
            PartyQueueService.PartyQueueMetrics metrics = this.partyQueueService.metricsSnapshot();
            source.sendMessage(
               ServerTextUtil.component("Party queue groups: "
                     + (groupedSizes.isEmpty() ? "none" : groupedSizes)
                     + " | activeGroups="
                     + metrics.activeGroups()
                     + " | queuedMembers="
                     + metrics.queuedMembers()
                     + " | movedTotal="
                     + metrics.totalMovedGroups()
                     + " | blockedCapacity="
                     + metrics.blockedCapacity()
                     + " | blockedNoTarget="
                     + metrics.blockedNoTarget(),
                  NamedTextColor.AQUA
               )
            );
         }
      }
   }

   private void executeDrain(CommandSource source, String rawQueueKey) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         String queueKey = this.normalizeQueueKey(rawQueueKey);
         int moved = this.queueDrainService.drainQueue(queueKey);
         source.sendMessage(ServerTextUtil.component("&a" + "Drained queue '" + queueKey + "' -> moved " + moved + " player(s)."));
      }
   }

   private void executeClear(CommandSource source, String rawQueueKey) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         String queueKey = this.normalizeQueueKey(rawQueueKey);
         int cleared = this.queueService.clearQueue(queueKey);
         source.sendMessage(ServerTextUtil.component("&a" + "Cleared queue '" + queueKey + "' -> removed " + cleared + " player(s)."));
      }
   }

   private void executeThrottleStatus(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         AdmissionControlService.AdmissionSnapshot snapshot = this.queueDrainService.admissionSnapshot();
         source.sendMessage(
            ServerTextUtil.component("Admission throttle: enabled="
                  + snapshot.enabled()
                  + " | perSecond="
                  + snapshot.transfersPerSecond()
                  + " | burst="
                  + snapshot.burstCapacity()
                  + " | availableTokens="
                  + (int)Math.floor(snapshot.availableTokens())
                  + " | maxDrainPerPass="
                  + this.queueDrainService.maxTransfersPerDrainPass(),
               NamedTextColor.AQUA
            )
         );
      }
   }

   private void executeThrottleToggle(CommandSource source, boolean enabled) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         this.queueDrainService.setAdmissionEnabled(enabled);
         source.sendMessage(ServerTextUtil.component("&a" + "Admission throttle " + (enabled ? "enabled" : "disabled") + "."));
      }
   }

   private void executeThrottleSet(CommandSource source, int perSecond, int burst) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         this.queueDrainService.reconfigureAdmission(perSecond, burst);
         AdmissionControlService.AdmissionSnapshot snapshot = this.queueDrainService.admissionSnapshot();
         source.sendMessage(
            ServerTextUtil.component("Admission throttle updated: perSecond=" + snapshot.transfersPerSecond() + " | burst=" + snapshot.burstCapacity() + ".", NamedTextColor.GREEN
            )
         );
      }
   }

   private void showStatus(CommandSource source, UUID playerId, String username) {
      String queueKey = this.queueService.queueKeyFor(playerId).orElse(null);
      String bestTarget = queueKey == null
         ? null
         : this.routingService.selectBestTargetForQueue(queueKey, Set.of()).map(server -> server.getServerInfo().getName()).orElse(null);
      Optional<QueueStatusSnapshot> status = this.queueService.status(playerId, bestTarget);
      if (status.isEmpty()) {
         source.sendMessage(ServerTextUtil.component("&e" + username + " is not currently queued."));
      } else {
         QueueStatusSnapshot snapshot = status.get();
         source.sendMessage(
            ServerTextUtil.component(snapshot.username()
                  + " -> queue="
                  + snapshot.queueKey()
                  + " | position="
                  + snapshot.position()
                  + "/"
                  + snapshot.queueSize()
                  + " | bestTarget="
                  + (snapshot.bestTargetServerId() == null ? "none" : snapshot.bestTargetServerId()),
               NamedTextColor.AQUA
            )
         );
      }
   }

   private Set<String> knownQueueKeys() {
      Set<String> keys = new LinkedHashSet<>();
      if (this.config.limbo != null && this.config.limbo.initialQueueKey != null && !this.config.limbo.initialQueueKey.isBlank()) {
         keys.add(this.normalizeQueueKey(this.config.limbo.initialQueueKey));
      }

      this.config.backendServers.forEach(server -> {
         if (server.cluster != null && !server.cluster.isBlank()) {
            keys.add(this.normalizeQueueKey(server.cluster));
         }

         if (server.role != null && !server.role.isBlank()) {
            keys.add(this.normalizeQueueKey(server.role));
         }

         if (server.serverId != null && !server.serverId.isBlank()) {
            keys.add(this.normalizeQueueKey(server.serverId));
         }
      });
      return keys;
   }

   private String normalizeQueueKey(String rawQueueKey) {
      return rawQueueKey == null ? "" : rawQueueKey.trim().toLowerCase(Locale.ROOT);
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
