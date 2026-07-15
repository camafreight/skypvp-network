package network.skypvp.proxy.service;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PartyMemberMover {
   private final ProxyServer proxyServer;
   private final PartyTransferGate transferGate;
   private final PartyQueueService partyQueueService;
   private final ServerRoutingService routingService;

   public PartyMemberMover(
      ProxyServer proxyServer,
      PartyTransferGate transferGate,
      PartyQueueService partyQueueService,
      ServerRoutingService routingService
   ) {
      this.proxyServer = proxyServer;
      this.transferGate = transferGate;
      this.partyQueueService = partyQueueService;
      this.routingService = routingService;
   }

   public PartyMemberMover.MoveResult moveMembers(
      UUID partyId,
      UUID initiatorId,
      RegisteredServer targetServer,
      Iterable<UUID> memberIds,
      String memberMessage,
      String queuedLeaderMessage
   ) {
      if (partyId == null || initiatorId == null || targetServer == null || memberIds == null) {
         return PartyMemberMover.MoveResult.invalid();
      }

      String targetServerId = targetServer.getServerInfo().getName();
      List<UUID> toMove = new ArrayList<>();

      for (UUID memberId : memberIds) {
         if (memberId == null || memberId.equals(initiatorId)) {
            continue;
         }

         this.proxyServer.getPlayer(memberId).ifPresent(player -> {
            String currentServer = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
            if (!targetServerId.equalsIgnoreCase(currentServer)) {
               toMove.add(memberId);
            }
         });
      }

      if (toMove.isEmpty()) {
         return PartyMemberMover.MoveResult.nothingToMove();
      }

      int partySize = toMove.size() + 1;
      if (!this.routingService.canAdmitPartyToServer(targetServerId, partySize) && this.partyQueueService != null) {
         List<UUID> grouped = new ArrayList<>(toMove);
         grouped.add(initiatorId);
         PartyQueueService.QueueGroupResult queued = this.partyQueueService.enqueue(
            this.routingService.queueKeyForServer(targetServerId), partyId, initiatorId, grouped
         );
         if (queued.valid()) {
            return PartyMemberMover.MoveResult.queued(targetServerId, toMove.size(), queuedLeaderMessage);
         }
      }

      for (UUID memberId : toMove) {
         this.transferGate.authorize(memberId, targetServerId);
         this.proxyServer.getPlayer(memberId).ifPresent(player -> this.connectMember(player, targetServer, memberMessage, targetServerId));
      }

      return PartyMemberMover.MoveResult.moved(targetServerId, toMove.size());
   }

   private void connectMember(Player player, RegisteredServer targetServer, String memberMessage, String targetServerId) {
      player.createConnectionRequest(targetServer).fireAndForget();
      if (memberMessage != null && !memberMessage.isBlank()) {
         player.sendMessage(ServerTextUtil.miniMessageComponent(memberMessage.replace("{server}", targetServerId)));
      }
   }

   public record MoveResult(MoveStatus status, String targetServerId, int memberCount, String leaderMessage) {
      static PartyMemberMover.MoveResult invalid() {
         return new PartyMemberMover.MoveResult(MoveStatus.INVALID, null, 0, null);
      }

      static PartyMemberMover.MoveResult nothingToMove() {
         return new PartyMemberMover.MoveResult(MoveStatus.NOTHING_TO_MOVE, null, 0, null);
      }

      static PartyMemberMover.MoveResult moved(String targetServerId, int memberCount) {
         return new PartyMemberMover.MoveResult(MoveStatus.MOVED, targetServerId, memberCount, null);
      }

      static PartyMemberMover.MoveResult queued(String targetServerId, int memberCount, String leaderMessage) {
         return new PartyMemberMover.MoveResult(MoveStatus.QUEUED, targetServerId, memberCount, leaderMessage);
      }
   }

   public enum MoveStatus {
      INVALID,
      NOTHING_TO_MOVE,
      MOVED,
      QUEUED
   }
}
