package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.proxy.repository.PartyRepository;
import network.skypvp.proxy.repository.PlayerSocialSettingsRepository;

public final class PartyService {
   private final PartyRepository repository;
   private final PlayerSocialSettingsRepository socialSettingsRepository;
   private final Map<UUID, PartyService.PartyState> partiesById = new ConcurrentHashMap<>();
   private final Map<UUID, UUID> partyByMember = new ConcurrentHashMap<>();
   private final Map<UUID, Map<UUID, PartyService.InviteState>> invitesByTarget = new ConcurrentHashMap<>();

   public PartyService(PartyRepository repository) {
      this(repository, null);
   }

   public PartyService(PartyRepository repository, PlayerSocialSettingsRepository socialSettingsRepository) {
      this.repository = repository;
      this.socialSettingsRepository = socialSettingsRepository;
      this.hydrateFromRepository();
   }

   public PartyService() {
      this(null, null);
   }

   public synchronized Optional<PartyService.PartyState> partyForMember(UUID memberId) {
      UUID partyId = this.partyByMember.get(memberId);
      if (partyId == null) {
         return Optional.empty();
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party == null) {
            this.partyByMember.remove(memberId);
            return Optional.empty();
         } else {
            return Optional.of(party.copy());
         }
      }
   }

   public synchronized PartyService.PartyActionResult create(UUID leaderId) {
      if (leaderId == null) {
         return PartyService.PartyActionResult.invalid("Invalid player.");
      } else if (this.partyByMember.containsKey(leaderId)) {
         return PartyService.PartyActionResult.failed("You are already in a party.");
      } else {
         UUID partyId = UUID.randomUUID();
         PartyService.PartyState state = new PartyService.PartyState(partyId, leaderId, true, new HashSet<>(Set.of(leaderId)));
         this.partiesById.put(partyId, state);
         this.partyByMember.put(leaderId, partyId);
         this.persistParty(state);
         return PartyService.PartyActionResult.success("Party created.", state.copy());
      }
   }

   public synchronized PartyService.PartyActionResult invite(UUID inviterId, UUID targetId, Duration ttl) {
      if (inviterId != null && targetId != null && !inviterId.equals(targetId)) {
         UUID partyId = this.partyByMember.get(inviterId);
         if (partyId == null) {
            PartyService.PartyActionResult created = this.create(inviterId);
            if (!created.success()) {
               return created;
            }

            partyId = created.party().partyId();
         }

         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party == null) {
            return PartyService.PartyActionResult.failed("Party not found.");
         } else if (!this.memberCanInvite(party, inviterId)) {
            return PartyService.PartyActionResult.failed("You do not have permission to invite players.");
         } else if (this.partyByMember.containsKey(targetId)) {
            return PartyService.PartyActionResult.failed("That player is already in a party.");
         }
         if (this.socialSettingsRepository != null) {
            this.socialSettingsRepository.refresh(targetId);
            if (this.socialSettingsRepository.blocksPartyRequests(targetId)) {
               return PartyService.PartyActionResult.failed("That player is not accepting party invites.");
            }
         }
         Map<UUID, PartyService.InviteState> incoming = this.invitesByTarget.computeIfAbsent(targetId, ignored -> new HashMap<>());
         PartyService.InviteState invite = new PartyService.InviteState(partyId, inviterId, Instant.now().plus(ttl == null ? Duration.ofMinutes(2L) : ttl));
         incoming.put(inviterId, invite);
         this.persistInvite(targetId, invite);
         return PartyService.PartyActionResult.success("Invite sent.", party.copy());
      } else {
         return PartyService.PartyActionResult.invalid("Invalid invite target.");
      }
   }

   public synchronized PartyService.PartyActionResult deny(UUID targetId, UUID inviterId) {
      if (targetId == null) {
         return PartyService.PartyActionResult.invalid("Invalid player.");
      } else {
         Map<UUID, PartyService.InviteState> incoming = this.invitesByTarget.get(targetId);
         if (incoming != null && !incoming.isEmpty()) {
            this.pruneExpired(targetId, incoming);
            PartyService.InviteState invite;
            if (inviterId != null) {
               invite = incoming.get(inviterId);
            } else {
               invite = incoming.values().stream().max(Comparator.comparing(PartyService.InviteState::expiresAt)).orElse(null);
            }

            if (invite != null && !invite.expiresAt().isBefore(Instant.now())) {
               incoming.remove(invite.inviterId());
               if (incoming.isEmpty()) {
                  this.invitesByTarget.remove(targetId);
               }

               if (this.repository != null) {
                  this.repository.clearInvite(targetId, invite.inviterId());
               }

               return PartyService.PartyActionResult.success("Party invite declined.", null);
            }
         }

         return PartyService.PartyActionResult.failed("No valid party invite found.");
      }
   }

   public synchronized PartyService.PartyActionResult accept(UUID targetId, UUID inviterId) {
      if (targetId == null) {
         return PartyService.PartyActionResult.invalid("Invalid player.");
      } else if (this.partyByMember.containsKey(targetId)) {
         return PartyService.PartyActionResult.failed("You are already in a party.");
      } else {
         PartyService.InviteState invite = null;
         Map<UUID, PartyService.InviteState> incoming = this.invitesByTarget.get(targetId);
         if (incoming != null && !incoming.isEmpty()) {
            this.pruneExpired(targetId, incoming);
            if (inviterId != null) {
               invite = incoming.get(inviterId);
            } else {
               invite = incoming.values().stream().max(Comparator.comparing(PartyService.InviteState::expiresAt)).orElse(null);
            }
         }

         if (invite != null && !invite.expiresAt().isBefore(Instant.now())) {
            PartyService.PartyState party = this.partiesById.get(invite.partyId());
            if (party == null) {
               return PartyService.PartyActionResult.failed("That party is no longer available.");
            } else {
               party.members().add(targetId);
               this.partyByMember.put(targetId, party.partyId());
               if (incoming != null) {
                  incoming.clear();
               }

               this.persistParty(party);
               this.clearInvitesForTarget(targetId);
               return PartyService.PartyActionResult.success("Joined party.", party.copy());
            }
         } else {
            return PartyService.PartyActionResult.failed("No valid party invite found.");
         }
      }
   }

   public synchronized PartyService.PartyActionResult leave(UUID memberId) {
      UUID partyId = this.partyByMember.remove(memberId);
      if (partyId == null) {
         return PartyService.PartyActionResult.failed("You are not in a party.");
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party == null) {
            return PartyService.PartyActionResult.failed("You are not in a party.");
         } else {
            party.members().remove(memberId);
            if (party.members().isEmpty()) {
               this.partiesById.remove(partyId);
               this.deleteParty(partyId);
               return PartyService.PartyActionResult.success("Party disbanded.", null);
            } else {
               if (party.leaderId().equals(memberId)) {
                  UUID nextLeader = party.members().stream().sorted().findFirst().orElse(null);
                  if (nextLeader != null) {
                     party.setLeaderId(nextLeader);
                  }
               }

               this.persistParty(party);
               return PartyService.PartyActionResult.success("Left party.", party.copy());
            }
         }
      }
   }

   public synchronized PartyService.PartyActionResult kick(UUID leaderId, UUID targetId) {
      if (leaderId != null && targetId != null) {
         UUID partyId = this.partyByMember.get(leaderId);
         if (partyId == null) {
            return PartyService.PartyActionResult.failed("You are not in a party.");
         } else {
            PartyService.PartyState party = this.partiesById.get(partyId);
            if (party == null || !party.leaderId().equals(leaderId)) {
               return PartyService.PartyActionResult.failed("Only the party leader can kick members.");
            } else if (!party.members().contains(targetId)) {
               return PartyService.PartyActionResult.failed("That player is not in your party.");
            } else if (targetId.equals(leaderId)) {
               return PartyService.PartyActionResult.failed("Use /party leave to leave your own party.");
            } else {
               party.members().remove(targetId);
               this.partyByMember.remove(targetId);
               if (party.members().isEmpty()) {
                  this.partiesById.remove(partyId);
                  this.deleteParty(partyId);
                  return PartyService.PartyActionResult.success("Party disbanded.", null);
               } else {
                  this.persistParty(party);
                  return PartyService.PartyActionResult.success("Member removed from party.", party.copy());
               }
            }
         }
      } else {
         return PartyService.PartyActionResult.invalid("Invalid player.");
      }
   }

   public synchronized PartyService.PartyActionResult transferLeader(UUID leaderId, UUID targetId) {
      UUID partyId = this.partyByMember.get(leaderId);
      if (partyId == null) {
         return PartyService.PartyActionResult.failed("You are not in a party.");
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party == null || !party.leaderId().equals(leaderId)) {
            return PartyService.PartyActionResult.failed("Only the current leader can transfer leadership.");
         } else if (!party.members().contains(targetId)) {
            return PartyService.PartyActionResult.failed("That player is not in your party.");
         } else {
            party.setLeaderId(targetId);
            this.persistParty(party);
            return PartyService.PartyActionResult.success("Transferred party leadership.", party.copy());
         }
      }
   }

   public synchronized void refreshFollowLeader(UUID partyId) {
      if (this.repository != null && partyId != null) {
         this.repository.followLeaderForParty(partyId).ifPresent(followLeader -> {
            PartyService.PartyState party = this.partiesById.get(partyId);
            if (party != null) {
               party.setFollowLeader(followLeader);
            }
         });
      }
   }

   public synchronized PartyService.PartyActionResult validateSummon(UUID actorId) {
      UUID partyId = this.partyByMember.get(actorId);
      if (partyId == null) {
         return PartyService.PartyActionResult.failed("You are not in a party.");
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party == null) {
            return PartyService.PartyActionResult.failed("You are not in a party.");
         } else if (!this.memberCanSummon(party, actorId)) {
            return PartyService.PartyActionResult.failed("Only the party leader or co-leaders can summon members.");
         } else {
            return PartyService.PartyActionResult.success("", party.copy());
         }
      }
   }

   public synchronized PartyService.PartyActionResult setFollow(UUID leaderId, boolean enabled) {
      UUID partyId = this.partyByMember.get(leaderId);
      if (partyId == null) {
         return PartyService.PartyActionResult.failed("You are not in a party.");
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party != null && party.leaderId().equals(leaderId)) {
            party.setFollowLeader(enabled);
            this.persistParty(party);
            return PartyService.PartyActionResult.success("Party follow mode updated.", party.copy());
         } else {
            return PartyService.PartyActionResult.failed("Only the leader can change follow mode.");
         }
      }
   }

   public synchronized List<UUID> onlineMembers(ProxyServer proxyServer, UUID partyId) {
      PartyService.PartyState party = this.partiesById.get(partyId);
      if (party == null) {
         return List.of();
      } else {
         List<UUID> online = new ArrayList<>();

         for (UUID member : party.members()) {
            if (proxyServer.getPlayer(member).isPresent()) {
               online.add(member);
            }
         }

         return online;
      }
   }

   public synchronized int activePartyCount() {
      return this.partiesById.size();
   }

   public synchronized int activePartyMemberCount() {
      return this.partyByMember.size();
   }

   public synchronized int pendingInviteCount() {
      int total = 0;

      for (Map<UUID, PartyService.InviteState> invites : this.invitesByTarget.values()) {
         total += invites.size();
      }

      return total;
   }

   private boolean memberCanInvite(PartyService.PartyState party, UUID memberId) {
      if (party.leaderId().equals(memberId)) {
         return true;
      }
      if (this.repository == null) {
         return false;
      }
      return this.repository.memberRole(party.partyId(), memberId)
         .map(role -> {
            String normalized = role.trim().toUpperCase(Locale.ROOT);
            return "CO_LEADER".equals(normalized) || "TRUSTED".equals(normalized);
         })
         .orElse(false);
   }

   private boolean memberCanSummon(PartyService.PartyState party, UUID memberId) {
      if (party.leaderId().equals(memberId)) {
         return true;
      }
      if (this.repository == null) {
         return false;
      }
      return this.repository.memberRole(party.partyId(), memberId)
         .map(role -> "CO_LEADER".equals(role.trim().toUpperCase(Locale.ROOT)))
         .orElse(false);
   }

   private void pruneExpired(UUID targetId, Map<UUID, PartyService.InviteState> incoming) {
      Instant now = Instant.now();
      incoming.entrySet().removeIf(entry -> {
         boolean expired = entry.getValue().expiresAt().isBefore(now);
         if (expired && this.repository != null && targetId != null) {
            this.repository.clearInvite(targetId, entry.getKey());
         }

         return expired;
      });
   }

   private void hydrateFromRepository() {
      if (this.repository != null) {
         Map<UUID, PartyRepository.PartySnapshot> persisted = this.repository.loadParties();
         persisted.values().forEach(snapshot -> {
            Set<UUID> members = new HashSet<>(snapshot.members());
            if (!members.contains(snapshot.leaderId())) {
               members.add(snapshot.leaderId());
            }

            PartyService.PartyState state = new PartyService.PartyState(snapshot.partyId(), snapshot.leaderId(), snapshot.followLeader(), members);
            this.partiesById.put(snapshot.partyId(), state);
            members.forEach(member -> this.partyByMember.put(member, snapshot.partyId()));
         });
         this.repository
            .loadInvites()
            .forEach(
               invite -> this.invitesByTarget
                     .computeIfAbsent(invite.targetId(), ignored -> new HashMap<>())
                     .put(invite.inviterId(), new PartyService.InviteState(invite.partyId(), invite.inviterId(), invite.expiresAt()))
            );
      }
   }

   private void persistParty(PartyService.PartyState state) {
      if (this.repository != null && state != null) {
         this.repository.upsertParty(state.partyId(), state.leaderId(), state.followLeader());
         this.repository.replaceMembers(state.partyId(), state.members());
      }
   }

   private void deleteParty(UUID partyId) {
      if (this.repository != null && partyId != null) {
         this.repository.deleteParty(partyId);
      }
   }

   private void persistInvite(UUID targetId, PartyService.InviteState invite) {
      if (this.repository != null && targetId != null && invite != null) {
         this.repository.upsertInvite(targetId, invite.inviterId(), invite.partyId(), invite.expiresAt());
      }
   }

   private void clearInvitesForTarget(UUID targetId) {
      if (this.repository != null && targetId != null) {
         this.repository.clearInvitesForTarget(targetId);
      }
   }

   public static record InviteState(UUID partyId, UUID inviterId, Instant expiresAt) {
   }

   public static record PartyActionResult(boolean success, String message, PartyService.PartyState party) {
      static PartyService.PartyActionResult success(String message, PartyService.PartyState party) {
         return new PartyService.PartyActionResult(true, message, party);
      }

      static PartyService.PartyActionResult failed(String message) {
         return new PartyService.PartyActionResult(false, message, null);
      }

      static PartyService.PartyActionResult invalid(String message) {
         return new PartyService.PartyActionResult(false, message, null);
      }
   }

   public static final class PartyState {
      private final UUID partyId;
      private UUID leaderId;
      private boolean followLeader;
      private final Set<UUID> members;

      private PartyState(UUID partyId, UUID leaderId, boolean followLeader, Set<UUID> members) {
         this.partyId = partyId;
         this.leaderId = leaderId;
         this.followLeader = followLeader;
         this.members = members;
      }

      public UUID partyId() {
         return this.partyId;
      }

      public UUID leaderId() {
         return this.leaderId;
      }

      public boolean followLeader() {
         return this.followLeader;
      }

      public Set<UUID> members() {
         return this.members;
      }

      private void setLeaderId(UUID leaderId) {
         this.leaderId = leaderId;
      }

      private void setFollowLeader(boolean followLeader) {
         this.followLeader = followLeader;
      }

      private PartyService.PartyState copy() {
         return new PartyService.PartyState(this.partyId, this.leaderId, this.followLeader, new HashSet<>(this.members));
      }
   }
}
