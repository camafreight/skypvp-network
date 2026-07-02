package network.skypvp.proxy.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.ProxyHoldService;
import network.skypvp.proxy.service.ServerRoutingService;
import network.skypvp.shared.ServerTextUtil;

public final class ProxyPlayCommand {
   private final ProxyServer proxyServer;
   private final ServerRoutingService routingService;
   private final ProxyHoldService holdService;
   private final PartyService partyService;
   private final PartyQueueService partyQueueService;
   private final PartyMemberMover partyMemberMover;

   public ProxyPlayCommand(
      ProxyServer proxyServer,
      ServerRoutingService routingService,
      ProxyHoldService holdService,
      PartyService partyService,
      PartyQueueService partyQueueService,
      PartyMemberMover partyMemberMover
   ) {
      this.proxyServer = proxyServer;
      this.routingService = routingService;
      this.holdService = holdService;
      this.partyService = partyService;
      this.partyQueueService = partyQueueService;
      this.partyMemberMover = partyMemberMover;
   }

   public BrigadierCommand build() {
      LiteralCommandNode<CommandSource> node = ((LiteralArgumentBuilder)((LiteralArgumentBuilder)LiteralArgumentBuilder.literal("play")
               .then(RequiredArgumentBuilder.argument("destination", StringArgumentType.word()).suggests((ctx, builder) -> {
                  this.knownDestinations().forEach(builder::suggest);
                  return builder.buildFuture();
               }).executes(ctx -> {
                  this.execute((CommandSource)ctx.getSource(), StringArgumentType.getString(ctx, "destination"));
                  return 1;
               })))
            .executes(ctx -> {
               this.showUsage((CommandSource)ctx.getSource());
               return 1;
            }))
         .build();
      return new BrigadierCommand(node);
   }

   private void execute(CommandSource source, String rawDestination) {
      if (!(source instanceof Player player)) {
         source.sendMessage(ServerTextUtil.component("&cOnly players can use /play."));
      } else {
         String destination = this.normalizeDestination(rawDestination);
         if (destination.isBlank()) {
            this.showUsage(source);
         } else {
            Optional<PartyService.PartyState> party = this.partyService == null ? Optional.empty() : this.partyService.partyForMember(player.getUniqueId());
            if (party.isPresent() && !party.get().leaderId().equals(player.getUniqueId())) {
               source.sendMessage(ServerTextUtil.component("&eOnly the party leader can choose a destination while in party mode."));
            } else {
               Optional<RegisteredServer> target = this.resolveTarget(destination);
               if (target.isEmpty()) {
                  source.sendMessage(this.unavailableMessage(destination));
               } else {
                  RegisteredServer server = target.get();
                  String targetServerId = server.getServerInfo().getName();
                  if (party.isPresent()) {
                     List<UUID> onlineMembers = this.partyService.onlineMembers(this.proxyServer, party.get().partyId());
                     if (onlineMembers.size() > 1) {
                        int availableSlots = this.routingService.availableSlotsForServer(targetServerId);
                        if (availableSlots >= onlineMembers.size()) {
                           this.routePartyMembers(server, destination, party.get(), onlineMembers);
                           return;
                        }

                        if (this.partyQueueService != null) {
                           PartyQueueService.QueueGroupResult queueResult = this.partyQueueService
                              .enqueue(destination, party.get().partyId(), player.getUniqueId(), onlineMembers);
                           if (queueResult.alreadyQueued()) {
                              source.sendMessage(ServerTextUtil.component("&eYour party is already queued as a group."));
                              return;
                           }

                           if (!queueResult.valid()) {
                              source.sendMessage(ServerTextUtil.component("&cCould not queue your party right now."));
                              return;
                           }

                           source.sendMessage(
                              ServerTextUtil.component("Queued your party as a group for " + this.displayLabel(destination) + " (insufficient slots).", NamedTextColor.YELLOW
                              )
                           );
                           return;
                        }
                     }
                  }

                  String currentServerId = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
                  if (targetServerId.equalsIgnoreCase(currentServerId)) {
                     source.sendMessage(ServerTextUtil.component("&e" + "You are already connected to " + targetServerId + "."));
                  } else {
                     boolean reroutedFromHold = this.holdService != null
                        && this.holdService.available()
                        && this.holdService.rerouteHeld(player, destination, server);
                     boolean releasedFromHold = !reroutedFromHold
                        && this.holdService != null
                        && this.holdService.available()
                        && this.holdService.releaseHeld(player, server);
                     if (!reroutedFromHold && !releasedFromHold) {
                        player.createConnectionRequest(server).fireAndForget();
                     }

                     source.sendMessage(
                        ServerTextUtil.createNotice()
                           .includeTitle("Play")
                           .defaultBodyTone(ServerTextUtil.ThemeTone.BRAND_100)
                           .addMiniMessageLine(
                              "<#94a3b8>Routing you to <#e2e8f0>"
                                 + this.displayLabel(destination)
                                 + "</#e2e8f0> via <#e2e8f0>"
                                 + targetServerId
                                 + "</#e2e8f0>.</#94a3b8>"
                           )
                           .buildComponent()
                     );
                  }
               }
            }
         }
      }
   }

