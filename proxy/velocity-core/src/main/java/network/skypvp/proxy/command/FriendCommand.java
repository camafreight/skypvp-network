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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.integration.PaperMenuMessenger;
import network.skypvp.proxy.repository.FriendRepository;

public final class FriendCommand {
   private final ProxyServer proxyServer;
   private final FriendRepository friendRepository;

   public FriendCommand(ProxyServer proxyServer, FriendRepository friendRepository) {
      this.proxyServer = proxyServer;
      this.friendRepository = friendRepository;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal(
                              "friend"
                           )
                           .then(
                              LiteralArgumentBuilder.literal("add")
                                 .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
                                    this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                                    return builder.buildFuture();
                                 }).executes(context -> {
                                    this.executeAdd((CommandSource)context.getSource(), StringArgumentType.getString(context, "player"));
                                    return 1;
                                 }))
                           ))
                        .then(
                           ((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("accept")
                                 .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
                                    this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                                    return builder.buildFuture();
                                 }).executes(context -> {
                                    this.executeAccept((CommandSource)context.getSource(), StringArgumentType.getString(context, "player"));
                                    return 1;
                                 })))
                              .executes(context -> {
                                 this.executeAcceptLatest((CommandSource)context.getSource());
                                 return 1;
                              })
                        ))
                     .then(
                        LiteralArgumentBuilder.literal("deny")
                           .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
                              this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                              return builder.buildFuture();
                           }).executes(context -> {
                              this.executeDeny((CommandSource)context.getSource(), StringArgumentType.getString(context, "player"));
                              return 1;
                           }))
                     ))
                  .then(
                     LiteralArgumentBuilder.literal("remove")
                        .then(RequiredArgumentBuilder.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
                           this.proxyServer.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                           return builder.buildFuture();
                        }).executes(context -> {
                           this.executeRemove((CommandSource)context.getSource(), StringArgumentType.getString(context, "player"));
                           return 1;
                        }))
                  ))
               .then(LiteralArgumentBuilder.literal("list").executes(context -> {
                  this.executeList((CommandSource)context.getSource());
                  return 1;
               })))
            .executes(context -> {
               this.openFriendsMenu((CommandSource)context.getSource());
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void openFriendsMenu(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         if (!PaperMenuMessenger.openFriendsMenu(player)) {
            player.sendMessage(ServerTextUtil.component("&eCould not open the Friends menu right now. Join a backend server and try again."));
         }
      }
   }

   private void executeAdd(CommandSource source, String targetName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> targetOpt = this.proxyServer.getPlayer(targetName);
         if (targetOpt.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + targetName + "' is not online."));
         } else {
            Player target = targetOpt.get();
            FriendRepository.FriendRequestResult result = this.friendRepository
               .sendRequest(player.getUniqueId(), target.getUniqueId(), Duration.ofMinutes(10L));
            switch (result) {
               case SENT:
                  player.sendMessage(ServerTextUtil.component("&a" + "Friend request sent to " + target.getUsername() + "."));
                  target.sendMessage(
                     ServerTextUtil.component("&b" + player.getUsername() + " sent you a friend request. Use /friend accept " + player.getUsername() + ".")
                  );
                  break;
               case ALREADY_FRIENDS:
                  player.sendMessage(ServerTextUtil.component("&eYou are already friends with that player."));
                  break;
               case REQUESTS_BLOCKED:
                  player.sendMessage(ServerTextUtil.component("&cThat player is not accepting friend requests."));
                  break;
               case INVALID:
                  player.sendMessage(ServerTextUtil.component("&cInvalid friend request."));
                  break;
               case ERROR:
                  player.sendMessage(ServerTextUtil.component("&cCould not send friend request right now."));
            }
         }
      }
   }

   private void executeAccept(CommandSource source, String requesterName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> requesterOpt = this.proxyServer.getPlayer(requesterName);
         if (requesterOpt.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + requesterName + "' is not online."));
         } else {
            Player requester = requesterOpt.get();
            FriendRepository.AcceptResult result = this.friendRepository.acceptRequest(player.getUniqueId(), requester.getUniqueId());
            if (result == FriendRepository.AcceptResult.ACCEPTED) {
               player.sendMessage(ServerTextUtil.component("&a" + "You are now friends with " + requester.getUsername() + "."));
               requester.sendMessage(ServerTextUtil.component("&a" + player.getUsername() + " accepted your friend request."));
            } else if (result == FriendRepository.AcceptResult.NOT_FOUND) {
               player.sendMessage(ServerTextUtil.component("&e" + "No active request found from " + requester.getUsername() + "."));
            } else {
               player.sendMessage(ServerTextUtil.component("&cCould not accept this friend request."));
            }
         }
      }
   }

   private void executeAcceptLatest(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<UUID> requesterId = this.friendRepository.latestIncomingRequestFrom(player.getUniqueId());
         if (requesterId.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&eYou have no pending friend requests."));
         } else {
            FriendRepository.AcceptResult result = this.friendRepository.acceptRequest(player.getUniqueId(), requesterId.get());
            if (result == FriendRepository.AcceptResult.ACCEPTED) {
               String requesterName = this.proxyServer
                  .getPlayer(requesterId.get())
                  .<String>map(Player::getUsername)
                  .orElse(requesterId.get().toString().substring(0, 8));
               player.sendMessage(ServerTextUtil.component("&a" + "You are now friends with " + requesterName + "."));
            } else {
               player.sendMessage(ServerTextUtil.component("&cCould not accept the latest friend request."));
            }
         }
      }
   }

   private void executeDeny(CommandSource source, String requesterName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> requesterOpt = this.proxyServer.getPlayer(requesterName);
         if (requesterOpt.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + requesterName + "' is not online."));
         } else {
            Player requester = requesterOpt.get();
            boolean denied = this.friendRepository.denyRequest(player.getUniqueId(), requester.getUniqueId());
            if (!denied) {
               player.sendMessage(ServerTextUtil.component("&e" + "No pending request from " + requester.getUsername() + "."));
            } else {
               player.sendMessage(ServerTextUtil.component("&a" + "Denied friend request from " + requester.getUsername() + "."));
            }
         }
      }
   }

   private void executeRemove(CommandSource source, String targetName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> targetOpt = this.proxyServer.getPlayer(targetName);
         if (targetOpt.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + targetName + "' is not online."));
         } else {
            Player target = targetOpt.get();
            boolean removed = this.friendRepository.removeFriend(player.getUniqueId(), target.getUniqueId());
            if (!removed) {
               player.sendMessage(ServerTextUtil.component("&e" + "You are not friends with " + target.getUsername() + "."));
            } else {
               player.sendMessage(ServerTextUtil.component("&a" + "Removed " + target.getUsername() + " from your friends list."));
            }
         }
      }
   }

   private void executeList(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         List<UUID> friends = this.friendRepository.listFriends(player.getUniqueId());
         if (friends.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&eYou have no friends yet."));
         } else {
            String list = friends.stream()
               .map(friendId -> this.proxyServer.getPlayer(friendId).map(Player::getUsername).orElse(friendId.toString().substring(0, 8)))
               .sorted(String::compareToIgnoreCase)
               .reduce((left, right) -> left + ", " + right)
               .orElse("-");
            player.sendMessage(ServerTextUtil.component("&b" + "Friends (" + friends.size() + "): " + list));
         }
      }
   }

   private Player requirePlayer(CommandSource source) {
      if (source instanceof Player) {
         return (Player)source;
      } else {
         source.sendMessage(ServerTextUtil.component("&cOnly players can use this command."));
         return null;
      }
   }
}
