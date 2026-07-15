package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.integration.PaperMenuMessenger;
import network.skypvp.proxy.service.BreachPlayMatchmakingService;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;

public final class PartyCommand {
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyMemberMover partyMemberMover;
   private final PartyTransferGate transferGate;
   private final BreachPlayMatchmakingService breachPlayMatchmaking;

   public PartyCommand(ProxyServer proxyServer, PartyService partyService, PartyMemberMover partyMemberMover, PartyTransferGate transferGate) {
      this(proxyServer, partyService, partyMemberMover, transferGate, null);
   }

   public PartyCommand(
      ProxyServer proxyServer,
      PartyService partyService,
      PartyMemberMover partyMemberMover,
      PartyTransferGate transferGate,
      BreachPlayMatchmakingService breachPlayMatchmaking
   ) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.partyMemberMover = partyMemberMover;
      this.transferGate = transferGate;
      this.breachPlayMatchmaking = breachPlayMatchmaking;
   }

   public BrigadierCommand build() {
      LiteralArgumentBuilder<CommandSource> party = LiteralArgumentBuilder.<CommandSource>literal("party");
      party.then(LiteralArgumentBuilder.<CommandSource>literal("create").executes(context -> {
         this.executeCreate(context.getSource());
         return 1;
      }));
      party.then(
         LiteralArgumentBuilder.<CommandSource>literal("invite")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                  .suggests((context, builder) -> {
                     this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                     return builder.buildFuture();
                  })
                  .executes(context -> {
                     this.executeInvite(context.getSource(), StringArgumentType.getString(context, "player"));
                     return 1;
                  })
            )
      );
      LiteralArgumentBuilder<CommandSource> accept = LiteralArgumentBuilder.<CommandSource>literal("accept");
      accept.then(
         RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
            .suggests((context, builder) -> {
               this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
               return builder.buildFuture();
            })
            .executes(context -> {
               this.executeAccept(context.getSource(), StringArgumentType.getString(context, "player"));
               return 1;
            })
      );
      accept.executes(context -> {
         this.executeAccept(context.getSource(), null);
         return 1;
      });
      party.then(accept);
      LiteralArgumentBuilder<CommandSource> deny = LiteralArgumentBuilder.<CommandSource>literal("deny");
      deny.then(
         RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
            .suggests((context, builder) -> {
               this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
               return builder.buildFuture();
            })
            .executes(context -> {
               this.executeDeny(context.getSource(), StringArgumentType.getString(context, "player"));
               return 1;
            })
      );
      deny.executes(context -> {
         this.executeDeny(context.getSource(), null);
         return 1;
      });
      party.then(deny);
      party.then(LiteralArgumentBuilder.<CommandSource>literal("leave").executes(context -> {
         this.executeLeave(context.getSource());
         return 1;
      }));
      party.then(
         LiteralArgumentBuilder.<CommandSource>literal("kick")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                  .suggests((context, builder) -> {
                     if (context.getSource() instanceof Player leader) {
                        for (String name : this.partyService.kickTabNames(leader.getUniqueId(), this.proxyServer)) {
                           builder.suggest(name);
                        }
                     }
                     return builder.buildFuture();
                  })
                  .executes(context -> {
                     this.executeKick(context.getSource(), StringArgumentType.getString(context, "player"));
                     return 1;
                  })
            )
      );
      party.then(
         LiteralArgumentBuilder.<CommandSource>literal("promote")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                  .suggests((context, builder) -> {
                     this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                     return builder.buildFuture();
                  })
                  .executes(context -> {
                     this.executePromote(context.getSource(), StringArgumentType.getString(context, "player"));
                     return 1;
                  })
            )
      );
      party.then(
         LiteralArgumentBuilder.<CommandSource>literal("follow")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("state", StringArgumentType.word())
                  .suggests((context, builder) -> {
                     builder.suggest("on");
                     builder.suggest("off");
                     return builder.buildFuture();
                  })
                  .executes(context -> {
                     this.executeFollow(context.getSource(), StringArgumentType.getString(context, "state"));
                     return 1;
                  })
            )
      );
      party.then(LiteralArgumentBuilder.<CommandSource>literal("open").executes(context -> {
         this.executeSetOpen(context.getSource(), true);
         return 1;
      }));
      party.then(LiteralArgumentBuilder.<CommandSource>literal("close").executes(context -> {
         this.executeSetOpen(context.getSource(), false);
         return 1;
      }));
      party.then(LiteralArgumentBuilder.<CommandSource>literal("find").executes(context -> {
         this.executeFind(context.getSource());
         return 1;
      }));
      party.then(
         LiteralArgumentBuilder.<CommandSource>literal("join")
            .then(
               RequiredArgumentBuilder.<CommandSource, String>argument("party", StringArgumentType.word())
                  .suggests((context, builder) -> {
                     this.proxyServer.getAllPlayers().forEach(player -> builder.suggest(player.getUsername()));
                     return builder.buildFuture();
                  })
                  .executes(context -> {
                     this.executeJoin(context.getSource(), StringArgumentType.getString(context, "party"));
                     return 1;
                  })
            )
      );
      party.then(LiteralArgumentBuilder.<CommandSource>literal("summon").executes(context -> {
         this.executeSummon(context.getSource());
         return 1;
      }));
      party.then(LiteralArgumentBuilder.<CommandSource>literal("list").executes(context -> {
         this.executeList(context.getSource());
         return 1;
      }));
      party.then(LiteralArgumentBuilder.<CommandSource>literal("disband").executes(context -> {
         this.executeDisband(context.getSource());
         return 1;
      }));
      party.executes(context -> {
         this.openPartyMenu(context.getSource());
         return 1;
      });
      return new BrigadierCommand(party.build());
   }

   private void openPartyMenu(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         if (!PaperMenuMessenger.openPartyMenu(player)) {
            player.sendMessage(ServerTextUtil.component("&eCould not open the Party menu right now. Join a backend server and try again."));
         }
      }
   }

   private void executeCreate(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         PartyService.PartyActionResult result = this.partyService.create(player.getUniqueId());
         this.sendResult(player, result);
      }
   }

   private void executeInvite(CommandSource source, String targetName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> targetOpt = this.proxyServer.getPlayer(targetName);
         if (targetOpt.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + targetName + "' is not online."));
         } else {
            Player target = targetOpt.get();
            PartyService.PartyActionResult result = this.partyService.invite(player.getUniqueId(), target.getUniqueId(), Duration.ofMinutes(2L));
            if (result.success()) {
               player.sendMessage(ServerTextUtil.partyInviteSentMessage(target.getUsername()));
               target.sendMessage(ServerTextUtil.partyInviteMessage(player.getUsername()));
               if (this.partyService.partyForMember(target.getUniqueId()).isPresent()) {
                  target.sendMessage(ServerTextUtil.component(
                          "&7Accepting will leave your current party and join theirs."
                  ));
               }
            } else {
               this.sendResult(player, result);
            }
         }
      }
   }

   private void executeAccept(CommandSource source, String inviterName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         UUID inviterId = null;
         if (inviterName != null) {
            Optional<Player> inviter = this.proxyServer.getPlayer(inviterName);
            if (inviter.isEmpty()) {
               player.sendMessage(ServerTextUtil.component("&c" + "Player '" + inviterName + "' is not online."));
               return;
            }

            inviterId = inviter.get().getUniqueId();
         }

         Optional<PartyService.PartyState> previousParty = this.partyService.partyForMember(player.getUniqueId());
         PartyService.PartyState previousSnapshot = previousParty.orElse(null);
         PartyService.PartyActionResult result = this.partyService.accept(player.getUniqueId(), inviterId);
         this.sendResult(player, result);
         if (result.success() && result.party() != null) {
            if (previousSnapshot != null && !previousSnapshot.partyId().equals(result.party().partyId())) {
               this.broadcastPartyExcept(
                     previousSnapshot,
                     player.getUniqueId(),
                     player.getUsername() + " left the party to join another."
               );
            }
            this.broadcastParty(result.party(), player.getUsername() + " joined the party.");
         }
      }
   }

   private void executeDeny(CommandSource source, String inviterName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         UUID inviterId = null;
         if (inviterName != null) {
            Optional<Player> inviter = this.proxyServer.getPlayer(inviterName);
            if (inviter.isEmpty()) {
               player.sendMessage(ServerTextUtil.component("&c" + "Player '" + inviterName + "' is not online."));
               return;
            }

            inviterId = inviter.get().getUniqueId();
         }

         PartyService.PartyActionResult result = this.partyService.deny(player.getUniqueId(), inviterId);
         this.sendResult(player, result);
      }
   }

   private void executeLeave(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         if (this.breachPlayMatchmaking != null) {
            this.breachPlayMatchmaking.cancelPendingDeployForMember(player.getUniqueId(), "party_leave");
         }
         PartyService.PartyActionResult result = this.partyService.leave(player.getUniqueId());
         this.sendResult(player, result);
      }
   }

   private void executeKick(CommandSource source, String targetName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<UUID> targetId = this.partyService.resolveKickTarget(player.getUniqueId(), targetName, this.proxyServer);
         if (targetId.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&cThat player is not in your party."));
         } else {
            if (this.breachPlayMatchmaking != null) {
               this.breachPlayMatchmaking.cancelPendingDeployForMember(targetId.get(), "party_leave");
            }
            PartyService.PartyActionResult result = this.partyService.kick(player.getUniqueId(), targetId.get());
            this.sendResult(player, result);
         }
      }
   }

   private void executePromote(CommandSource source, String targetName) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<Player> target = this.proxyServer.getPlayer(targetName);
         if (target.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&c" + "Player '" + targetName + "' is not online."));
         } else {
            PartyService.PartyActionResult result = this.partyService.transferLeader(player.getUniqueId(), target.get().getUniqueId());
            this.sendResult(player, result);
         }
      }
   }

   private void executeFollow(CommandSource source, String state) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         boolean enabled;
         if ("on".equalsIgnoreCase(state)) {
            enabled = true;
         } else {
            if (!"off".equalsIgnoreCase(state)) {
               player.sendMessage(ServerTextUtil.component("&eUsage: /party follow <on|off>"));
               return;
            }

            enabled = false;
         }

         PartyService.PartyActionResult result = this.partyService.setFollow(player.getUniqueId(), enabled);
         this.sendResult(player, result);
      }
   }

   private void executeSetOpen(CommandSource source, boolean open) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         PartyService.PartyActionResult result = this.partyService.setOpen(player.getUniqueId(), open);
         this.sendResult(player, result);
         if (result.success() && result.party() != null) {
            String memberMessage = open
               ? "Your party is now open — anyone can use /party find to join."
               : "Your party is now closed to /party find.";
            for (UUID memberId : result.party().members()) {
               if (memberId.equals(player.getUniqueId())) {
                  continue;
               }
               this.proxyServer
                  .getPlayer(memberId)
                  .ifPresent(member -> member.sendMessage(ServerTextUtil.component(memberMessage, NamedTextColor.GREEN)));
            }
         }
      }
   }

   private void executeFind(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         PartyService.PartyActionResult result = this.partyService.findOpenParty(this.proxyServer, player.getUniqueId());
         this.sendResult(player, result);
         if (result.success() && result.party() != null) {
            this.announceJoin(player, result.party(), " joined your party via /party find.");
            this.routeSeekerToParty(player, result.party());
         }
      }
   }

   private void executeJoin(CommandSource source, String argument) {
      Player player = this.requirePlayer(source);
      if (player == null) {
         return;
      }
      UUID partyId = this.resolvePartyId(argument);
      if (partyId == null) {
         player.sendMessage(ServerTextUtil.component("&cThat party could not be found. Try /party find."));
         return;
      }
      PartyService.PartyActionResult result = this.partyService.joinOpenParty(player.getUniqueId(), partyId);
      this.sendResult(player, result);
      if (result.success() && result.party() != null) {
         this.announceJoin(player, result.party(), " joined your party.");
         this.routeSeekerToParty(player, result.party());
      }
   }

   /** Resolves a /party join argument that is either a party UUID (from the GUI) or an online member's name. */
   private UUID resolvePartyId(String argument) {
      if (argument == null || argument.isBlank()) {
         return null;
      }
      String trimmed = argument.trim();
      try {
         return UUID.fromString(trimmed);
      } catch (IllegalArgumentException ignored) {
         // Not a UUID — treat it as an online player's name and resolve their party.
      }
      return this.proxyServer.getPlayer(trimmed)
         .flatMap(member -> this.partyService.partyForMember(member.getUniqueId()))
         .map(PartyService.PartyState::partyId)
         .orElse(null);
   }

   private void announceJoin(Player joiner, PartyService.PartyState party, String suffix) {
      for (UUID memberId : party.members()) {
         if (memberId.equals(joiner.getUniqueId())) {
            continue;
         }
         this.proxyServer.getPlayer(memberId).ifPresent(member ->
            member.sendMessage(ServerTextUtil.component("&b" + joiner.getUsername() + suffix)));
      }
   }

   /**
    * Sends a freshly-joined seeker to wherever the party already is so /party find (and the find-a-party browser)
    * actually put you <em>with</em> your new party instead of just adding you to a roster on another server.
    */
   private void routeSeekerToParty(Player seeker, PartyService.PartyState party) {
      Optional<RegisteredServer> anchor = this.resolvePartyAnchorServer(party, seeker.getUniqueId());
      if (anchor.isEmpty()) {
         return;
      }
      RegisteredServer target = anchor.get();
      String targetName = target.getServerInfo().getName();
      String currentName = seeker.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
      if (targetName.equalsIgnoreCase(currentName)) {
         return;
      }
      this.transferGate.authorize(seeker.getUniqueId(), targetName);
      seeker.createConnectionRequest(target).fireAndForget();
      seeker.sendMessage(ServerTextUtil.miniMessageComponent("<green>Taking you to your party on <white>" + targetName + "<green>..."));
   }

   /** Picks the server the party is anchored to: the leader's server if online, otherwise any online member's server. */
   private Optional<RegisteredServer> resolvePartyAnchorServer(PartyService.PartyState party, UUID excludeMemberId) {
      Optional<RegisteredServer> leaderServer = this.proxyServer.getPlayer(party.leaderId())
         .flatMap(Player::getCurrentServer)
         .map(connection -> connection.getServer());
      if (leaderServer.isPresent()) {
         return leaderServer;
      }
      for (UUID memberId : party.members()) {
         if (memberId.equals(excludeMemberId)) {
            continue;
         }
         Optional<RegisteredServer> memberServer = this.proxyServer.getPlayer(memberId)
            .flatMap(Player::getCurrentServer)
            .map(connection -> connection.getServer());
         if (memberServer.isPresent()) {
            return memberServer;
         }
      }
      return Optional.empty();
   }

   private void executeSummon(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         PartyService.PartyActionResult validation = this.partyService.validateSummon(player.getUniqueId());
         if (!validation.success()) {
            this.sendResult(player, validation);
            return;
         }

         Optional<RegisteredServer> targetServer = player.getCurrentServer().map(connection -> connection.getServer());
         if (targetServer.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&cYou must be connected to a server to summon your party."));
            return;
         }

         PartyService.PartyState party = validation.party();
         String targetServerId = targetServer.get().getServerInfo().getName();
         PartyMemberMover.MoveResult result = this.partyMemberMover.moveMembers(
            party.partyId(),
            player.getUniqueId(),
            targetServer.get(),
            party.members(),
            "<#94a3b8>Summoned to <#e2e8f0>{server}<#94a3b8> by your party.",
            "<#fbbf24>Your party was queued for <#e2e8f0>{server}<#fbbf24> because there are not enough slots."
         );
         switch (result.status()) {
            case MOVED:
               player.sendMessage(
                  ServerTextUtil.miniMessageComponent(
                     "<green>Summoned <white>" + result.memberCount() + "</white> party member(s) to <white>" + targetServerId + "<green>."
                  )
               );
               break;
            case QUEUED:
               player.sendMessage(
                  ServerTextUtil.miniMessageComponent(
                     result.leaderMessage() == null
                        ? "<#fbbf24>Your party was queued for <#e2e8f0>" + targetServerId + "<#fbbf24>."
                        : result.leaderMessage().replace("{server}", targetServerId)
                  )
               );
               break;
            case NOTHING_TO_MOVE:
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#fbbf24>All online party members are already on <#e2e8f0>" + targetServerId + "<#fbbf24>."));
               break;
            default:
               player.sendMessage(ServerTextUtil.component("&cCould not summon your party right now."));
         }
      }
   }

   private void executeList(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<PartyService.PartyState> party = this.partyService.partyForMember(player.getUniqueId());
         if (party.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&eYou are not in a party."));
         } else {
            PartyService.PartyState state = party.get();
            List<UUID> members = new ArrayList<>(state.members());
            members.sort(Comparator.comparing(id -> this.proxyServer.getPlayer(id).map(Player::getUsername).orElse(id.toString())));
            String membersLine = members.stream().map(member -> {
               String name = this.proxyServer.getPlayer(member).<String>map(Player::getUsername).orElse(member.toString().substring(0, 8));
               return state.leaderId().equals(member) ? name + " (leader)" : name;
            }).reduce((left, right) -> left + ", " + right).orElse("-");
            player.sendMessage(
               ServerTextUtil.component("Party " + state.partyId().toString().substring(0, 8)
                     + " | " + (state.open() ? "Open" : "Closed")
                     + " | Follow leader: " + state.followLeader()
                     + " | Members: " + membersLine,
                  NamedTextColor.AQUA
               )
            );
         }
      }
   }

   private void executeDisband(CommandSource source) {
      Player player = this.requirePlayer(source);
      if (player != null) {
         Optional<PartyService.PartyState> party = this.partyService.partyForMember(player.getUniqueId());
         if (party.isEmpty()) {
            player.sendMessage(ServerTextUtil.component("&eYou are not in a party."));
         } else {
            PartyService.PartyState state = party.get();
            if (!state.leaderId().equals(player.getUniqueId())) {
               player.sendMessage(ServerTextUtil.component("&cOnly the party leader can disband the party."));
            } else {
               for (UUID member : new ArrayList<>(state.members())) {
                  this.partyService.leave(member);
               }

               player.sendMessage(ServerTextUtil.component("&aParty disbanded."));
            }
         }
      }
   }

   private void broadcastParty(PartyService.PartyState party, String message) {
      this.broadcastPartyExcept(party, null, message);
   }

   private void broadcastPartyExcept(PartyService.PartyState party, UUID excludeId, String message) {
      if (party == null) {
         return;
      }
      for (UUID memberId : party.members()) {
         if (excludeId != null && excludeId.equals(memberId)) {
            continue;
         }
         this.proxyServer.getPlayer(memberId).ifPresent(member -> member.sendMessage(ServerTextUtil.component("&b" + message)));
      }
   }

   private void sendResult(Player player, PartyService.PartyActionResult result) {
      player.sendMessage(ServerTextUtil.component(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
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
