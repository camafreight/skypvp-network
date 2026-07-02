package network.skypvp.proxy.service;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class PartyQueueService {
   private final ProxyServer proxyServer;
   private final ServerRoutingService routingService;
   private final PartyTransferGate transferGate;
   private final Logger logger;
   private final Map<String, Deque<PartyQueueService.GroupQueueEntry>> groupQueues = new HashMap<>();
   private final Set<UUID> queuedMembers = new HashSet<>();
   private long totalQueuedGroups;
   private long totalMovedGroups;
   private long blockedNoTarget;
   private long blockedCapacity;
   private long droppedOfflineGroups;
   private long lastDrainEpochMillis;

   public PartyQueueService(ProxyServer proxyServer, ServerRoutingService routingService, PartyTransferGate transferGate, Logger logger) {
      this.proxyServer = proxyServer;
      this.routingService = routingService;
      this.transferGate = transferGate;
      this.logger = logger;
   }

   public synchronized PartyQueueService.QueueGroupResult enqueueBreach(UUID partyId, UUID leaderId, List<UUID> members) {
      return this.enqueue("breach", partyId, leaderId, members);
   }

   public synchronized PartyQueueService.QueueGroupResult enqueue(String queueKey, UUID partyId, UUID leaderId, List<UUID> members) {
      if (queueKey != null && !queueKey.isBlank() && partyId != null && leaderId != null && members != null && !members.isEmpty()) {
         for (UUID member : members) {
            if (this.queuedMembers.contains(member)) {
               return PartyQueueService.QueueGroupResult.queuedAlready();
            }
         }

         Deque<PartyQueueService.GroupQueueEntry> queue = this.groupQueues.computeIfAbsent(queueKey, ignored -> new ArrayDeque<>());
         PartyQueueService.GroupQueueEntry entry = new PartyQueueService.GroupQueueEntry(
            queueKey, partyId, leaderId, List.copyOf(members), System.currentTimeMillis()
         );
         queue.addLast(entry);
         this.queuedMembers.addAll(members);
         this.totalQueuedGroups++;
         int position = queue.size();

         for (UUID memberx : members) {
            this.proxyServer
               .getPlayer(memberx)
               .ifPresent(
                  player -> player.sendMessage(
                        ServerTextUtil.component("&e" + "Your party was queued for " + queueKey + " as a group. Position: " + position + ".")
                     )
               );
         }

         return PartyQueueService.QueueGroupResult.queued(position);
      } else {
         return PartyQueueService.QueueGroupResult.invalid();
      }
   }

   public synchronized boolean removeByMember(UUID memberId) {
      if (memberId != null && this.queuedMembers.contains(memberId)) {
         for (Entry<String, Deque<PartyQueueService.GroupQueueEntry>> entry : this.groupQueues.entrySet()) {
            Deque<PartyQueueService.GroupQueueEntry> queue = entry.getValue();
            PartyQueueService.GroupQueueEntry target = null;

            for (PartyQueueService.GroupQueueEntry candidate : queue) {
               if (candidate.members().contains(memberId)) {
                  target = candidate;
                  break;
               }
            }

            if (target != null) {
               queue.remove(target);
               this.queuedMembers.removeAll(target.members());
               if (queue.isEmpty()) {
                  this.groupQueues.remove(entry.getKey());
               }

               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public synchronized int drainAll() {
      int movedGroups = 0;
      this.lastDrainEpochMillis = System.currentTimeMillis();

      for (String queueKey : new ArrayList<>(this.groupQueues.keySet())) {
         Deque<PartyQueueService.GroupQueueEntry> queue = this.groupQueues.get(queueKey);
         if (queue != null && !queue.isEmpty()) {
            PartyQueueService.GroupQueueEntry head = queue.peekFirst();
            if (head != null) {
               List<Player> online = new ArrayList<>();
               boolean allMembersOnline = true;

               for (UUID memberId : head.members()) {
                  Optional<Player> member = this.proxyServer.getPlayer(memberId);
                  if (member.isPresent()) {
                     online.add(member.get());
                  } else {
                     allMembersOnline = false;
                  }
               }

               if (!allMembersOnline) {
                  queue.pollFirst();
                  this.queuedMembers.removeAll(head.members());
                  this.droppedOfflineGroups++;

                  for (Player member : online) {
                     member.sendMessage(ServerTextUtil.component("&eParty queue cancelled because one or more queued members went offline."));
                  }

                  if (queue.isEmpty()) {
                     this.groupQueues.remove(queueKey);
                  }
               } else {
                  Optional<RegisteredServer> targetOpt = this.routingService.selectBestTargetForQueue(queueKey, Set.of());
                  if (targetOpt.isEmpty()) {
                     this.blockedNoTarget++;
                  } else {
                     RegisteredServer target = targetOpt.get();
                     int availableSlots = this.routingService.availableSlotsForServer(target.getServerInfo().getName());
                     if (availableSlots < online.size()) {
                        this.blockedCapacity++;
                     } else {
                        queue.pollFirst();
                        this.queuedMembers.removeAll(head.members());

                        for (Player member : online) {
                           String targetServerId = target.getServerInfo().getName();
                           if (this.transferGate != null) {
                              this.transferGate.authorize(member.getUniqueId(), targetServerId);
                           }
                           member.createConnectionRequest(target).fireAndForget();
                           member.sendMessage(
                              ServerTextUtil.component("&a" + "Party queue ready: sending your group to " + target.getServerInfo().getName() + ".")
                           );
                        }

                        movedGroups++;
                        this.totalMovedGroups++;
                        this.logger
                           .info(
                              "Drained party queue '{}' -> '{}' for party {} ({} online members)",
                              queueKey,
                              target.getServerInfo().getName(),
                              head.partyId(),
                              online.size()
                           );
                        if (queue.isEmpty()) {
                           this.groupQueues.remove(queueKey);
                        }
                     }
                  }
               }
            }
         }
      }

      return movedGroups;
   }

   public synchronized boolean isQueued(UUID memberId) {
      return this.queuedMembers.contains(memberId);
   }

   public synchronized Map<String, Integer> queueSizes() {
      Map<String, Integer> sizes = new HashMap<>();

      for (Entry<String, Deque<PartyQueueService.GroupQueueEntry>> entry : this.groupQueues.entrySet()) {
         sizes.put(entry.getKey(), entry.getValue().size());
      }

      return sizes;
   }

   public synchronized int queuedMemberCount() {
      return this.queuedMembers.size();
   }

   public synchronized PartyQueueService.PartyQueueMetrics metricsSnapshot() {
      int activeGroups = 0;

      for (Deque<PartyQueueService.GroupQueueEntry> queue : this.groupQueues.values()) {
         activeGroups += queue.size();
      }

      return new PartyQueueService.PartyQueueMetrics(
         activeGroups,
         this.queuedMembers.size(),
         this.totalQueuedGroups,
         this.totalMovedGroups,
         this.blockedNoTarget,
         this.blockedCapacity,
         this.droppedOfflineGroups,
         this.lastDrainEpochMillis
      );
   }

   public static record GroupQueueEntry(String queueKey, UUID partyId, UUID leaderId, List<UUID> members, long queuedAtEpochMillis) {
   }

   public static record PartyQueueMetrics(
      int activeGroups,
      int queuedMembers,
      long totalQueuedGroups,
      long totalMovedGroups,
      long blockedNoTarget,
      long blockedCapacity,
      long droppedOfflineGroups,
      long lastDrainEpochMillis
   ) {
   }

   public static record QueueGroupResult(boolean queued, boolean alreadyQueued, boolean valid, int position) {
      static PartyQueueService.QueueGroupResult queued(int position) {
         return new PartyQueueService.QueueGroupResult(true, false, true, position);
      }

      static PartyQueueService.QueueGroupResult queuedAlready() {
         return new PartyQueueService.QueueGroupResult(false, true, true, -1);
      }

      static PartyQueueService.QueueGroupResult invalid() {
         return new PartyQueueService.QueueGroupResult(false, false, false, -1);
      }
   }
}
