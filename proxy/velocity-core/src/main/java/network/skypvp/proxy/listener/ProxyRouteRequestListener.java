package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import network.skypvp.proxy.service.BreachPlayMatchmakingService;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.ProxyDestinationRouter;
import org.slf4j.Logger;

public final class ProxyRouteRequestListener {
   private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("skypvp:route");
   private final ProxyServer proxyServer;
   private final ProxyDestinationRouter destinationRouter;
   private final PartyService partyService;
   private final PartyMemberMover partyMemberMover;
   private final BreachPlayMatchmakingService breachPlayMatchmaking;
   private final Logger logger;

   public ProxyRouteRequestListener(
      ProxyServer proxyServer,
      ProxyDestinationRouter destinationRouter,
      PartyService partyService,
      PartyMemberMover partyMemberMover,
      BreachPlayMatchmakingService breachPlayMatchmaking,
      Logger logger
   ) {
      this.proxyServer = proxyServer;
      this.destinationRouter = destinationRouter;
      this.partyService = partyService;
      this.partyMemberMover = partyMemberMover;
      this.breachPlayMatchmaking = breachPlayMatchmaking;
      this.logger = logger;
   }

   @Subscribe
   public void onPluginMessage(PluginMessageEvent event) {
      if (CHANNEL.equals(event.getIdentifier())) {
         if (event.getTarget() instanceof Player player) {
            event.setResult(ForwardResult.handled());
            ProxyRouteRequestListener.RouteRequest request = this.decode(event.getData()).orElse(null);
            if (request == null) {
               player.sendMessage(ServerTextUtil.miniMessageComponent("<#FF5555>[!] <reset><#888888>Invalid route request.<reset>"));
            } else if ("PARTY_GATHER".equalsIgnoreCase(request.action())) {
               this.gatherPartyToCurrentServer(player, request.roster());
            } else if ("BREACH_PLAY".equalsIgnoreCase(request.action())) {
               this.handleBreachPlay(player, request.destination(), request.deployableMembers(), request.partyId());
            } else if ("CONNECT".equalsIgnoreCase(request.action())) {
               this.destinationRouter.connectExact(player, request.destination());
            } else if ("PROXY_COMMAND".equalsIgnoreCase(request.action())) {
               this.dispatchProxyPlayerCommand(player, request.destination());
            } else if ("PROXY_CONSOLE_COMMAND".equalsIgnoreCase(request.action())) {
               this.dispatchProxyConsoleCommand(request.destination());
            } else {
               this.destinationRouter.route(player, request.destination());
            }
         }
      }
   }

   /**
    * Cross-pod matchmaking for {@code /breach play}: reserve a slot in the network breach pool and route the squad.
    * When no joinable instance exists, the current extraction backend is asked to provision a fresh raid locally.
    */
   private void handleBreachPlay(Player player, String mapId, List<UUID> deployableMembers, UUID partyId) {
      if (this.breachPlayMatchmaking == null) {
         return;
      }
      if (this.breachPlayMatchmaking.matchmakeFromPlayCommand(player, mapId, deployableMembers, partyId)) {
         return;
      }
      this.breachPlayMatchmaking.requestLocalProvision(player, mapId);
   }

