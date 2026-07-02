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
import java.util.Optional;
import network.skypvp.proxy.service.ProxyPrivateMessageService;

public final class ProxyMessageCommand {
   private final ProxyServer proxyServer;
   private final ProxyPrivateMessageService privateMessageService;

   public ProxyMessageCommand(ProxyServer proxyServer, ProxyPrivateMessageService privateMessageService) {
      this.proxyServer = proxyServer;
      this.privateMessageService = privateMessageService;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("msg")
               .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
                  this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                  return builder.buildFuture();
               }).then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "message"));
                  return 1;
               }))))
            .executes(ctx -> {
               ((CommandSource)ctx.getSource()).sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /msg <player> <message><reset>"));
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   void deliverMessage(CommandSource from, Player target, String message) {
      this.privateMessageService.deliver(from, target, message);
   }

   private void execute(CommandSource source, String targetName, String message) {
      Optional<Player> targetOpt = this.proxyServer.getPlayer(targetName);
      if (targetOpt.isEmpty()) {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Player '" + targetName + "' is not online.<reset>"));
      } else {
         Player target = targetOpt.get();
         if (source instanceof Player sender && sender.getUniqueId().equals(target.getUniqueId())) {
            source.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>You cannot message yourself.<reset>"));
            return;
         }

         this.deliverMessage(source, target, message);
      }
   }
}
