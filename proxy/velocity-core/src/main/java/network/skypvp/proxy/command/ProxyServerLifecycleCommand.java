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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.proxy.repository.ServerRegistryRepository;
import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.proxy.state.ServerLifecycleState;

public final class ProxyServerLifecycleCommand {
   private final ProxyServer proxyServer;
   private final ServerRegistryRepository serverRegistryRepository;
   private final ServerRoutingService routingService;
   private final NetworkStateRegistry stateRegistry;

   public ProxyServerLifecycleCommand(
      ProxyServer proxyServer,
      ServerRegistryRepository serverRegistryRepository,
      ServerRoutingService routingService,
      NetworkStateRegistry stateRegistry
   ) {
      this.proxyServer = proxyServer;
      this.serverRegistryRepository = serverRegistryRepository;
      this.routingService = routingService;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("serverlifecycle");
      root.then(LiteralArgumentBuilder.<CommandSource>literal("list").executes(ctx -> {
         this.executeList((CommandSource)ctx.getSource());
         return 1;
      }));
      root.then(LiteralArgumentBuilder.<CommandSource>literal("show").then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word()).suggests((ctx, builder) -> {
         this.proxyServer.getAllServers().forEach(server -> builder.suggest(server.getServerInfo().getName()));
         return builder.buildFuture();
      }).executes(ctx -> {
         this.executeShow((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "server"));
         return 1;
      })));
      root.then(
         LiteralArgumentBuilder.<CommandSource>literal("set")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                  .suggests((ctx, builder) -> {
                     this.proxyServer.getAllServers().forEach(server -> builder.suggest(server.getServerInfo().getName()));
                     return builder.buildFuture();
                  })
                  .then(
                     ((RequiredArgumentBuilder)((RequiredArgumentBuilder)RequiredArgumentBuilder.<CommandSource, String>argument("state", StringArgumentType.word())
                              .suggests((ctx, builder) -> {
                                 Arrays.stream(ServerLifecycleState.values()).forEach(s -> builder.suggest(s.name()));
                                 return builder.buildFuture();
                              })
                              .executes(
                                 ctx -> {
                                    this.executeSet(
                                       (CommandSource)ctx.getSource(),
                                       StringArgumentType.getString(ctx, "server"),
                                       StringArgumentType.getString(ctx, "state"),
                                       "manual lifecycle update",
                                       0L
                                    );
                                    return 1;
                                 }
                              ))
                           .then(
                              RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString())
                                 .executes(
                                    ctx -> {
                                       this.executeSet(
                                          (CommandSource)ctx.getSource(),
                                          StringArgumentType.getString(ctx, "server"),
                                          StringArgumentType.getString(ctx, "state"),
                                          StringArgumentType.getString(ctx, "reason"),
                                          0L
                                       );
                                       return 1;
                                    }
                                 )
                           ))
                        .then(
                           RequiredArgumentBuilder.<CommandSource, Integer>argument("generation", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
                              .executes(
                                 ctx -> {
                                    this.executeSet(
                                       (CommandSource)ctx.getSource(),
                                       StringArgumentType.getString(ctx, "server"),
                                       StringArgumentType.getString(ctx, "state"),
                                       "manual lifecycle update",
                                       (long)IntegerArgumentType.getInteger(ctx, "generation")
                                    );
                                    return 1;
                                 }
                              )
                        )
                  )
            )
      );
      root.then(((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.<CommandSource>literal("history").executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), null, 20);
         return 1;
      })).then(RequiredArgumentBuilder.<CommandSource, Integer>argument("limit", IntegerArgumentType.integer(1, 200)).executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), null, IntegerArgumentType.getInteger(ctx, "limit"));
         return 1;
      }))).then(((RequiredArgumentBuilder)RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word()).suggests((ctx, builder) -> {
         this.proxyServer.getAllServers().forEach(server -> builder.suggest(server.getServerInfo().getName()));
         return builder.buildFuture();
      }).executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "server"), 20);
         return 1;
      })).then(RequiredArgumentBuilder.<CommandSource, Integer>argument("limit", IntegerArgumentType.integer(1, 200)).executes(ctx -> {
         this.executeHistory((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "server"), IntegerArgumentType.getInteger(ctx, "limit"));
         return 1;
      }))));
      root.executes(ctx -> {
         ((CommandSource)ctx.getSource()).sendMessage(ServerTextUtil.component("&eUsage: /serverlifecycle <list|show|set|history>"));
         return 1;
      });
      LiteralCommandNode<CommandSource> node = root.build();
      return new BrigadierCommand(node);
   }

   private void executeList(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         this.routingService
            .snapshotStatuses()
            .forEach(
               status -> {
                  String orchestratorSource = "-";
                  long generation = 0L;
                  if (this.serverRegistryRepository != null) {
                     Optional<ServerRegistryRepository.ServerRegistrySnapshot> snapshot = this.serverRegistryRepository.snapshotFor(status.serverId());
                     if (snapshot.isPresent()) {
                        orchestratorSource = snapshot.get().orchestratorSource() == null ? "-" : snapshot.get().orchestratorSource();
                        generation = snapshot.get().orchestrationGeneration();
                     }
                  }

                  source.sendMessage(
                     ServerTextUtil.component(status.serverId()
                           + " | lifecycle="
                           + status.lifecycleState()
                           + " | joinable="
                           + status.joinable()
                           + " | stale="
                           + status.stale()
                           + " | source="
                           + orchestratorSource
                           + " | gen="
                           + generation
                           + " | load="
                           + String.format(Locale.ROOT, "%.2f", status.loadRatio()),
                        NamedTextColor.GRAY
                     )
                  );
               }
            );
      }
   }

   private void executeShow(CommandSource source, String serverId) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else {
         Optional<ServerRoutingService.ServerRouteStatus> routeStatus = this.routingService.describeServer(serverId);
         if (routeStatus.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&c" + "Unknown server: " + serverId));
         } else {
            ServerRoutingService.ServerRouteStatus status = routeStatus.get();
            source.sendMessage(
               ServerTextUtil.component(status.serverId()
                     + " | lifecycle="
                     + status.lifecycleState()
                     + " | joinable="
                     + status.joinable()
                     + " | stale="
                     + status.stale()
                     + " | registered="
                     + status.registered()
                     + " | online="
                     + status.onlinePlayers()
                     + "/"
                     + status.maxPlayers(),
                  NamedTextColor.AQUA
               )
            );
            if (this.serverRegistryRepository != null) {
               this.serverRegistryRepository
                  .snapshotFor(serverId)
                  .ifPresent(
                     snapshot -> source.sendMessage(
                           ServerTextUtil.component("DB: lifecycle="
                                 + snapshot.lifecycleState()
                                 + " | desired="
                                 + snapshot.desiredLifecycleState()
                                 + " | reason="
                                 + snapshot.lifecycleReason()
                                 + " | source="
                                 + snapshot.orchestratorSource()
                                 + " | gen="
                                 + snapshot.orchestrationGeneration(),
                              NamedTextColor.GRAY
                           )
                        )
                  );
            }
         }
      }
   }

   private void executeSet(CommandSource source, String serverId, String stateRaw, String reason, long generation) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else if (this.serverRegistryRepository == null) {
         source.sendMessage(ServerTextUtil.component("&cLifecycle persistence unavailable (PostgreSQL disabled)."));
      } else {
         ServerLifecycleState targetState;
         try {
            targetState = ServerLifecycleState.valueOf(stateRaw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var13) {
            source.sendMessage(ServerTextUtil.component("&cInvalid lifecycle state."));
            return;
         }

         long currentGeneration = this.serverRegistryRepository
            .snapshotFor(serverId)
            .map(ServerRegistryRepository.ServerRegistrySnapshot::orchestrationGeneration)
            .orElse(0L);
         long appliedGeneration = Math.max(Math.max(0L, generation), currentGeneration + 1L);
         boolean ok = this.serverRegistryRepository.updateLifecycle(serverId, targetState, targetState, reason, this.actorName(source), appliedGeneration);
         if (ok) {
            source.sendMessage(ServerTextUtil.component("&a" + "Updated " + serverId + " lifecycle -> " + targetState + "."));
         } else {
            source.sendMessage(ServerTextUtil.component("&c" + "Failed to update lifecycle for " + serverId + "."));
         }
      }
   }

   private void executeHistory(CommandSource source, String serverId, int limit) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&cNo permission."));
      } else if (this.serverRegistryRepository == null) {
         source.sendMessage(ServerTextUtil.component("&cLifecycle persistence unavailable (PostgreSQL disabled)."));
      } else {
         List<ServerRegistryRepository.ServerLifecycleAuditEntry> rows = serverId != null && !serverId.isBlank()
            ? this.serverRegistryRepository.recentLifecycleAuditForServer(serverId, limit)
            : this.serverRegistryRepository.recentLifecycleAudit(limit);
         if (rows.isEmpty()) {
            source.sendMessage(ServerTextUtil.component("&eNo lifecycle audit events found."));
         } else {
            String scope = serverId != null && !serverId.isBlank() ? serverId : "all servers";
            source.sendMessage(ServerTextUtil.component("&b" + "Lifecycle audit history (" + scope + "):"));

            for (ServerRegistryRepository.ServerLifecycleAuditEntry row : rows) {
               source.sendMessage(
                  ServerTextUtil.component("#"
                        + row.auditId()
                        + " | "
                        + row.serverId()
                        + " | "
                        + (row.previousState() == null ? "-" : row.previousState())
                        + " -> "
                        + row.newState()
                        + " | desired="
                        + (row.desiredState() == null ? "-" : row.desiredState())
                        + " | src="
                        + (row.source() == null ? "-" : row.source())
                        + " | gen="
                        + row.generation()
                        + " | at="
                        + row.changedAt(),
                     NamedTextColor.GRAY
                  )
               );
            }
         }
      }
   }

   private String actorName(CommandSource source) {
      return source instanceof Player player ? player.getUsername() : "console";
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
