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
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.registry.PrivateMessageRegistry;

public final class ProxyReplyCommand {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyServer proxyServer;
   private final PrivateMessageRegistry messageRegistry;
   private final ProxyMessageCommand messageCommand;

   public ProxyReplyCommand(ProxyServer proxyServer, PrivateMessageRegistry messageRegistry, ProxyMessageCommand messageCommand) {
      this.proxyServer = proxyServer;
      this.messageRegistry = messageRegistry;
      this.messageCommand = messageCommand;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("reply")
               .then(RequiredArgumentBuilder.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "message"));
                  return 1;
               })))
            .executes(ctx -> {
               ((CommandSource)ctx.getSource()).sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Usage: /reply <message><reset>"));
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source, String message) {
      if (source instanceof Player sender) {
         UUID targetId = this.messageRegistry.getReplyTarget(sender.getUniqueId());
         if (targetId == null) {
            source.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>No conversation to reply to. Use <reset><#FFD700>/msg <player><reset><#888888> first.<reset>"));
         } else {
            Optional<Player> targetOpt = this.proxyServer.getPlayer(targetId);
            if (targetOpt.isEmpty()) {
               source.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>That player is no longer online.<reset>"));
            } else {
               this.messageCommand.deliverMessage(sender, targetOpt.get(), message);
            }
         }
      } else {
         source.sendMessage(ServerTextUtil.miniMessageComponent("<#888888>Only players can use /reply.<reset>"));
      }
   }
}
