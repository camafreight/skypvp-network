package network.skypvp.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.registry.NetworkStateRegistry;

import network.skypvp.shared.ServerTextUtil;

public final class ProxyBroadcastCommand {
   private final ProxyServer proxyServer;
   private final NetworkStateRegistry stateRegistry;

   public ProxyBroadcastCommand(ProxyServer proxyServer, NetworkStateRegistry stateRegistry) {
      this.proxyServer = proxyServer;
      this.stateRegistry = stateRegistry;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("broadcast")
               .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "message"));
                  return 1;
               })))
            .executes(
               ctx -> {
                  ((CommandSource)ctx.getSource())
                     .sendMessage(
                        ServerTextUtil.createNotice()
                           .includeTitle("Broadcast")
                           .defaultBodyTone(ServerTextUtil.ThemeTone.ALERT_YELLOW)
                           .addLine("Usage: /broadcast <message>")
                           .buildComponent()
                     );
                  return 1;
               }
            ))
         .build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source, String rawMessage) {
      if (!this.hasAdminPermission(source)) {
         source.sendMessage(
            ServerTextUtil.createNotice()
               .includeTitle("Broadcast")
               .defaultBodyTone(ServerTextUtil.ThemeTone.ALERT_YELLOW)
               .addLine("No permission.")
               .buildComponent()
         );
      } else {
         Component formatted = ServerTextUtil.createNotice()
            .includeTitle("Network Broadcast")
            .defaultBodyTone(ServerTextUtil.ThemeTone.ALERT_YELLOW)
            .addMiniMessageLine(rawMessage)
            .buildComponent();
         this.proxyServer.getAllPlayers().forEach(player -> player.sendMessage(formatted));
         if (!(source instanceof Player)) {
            source.sendMessage(formatted);
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