   /**
    * Pulls the requesting player's breach squad onto the server the player is currently on. Fired by an extraction
    * backend when a party leader starts a breach, so members scattered across the network are co-located on the same
    * extraction server; the backend then holds instance slots for them until they arrive. When {@code roster} is
    * non-empty only those members (plus the leader) are moved; an empty roster co-locates the whole online party.
    */
   private void gatherPartyToCurrentServer(Player leader, Set<UUID> roster) {
      if (this.partyService == null || this.partyMemberMover == null) {
         return;
      }
      Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(leader.getUniqueId());
      if (partyOpt.isEmpty()) {
         return;
      }
      PartyService.PartyState party = partyOpt.get();
      // Only the leader may drag the party into a breach.
      if (!party.leaderId().equals(leader.getUniqueId())) {
         return;
      }
      Optional<RegisteredServer> currentServer = leader.getCurrentServer().map(connection -> connection.getServer());
      if (currentServer.isEmpty()) {
         return;
      }
      List<UUID> onlineMembers = this.partyService.onlineMembers(this.proxyServer, party.partyId());
      if (roster != null && !roster.isEmpty()) {
         List<UUID> squad = new ArrayList<>();
         for (UUID memberId : onlineMembers) {
            if (memberId.equals(leader.getUniqueId()) || roster.contains(memberId)) {
               squad.add(memberId);
            }
         }
         onlineMembers = squad;
      }
      if (onlineMembers.size() <= 1) {
         return;
      }
      String message = "<green>Party breach: regrouping on <white>{server}<green>.";
      this.partyMemberMover.moveMembers(
         party.partyId(),
         leader.getUniqueId(),
         currentServer.get(),
         onlineMembers,
         message,
         "<yellow>Party breach queued — not enough room to move everyone yet."
      );
   }

   private void dispatchProxyPlayerCommand(Player player, String rawCommand) {
      String command = normalizeCommand(rawCommand);
      if (command.isBlank()) {
         return;
      }
      this.proxyServer.getCommandManager().executeAsync(player, command).exceptionally(error -> {
         this.logger.warn("Failed to run proxy command for {}: {}", player.getUsername(), error.getMessage());
         return null;
      });
   }

   private void dispatchProxyConsoleCommand(String rawCommand) {
      String command = normalizeCommand(rawCommand);
      if (command.isBlank()) {
         return;
      }
      this.proxyServer.getCommandManager().executeAsync(this.proxyServer.getConsoleCommandSource(), command).exceptionally(error -> {
         this.logger.warn("Failed to run proxy console command: {}", error.getMessage());
         return null;
      });
   }

   private static String normalizeCommand(String rawCommand) {
      if (rawCommand == null) {
         return "";
      }
      String trimmed = rawCommand.trim();
      return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
   }

   private Optional<ProxyRouteRequestListener.RouteRequest> decode(byte[] data) {
      try {
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            String action = input.readUTF();
            String destination = input.readUTF();
            Set<UUID> roster = new HashSet<>();
            List<UUID> deployableMembers = List.of();
            UUID partyId = null;
            if (input.available() > 0) {
               if ("BREACH_PLAY".equalsIgnoreCase(action)) {
                  int count = input.readInt();
                  deployableMembers = new ArrayList<>(Math.max(0, count));
                  for (int index = 0; index < count; index++) {
                     try {
                        deployableMembers.add(UUID.fromString(input.readUTF()));
                     } catch (IllegalArgumentException ignored) {
                        // skip malformed id
                     }
                  }
                  if (input.available() > 0) {
                     String partyIdRaw = input.readUTF();
                     if (partyIdRaw != null && !partyIdRaw.isBlank()) {
                        try {
                           partyId = UUID.fromString(partyIdRaw);
                        } catch (IllegalArgumentException ignored) {
                           // ignore malformed party id
                        }
                     }
                  }
               } else {
                  int count = input.readInt();
                  for (int index = 0; index < count; index++) {
                     try {
                        roster.add(UUID.fromString(input.readUTF()));
                     } catch (IllegalArgumentException ignored) {
                        // skip malformed id
                     }
                  }
               }
            }
            return Optional.of(new ProxyRouteRequestListener.RouteRequest(action, destination, roster, deployableMembers, partyId));
         }
      } catch (IOException exception) {
         this.logger.warn("Failed to decode proxy route request: {}", exception.getMessage());
         return Optional.empty();
      }
   }

   private static record RouteRequest(
         String action,
         String destination,
         Set<UUID> roster,
         List<UUID> deployableMembers,
         UUID partyId
   ) {
   }
}
