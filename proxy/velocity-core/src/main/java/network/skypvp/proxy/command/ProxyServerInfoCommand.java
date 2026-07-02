package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.proxy.repository.ServerRegistryRepository;
import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.shared.FieldValueFormatter;

public final class ProxyServerInfoCommand {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   // $VF: renamed from: DIV java.lang.String
   private static final String DIV = "<#555555>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<reset>";
   private final ProxyServer proxyServer;
   private final ServerRoutingService routingService;
   private final ServerRegistryRepository serverRegistryRepository;
   private final NetworkStateRegistry stateRegistry;

   public ProxyServerInfoCommand(
      ProxyServer proxyServer,
      ServerRoutingService routingService,
      ServerRegistryRepository serverRegistryRepository,
      NetworkStateRegistry stateRegistry
   ) {
      this.proxyServer = proxyServer;
      this.routingService = routingService;
      this.serverRegistryRepository = serverRegistryRepository;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("scservers")
               .then(RequiredArgumentBuilder.argument("server", StringArgumentType.word()).suggests((ctx, builder) -> {
                  this.proxyServer.getAllServers().forEach(server -> builder.suggest(server.getServerInfo().getName()));
                  return builder.buildFuture();
               }).executes(ctx -> {
                  this.executeSingle((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "server"));
                  return 1;
               })))
            .executes(ctx -> {
               this.executeAll((CommandSource)ctx.getSource());
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void executeAll(CommandSource source) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>No permission.<reset>"));
      } else {
         source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
         source.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Network Server Snapshot</bold><reset>"));
         source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
         this.routingService.snapshotStatuses().forEach(status -> source.sendMessage(this.formatStatusComponent(status)));
         source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
      }
   }

   private void executeSingle(CommandSource source, String serverId) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>No permission.<reset>"));
      } else {
         Optional<ServerRoutingService.ServerRouteStatus> statusOpt = this.routingService.describeServer(serverId);
         if (statusOpt.isEmpty()) {
            source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>Unknown server '<reset><white>" + serverId + "<reset><#888888>'.<reset>"));
         } else {
            ServerRoutingService.ServerRouteStatus status = statusOpt.get();
            source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
            source.sendMessage(ServerTextUtil.miniMessageComponent("<gradient:#FFB300:#FF6F00><bold>  Server: " + serverId + "</bold><reset>"));
            source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
            source.sendMessage(this.formatStatusComponent(status));
            if (this.serverRegistryRepository != null) {
               Optional<ServerRegistryRepository.ServerRegistrySnapshot> snapshot = this.serverRegistryRepository.snapshotFor(serverId);
               if (snapshot.isPresent()) {
                  ServerRegistryRepository.ServerRegistrySnapshot row = snapshot.get();
                  source.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Role", row.role())));
                  source.sendMessage(
                     ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Online", row.onlinePlayers() + "/" + row.maxPlayers()))
                  );
                  source.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Joinable", String.valueOf(row.joinable()))));
                  source.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Maintenance", String.valueOf(row.maintenance()))));
                  source.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Lifecycle", row.lifecycleState().name())));
                  source.sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Desired", row.desiredLifecycleState().name())));
                  source.sendMessage(
                     ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Last Heartbeat", String.valueOf(row.lastHeartbeatAt())))
                  );
               } else {
                  source.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>DB row: none<reset>"));
               }
            }

            source.sendMessage(ServerTextUtil.miniMessageComponent(DIV));
         }
      }
   }

   private Component formatStatusComponent(ServerRoutingService.ServerRouteStatus status) {
      long ageSeconds = status.heartbeatAgeMillis() == Long.MAX_VALUE ? -1L : status.heartbeatAgeMillis() / 1000L;
      return ServerTextUtil.miniMessageComponent(
         "<#FFFFFF>"
            + status.serverId()
            + "<reset> "
            + FieldValueFormatter.fieldValueMiniMessage("Role", status.role())
            + " <#555555>|</#555555> "
            + FieldValueFormatter.fieldValueMiniMessage("Online", status.onlinePlayers() + "/" + status.maxPlayers())
            + " <#555555>|</#555555> "
            + FieldValueFormatter.fieldValueMiniMessage("Load", String.format(Locale.ROOT, "%.0f%%", status.loadRatio() * 100.0))
            + " <#555555>|</#555555> "
            + FieldValueFormatter.fieldValueMiniMessage("Joinable", String.valueOf(status.joinable()))
            + " <#555555>|</#555555> "
            + FieldValueFormatter.fieldValueMiniMessage("Stale", String.valueOf(status.stale()))
            + " <#555555>|</#555555> "
            + FieldValueFormatter.fieldValueMiniMessage("Age", ageSeconds + "s")
      );
   }

   private static String boolColor(boolean positive) {
      return positive ? "#FFD700" : "red";
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
