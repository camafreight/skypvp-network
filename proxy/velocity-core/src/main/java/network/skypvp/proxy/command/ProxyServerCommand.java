package network.skypvp.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.shared.ServerTextUtil;

public final class ProxyServerCommand {
   private final ProxyServer proxyServer;
   private final NetworkStateRegistry stateRegistry;

   public ProxyServerCommand(ProxyServer proxyServer, NetworkStateRegistry stateRegistry) {
      this.proxyServer = proxyServer;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("server");
      root.then(
         RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
            .suggests((ctx, builder) -> {
               if (this.hasAdminPermission((CommandSource)ctx.getSource())) {
                  this.proxyServer.getAllServers().forEach(server -> builder.suggest(server.getServerInfo().getName()));
               }

               return builder.buildFuture();
            })
            .executes(ctx -> {
               this.execute((CommandSource)ctx.getSource());
               return 1;
            })
      );
      root.executes(ctx -> {
         this.execute((CommandSource)ctx.getSource());
         return 1;
      });
      LiteralCommandNode<CommandSource> node = root.build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source) {
      if (this.hasAdminPermission(source)) {
         source.sendMessage(ServerTextUtil.component("&eDirect /server switching is disabled here. Use /send for staff routing or /play for queue-aware routing."));
      } else {
         source.sendMessage(ServerTextUtil.component("&cDirect server switching is disabled. Use /queue follow <friend> to follow a friend."));
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