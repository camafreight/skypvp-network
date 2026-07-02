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
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.shared.FieldValueFormatter;
import org.slf4j.Logger;

public final class ProxySendCommand {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyServer proxyServer;
   private final NetworkStateRegistry stateRegistry;
   private final ServerRoutingService routingService;
   private final Logger logger;

   public ProxySendCommand(
      ProxyServer proxyServer, NetworkStateRegistry stateRegistry, ServerRoutingService routingService, Logger logger
   ) {
      this.proxyServer = proxyServer;
      this.stateRegistry = stateRegistry;
      this.routingService = routingService;
      this.logger = logger;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("send")
               .then(RequiredArgumentBuilder.argument("target", StringArgumentType.word()).suggests((ctx, builder) -> {
                  builder.suggest("all");
                  this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                  return builder.buildFuture();
               }).then(RequiredArgumentBuilder.argument("server", StringArgumentType.word()).suggests((ctx, builder) -> {
                  this.proxyServer.getAllServers().forEach(s -> builder.suggest(s.getServerInfo().getName()));
                  return builder.buildFuture();
               }).executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "target"), StringArgumentType.getString(ctx, "server"));
                  return 1;
               }))))
            .executes(ctx -> {
               ((CommandSource)ctx.getSource()).sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /send <player|all> <server><reset>"));
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source, String target, String serverName) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!]<reset> <#888888>No permission.<reset>"));
      } else {
         Optional<RegisteredServer> serverOpt = this.proxyServer.getServer(serverName);
         if (serverOpt.isEmpty()) {
            source.sendMessage(ServerTextUtil.miniMessageComponent(FieldValueFormatter.fieldValueMiniMessage("Unknown Server", serverName)));
         } else {
            this.routingService
               .describeServer(serverName)
               .filter(status -> !status.isHealthyJoinTarget())
               .ifPresent(
                  status -> source.sendMessage(
                        ServerTextUtil.miniMessageComponent(FieldValueFormatter.fieldValueMiniMessage("Warning", "Target is stale or non-joinable; transfer still attempted"))
                     )
               );
            RegisteredServer server = serverOpt.get();
            if ("all".equalsIgnoreCase(target)) {
               int count = this.proxyServer.getPlayerCount();
               this.proxyServer.getAllPlayers().forEach(p -> p.createConnectionRequest(server).fireAndForget());
               source.sendMessage(
                  ServerTextUtil.miniMessageComponent(
                     FieldValueFormatter.fieldValueMiniMessage("Action", "Sending all players")
                        + " <#555555>|</#555555> "
                        + FieldValueFormatter.fieldValueMiniMessage("Count", String.valueOf(count))
                        + " <#555555>|</#555555> "
                        + FieldValueFormatter.fieldValueMiniMessage("Server", serverName)
                  )
               );
               this.logger.info("Admin sent all players ({}) to '{}'", count, serverName);
            } else {
               Optional<Player> playerOpt = this.proxyServer.getPlayer(target);
               if (playerOpt.isEmpty()) {
                  source.sendMessage(ServerTextUtil.miniMessageComponent(FieldValueFormatter.fieldValueMiniMessage("Player Offline", target)));
                  return;
               }

               Player player = playerOpt.get();
               player.createConnectionRequest(server).fireAndForget();
               player.sendMessage(ServerTextUtil.miniMessageComponent(FieldValueFormatter.fieldValueMiniMessage("Moved To", serverName)));
               source.sendMessage(
                  ServerTextUtil.miniMessageComponent(
                     FieldValueFormatter.fieldValueMiniMessage("Sent Player", player.getUsername())
                        + " <#555555>|</#555555> "
                        + FieldValueFormatter.fieldValueMiniMessage("Server", serverName)
                  )
               );
               this.logger.info("Admin sent '{}' to '{}'", player.getUsername(), serverName);
            }
         }
      }
   }

   private boolean hasAdminPermission(CommandSource source) {
      if (source instanceof Player player) {
         return player.hasPermission("skypvp.admin.rank") || player.hasPermission("skypvp.admin");
      } else {
         return true;
      }
   }
}