   private void routePartyMembers(RegisteredServer target, String destination, PartyService.PartyState party, List<UUID> onlineMembers) {
      String targetServerId = target.getServerInfo().getName();
      String memberMessage = "<green>Party routing: moving to <white>" + this.displayLabel(destination) + "<green>.";
      PartyMemberMover.MoveResult result = this.partyMemberMover.moveMembers(
         party.partyId(),
         party.leaderId(),
         target,
         onlineMembers,
         memberMessage,
         null
      );
      this.proxyServer.getPlayer(party.leaderId()).ifPresent(leader -> {
         String currentServer = leader.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
         if (!targetServerId.equalsIgnoreCase(currentServer)) {
            leader.createConnectionRequest(target).fireAndForget();
            leader.sendMessage(ServerTextUtil.miniMessageComponent(memberMessage));
         } else if (result.status() == PartyMemberMover.MoveStatus.NOTHING_TO_MOVE) {
            leader.sendMessage(ServerTextUtil.component("&eYour party is already on that server."));
         }
      });
   }

   private void showUsage(CommandSource source) {
      source.sendMessage(
         ServerTextUtil.createNotice()
            .includeTitle("Play")
            .defaultBodyTone(ServerTextUtil.ThemeTone.BRAND_100)
            .addLine("Usage: /play <destination>")
            .addLine("Examples: /play lobby, /play extraction")
            .addLine("Known routes: " + String.join(", ", this.knownDestinations()))
            .buildComponent()
      );
   }

   private Optional<RegisteredServer> resolveTarget(String destination) {
      return destination.equalsIgnoreCase(this.routingService.loginQueueKey())
         ? this.routingService.selectBestLoginServer()
         : this.routingService.selectBestTargetForQueue(destination, Set.of());
   }

   private Component unavailableMessage(String destination) {
      return destination.equalsIgnoreCase(this.routingService.loginQueueKey())
         ? ServerTextUtil.createNotice()
            .includeTitle("Play")
            .defaultBodyTone(ServerTextUtil.ThemeTone.ALERT_YELLOW)
            .addMiniMessageLine(
               "<#94a3b8>Lobby is offline right now. Stay connected and the proxy will move you there automatically when it returns.</#94a3b8>"
            )
            .buildComponent()
         : ServerTextUtil.createNotice()
            .includeTitle("Play")
            .defaultBodyTone(ServerTextUtil.ThemeTone.ALERT_YELLOW)
            .addMiniMessageLine("<#94a3b8>" + this.displayLabel(destination) + " is not available right now. Pick another live mode or stay on hold.</#94a3b8>")
            .buildComponent();
   }

   private Set<String> knownDestinations() {
      Set<String> destinations = new LinkedHashSet<>();
      destinations.add("lobby");
      destinations.add("hub");
      this.routingService.snapshotStatuses().forEach(status -> {
         this.addDestination(destinations, status.cluster());
         this.addDestination(destinations, status.role());
         this.addDestination(destinations, status.serverId());
      });
      this.proxyServer.getAllServers().forEach(server -> this.addDestination(destinations, server.getServerInfo().getName()));
      return destinations;
   }

   private void addDestination(Set<String> destinations, String rawValue) {
      String normalized = this.normalizeDestination(rawValue);
      if (!normalized.isBlank()) {
         destinations.add(normalized);
      }
   }

   private String normalizeDestination(String rawDestination) {
      String normalized = rawDestination == null ? "" : rawDestination.trim().toLowerCase(Locale.ROOT);

      return switch (normalized) {
         case "hub", "spawn", "lobby" -> this.routingService.loginQueueKey();
         case "minigames", "minigame" -> "extraction";
         case "survival", "smp" -> "extraction";
         default -> normalized;
      };
   }

   private String displayLabel(String destination) {
      String var2 = this.normalizeDestination(destination);

      return switch (var2) {
         case "lobby" -> "Lobby";
         case "extraction" -> "Extraction";
         case "survival", "smp" -> "Extraction";
         case "minigame" -> "Extraction";
         default -> this.titleCase(destination.replace('-', ' ').replace('_', ' '));
      };
   }

   private String titleCase(String value) {
      if (value != null && !value.isBlank()) {
         String[] parts = value.split("\\s+");
         StringBuilder out = new StringBuilder();

         for (String part : parts) {
            if (!part.isBlank()) {
               if (!out.isEmpty()) {
                  out.append(' ');
               }

               out.append(Character.toUpperCase(part.charAt(0)));
               if (part.length() > 1) {
                  out.append(part.substring(1).toLowerCase(Locale.ROOT));
               }
            }
         }

         return out.isEmpty() ? "Destination" : out.toString();
      } else {
         return "Destination";
      }
   }
}
