package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.Player;
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
   /**
    * Hard cap on total party size (across the whole network). A party may be larger than a single breach deploy squad
    * (the extraction backend caps how many actually deploy together, and the leader picks who). Enforced on invite,
    * accept, and find/join.
    */
   private static final int MAX_PARTY_SIZE = 12;
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
         PartyService.PartyState state = new PartyService.PartyState(partyId, leaderId, true, false, new HashSet<>(Set.of(leaderId)));
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
         } else if (partyId.equals(this.partyByMember.get(targetId))) {
            return PartyService.PartyActionResult.failed("That player is already in your party.");
         } else if (party.members().size() >= MAX_PARTY_SIZE) {
            return PartyService.PartyActionResult.failed("Your party is full (max " + MAX_PARTY_SIZE + ").");
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
      }

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

      if (invite == null || invite.expiresAt().isBefore(Instant.now())) {
         return PartyService.PartyActionResult.failed("No valid party invite found.");
      }

      PartyService.PartyState party = this.partiesById.get(invite.partyId());
      if (party == null) {
         return PartyService.PartyActionResult.failed("That party is no longer available.");
      }

      UUID currentPartyId = this.partyByMember.get(targetId);
      if (party.partyId().equals(currentPartyId)) {
         if (incoming != null) {
            incoming.clear();
         }
         this.clearInvitesForTarget(targetId);
         return PartyService.PartyActionResult.success("You are already in that party.", party.copy());
      }

      // Capacity must be checked before leaving the current party so a failed join does not eject the player.
      if (party.members().size() >= MAX_PARTY_SIZE) {
         return PartyService.PartyActionResult.failed("That party is full (max " + MAX_PARTY_SIZE + ").");
      }

      boolean switchedParties = false;
      if (currentPartyId != null) {
         PartyService.PartyActionResult left = this.leave(targetId);
         if (!left.success()) {
            return PartyService.PartyActionResult.failed("Could not leave your current party.");
         }
         switchedParties = true;
         party = this.partiesById.get(invite.partyId());
         if (party == null) {
            return PartyService.PartyActionResult.failed("That party is no longer available.");
         }
         if (party.members().size() >= MAX_PARTY_SIZE) {
            return PartyService.PartyActionResult.failed("That party is full (max " + MAX_PARTY_SIZE + ").");
         }
      }

      party.members().add(targetId);
      this.partyByMember.put(targetId, party.partyId());
      if (incoming != null) {
         incoming.clear();
      }

      this.persistParty(party);
      this.clearInvitesForTarget(targetId);
      return PartyService.PartyActionResult.success(
            switchedParties ? "Left your previous party and joined the new one." : "Joined party.",
            party.copy()
      );
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
                  UUID nextLeader = this.chooseSuccessor(party, party.members());
                  if (nextLeader != null) {
                     party.setLeaderId(nextLeader);
                     if (this.repository != null) {
                        this.repository.setMemberRole(party.partyId(), nextLeader, "LEADER");
                     }
                  }
               }

               this.persistParty(party);
               return PartyService.PartyActionResult.success("Left party.", party.copy());
            }
         }
      }
   }

   /**
    * Handles a member disconnect WITHOUT ejecting them from the party. A raider who crashes mid-raid (common in an
    * extraction game mode) stays a member so that on reconnect they are still grouped and covered by friendly-fire
    * protection. Rules:
    * <ul>
    *   <li>If no other member is online, the party is disbanded so fully-offline parties do not linger in the DB.</li>
    *   <li>If the leader disconnects while other members are online, leadership hands off to the best-ranked online
    *       member (so the party is not stuck with an offline leader), but the old leader is kept as a member.</li>
    * </ul>
    */
   public synchronized PartyService.PartyDisconnectResult handleDisconnect(ProxyServer proxyServer, UUID memberId) {
      if (memberId == null) {
         return PartyService.PartyDisconnectResult.none();
      }
      UUID partyId = this.partyByMember.get(memberId);
      if (partyId == null) {
         return PartyService.PartyDisconnectResult.none();
      }
      PartyService.PartyState party = this.partiesById.get(partyId);
      if (party == null) {
         this.partyByMember.remove(memberId);
         return PartyService.PartyDisconnectResult.none();
      }

      List<UUID> otherOnline = new ArrayList<>();
      for (UUID member : party.members()) {
         if (!member.equals(memberId) && proxyServer != null && proxyServer.getPlayer(member).isPresent()) {
            otherOnline.add(member);
         }
      }

      if (otherOnline.isEmpty()) {
         for (UUID member : party.members()) {
            this.partyByMember.remove(member);
         }
         this.partiesById.remove(partyId);
         this.deleteParty(partyId);
         return PartyService.PartyDisconnectResult.disbanded();
      }

      boolean wasLeader = party.leaderId().equals(memberId);
      UUID newLeaderId = null;
      if (wasLeader) {
         newLeaderId = this.chooseSuccessor(party, otherOnline);
         if (newLeaderId != null) {
            party.setLeaderId(newLeaderId);
            if (this.repository != null) {
               this.repository.setMemberRole(party.partyId(), memberId, "CO_LEADER");
               this.repository.setMemberRole(party.partyId(), newLeaderId, "LEADER");
            }
         }
      }
      this.persistParty(party);
      return PartyService.PartyDisconnectResult.kept(wasLeader, newLeaderId);
   }

   /**
    * Resolves a kick target by username, including offline party members (via {@code network_players.last_username}).
    */
   public synchronized Optional<UUID> resolveKickTarget(UUID leaderId, String targetName, ProxyServer proxyServer) {
      if (leaderId == null || targetName == null || targetName.isBlank()) {
         return Optional.empty();
      }
      UUID partyId = this.partyByMember.get(leaderId);
      if (partyId == null) {
         return Optional.empty();
      }
      PartyService.PartyState party = this.partiesById.get(partyId);
      if (party == null || !party.leaderId().equals(leaderId)) {
         return Optional.empty();
      }
      String normalized = targetName.trim();
      if (proxyServer != null) {
         Optional<Player> onlineExact = proxyServer.getPlayer(normalized);
         if (onlineExact.isPresent() && party.members().contains(onlineExact.get().getUniqueId())) {
            return Optional.of(onlineExact.get().getUniqueId());
         }
         for (UUID memberId : party.members()) {
            Optional<Player> onlineMember = proxyServer.getPlayer(memberId);
            if (onlineMember.isPresent() && onlineMember.get().getUsername().equalsIgnoreCase(normalized)) {
               return Optional.of(memberId);
            }
         }
      }
      if (this.repository != null) {
         return this.repository.resolveMemberIdInParty(partyId, normalized);
      }
      return Optional.empty();
   }

   /** Member usernames for tab completion (online names override stored last_username). */
   public synchronized List<String> kickTabNames(UUID leaderId, ProxyServer proxyServer) {
      UUID partyId = this.partyByMember.get(leaderId);
      if (partyId == null) {
         return List.of();
      }
      PartyService.PartyState party = this.partiesById.get(partyId);
      if (party == null || !party.leaderId().equals(leaderId)) {
         return List.of();
      }
      Map<UUID, String> stored = this.repository != null
         ? this.repository.memberUsernamesForParty(partyId)
         : Map.of();
      List<String> names = new ArrayList<>();
      for (UUID memberId : party.members()) {
         if (memberId.equals(leaderId)) {
            continue;
         }
         if (proxyServer != null) {
            Optional<Player> online = proxyServer.getPlayer(memberId);
            if (online.isPresent()) {
               names.add(online.get().getUsername());
               continue;
            }
         }
         String storedName = stored.get(memberId);
         if (storedName != null && !storedName.isBlank()) {
            names.add(storedName);
         }
      }
      names.sort(String.CASE_INSENSITIVE_ORDER);
      return names;
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
            UUID previousLeaderId = party.leaderId();
            party.setLeaderId(targetId);
            this.persistParty(party);
            if (this.repository != null) {
               this.repository.setMemberRole(party.partyId(), previousLeaderId, "CO_LEADER");
               this.repository.setMemberRole(party.partyId(), targetId, "LEADER");
            }
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

   public synchronized PartyService.PartyActionResult setOpen(UUID leaderId, boolean open) {
      UUID partyId = this.partyByMember.get(leaderId);
      if (partyId == null) {
         return PartyService.PartyActionResult.failed("You are not in a party.");
      } else {
         PartyService.PartyState party = this.partiesById.get(partyId);
         if (party != null && party.leaderId().equals(leaderId)) {
            party.setOpen(open);
            this.persistParty(party);
            return PartyService.PartyActionResult.success(
               open
                  ? "Your party is now open — anyone can use /party find to join."
                  : "Your party is now closed to /party find.",
               party.copy()
            );
         } else {
            return PartyService.PartyActionResult.failed("Only the leader can open or close the party.");
         }
      }
   }

   /**
    * Finds an open party for a player looking to join one and adds them to it. To encourage groups filling up, the
    * open party with the most online members that still has room is chosen. Fully-offline parties are skipped so a
    * seeker is never dropped into an empty group.
    *
    * @return the joined party in the result on success, or a failure describing why no party was joined
    */
   public synchronized PartyService.PartyActionResult findOpenParty(ProxyServer proxyServer, UUID seekerId) {
      if (seekerId == null) {
         return PartyService.PartyActionResult.invalid("Invalid player.");
      }
      if (this.partyByMember.containsKey(seekerId)) {
         return PartyService.PartyActionResult.failed("You are already in a party. Leave it first with /party leave.");
      }

      PartyService.PartyState best = null;
      int bestOnline = 0;
      for (PartyService.PartyState party : this.partiesById.values()) {
         if (!party.open() || party.members().size() >= MAX_PARTY_SIZE) {
            continue;
         }
         int online = 0;
         for (UUID member : party.members()) {
            if (proxyServer != null && proxyServer.getPlayer(member).isPresent()) {
               online++;
            }
         }
         if (online > bestOnline) {
            bestOnline = online;
            best = party;
         }
      }

      if (best == null) {
         return PartyService.PartyActionResult.failed("No open parties are looking for members right now.");
      }

      best.members().add(seekerId);
      this.partyByMember.put(seekerId, best.partyId());
      this.invitesByTarget.remove(seekerId);
      this.clearInvitesForTarget(seekerId);
      this.persistParty(best);
      return PartyService.PartyActionResult.success("Joined an open party!", best.copy());
   }

   /**
    * Joins a specific open party by id (used by the "find a party" browser where a seeker picks a party to join). The
    * party must be flagged open and still have room; otherwise the seeker is told to try another.
    *
    * @return the joined party in the result on success, or a failure describing why the join was rejected
    */
   public synchronized PartyService.PartyActionResult joinOpenParty(UUID seekerId, UUID partyId) {
      if (seekerId == null || partyId == null) {
         return PartyService.PartyActionResult.invalid("Invalid request.");
      }
      if (this.partyByMember.containsKey(seekerId)) {
         return PartyService.PartyActionResult.failed("You are already in a party. Leave it first with /party leave.");
      }
      PartyService.PartyState party = this.partiesById.get(partyId);
      if (party == null) {
         return PartyService.PartyActionResult.failed("That party no longer exists.");
      }
      if (!party.open()) {
         return PartyService.PartyActionResult.failed("That party is not open to join.");
      }
      if (party.members().size() >= MAX_PARTY_SIZE) {
         return PartyService.PartyActionResult.failed("That party is full (max " + MAX_PARTY_SIZE + ").");
      }
      party.members().add(seekerId);
      this.partyByMember.put(seekerId, partyId);
      this.invitesByTarget.remove(seekerId);
      this.clearInvitesForTarget(seekerId);
      this.persistParty(party);
      return PartyService.PartyActionResult.success("Joined the party!", party.copy());
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

   /**
    * Picks the best-ranked leadership successor from {@code candidates} (CO_LEADER &gt; TRUSTED &gt; MEMBER, ties
    * broken by lowest UUID for determinism). Reads roles from the DB since the proxy does not cache them.
    */
   private UUID chooseSuccessor(PartyService.PartyState party, java.util.Collection<UUID> candidates) {
      if (candidates == null || candidates.isEmpty()) {
         return null;
      }
      Map<UUID, String> roles = this.repository == null ? Map.of() : this.repository.memberRoles(party.partyId());
      return candidates.stream()
         .min(Comparator.<UUID>comparingInt(id -> roleRank(roles.get(id))).thenComparing(Comparator.naturalOrder()))
         .orElse(null);
   }

   private static int roleRank(String raw) {
      if (raw == null) {
         return 3;
      }
      return switch (raw.trim().toUpperCase(Locale.ROOT)) {
         case "LEADER" -> 0;
         case "CO_LEADER", "CO-LEADER", "COLEADER" -> 1;
         case "TRUSTED" -> 2;
         default -> 3;
      };
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

   /** Drops expired invites and removes empty target buckets so {@link #invitesByTarget} cannot grow without bound. */
   public synchronized void sweepStaleInvites() {
      for (java.util.Iterator<Map.Entry<UUID, Map<UUID, PartyService.InviteState>>> iterator = this.invitesByTarget.entrySet().iterator();
           iterator.hasNext();) {
         Map.Entry<UUID, Map<UUID, PartyService.InviteState>> entry = iterator.next();
         Map<UUID, PartyService.InviteState> incoming = entry.getValue();
         if (incoming == null || incoming.isEmpty()) {
            iterator.remove();
            continue;
         }
         this.pruneExpired(entry.getKey(), incoming);
         if (incoming.isEmpty()) {
            iterator.remove();
         }
      }
   }

   private void hydrateFromRepository() {
      if (this.repository != null) {
         Map<UUID, PartyRepository.PartySnapshot> persisted = this.repository.loadParties();
         persisted.values().forEach(snapshot -> {
            Set<UUID> members = new HashSet<>(snapshot.members());
            if (!members.contains(snapshot.leaderId())) {
               members.add(snapshot.leaderId());
            }

            PartyService.PartyState state = new PartyService.PartyState(snapshot.partyId(), snapshot.leaderId(), snapshot.followLeader(), snapshot.open(), members);
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
         this.repository.upsertParty(state.partyId(), state.leaderId(), state.followLeader(), state.open());
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

   public static record PartyDisconnectResult(PartyService.PartyDisconnectResult.Outcome outcome, boolean wasLeader, UUID newLeaderId) {
      public enum Outcome {
         NONE,
         KEPT,
         DISBANDED
      }

      static PartyService.PartyDisconnectResult none() {
         return new PartyService.PartyDisconnectResult(Outcome.NONE, false, null);
      }

      static PartyService.PartyDisconnectResult disbanded() {
         return new PartyService.PartyDisconnectResult(Outcome.DISBANDED, false, null);
      }

      static PartyService.PartyDisconnectResult kept(boolean wasLeader, UUID newLeaderId) {
         return new PartyService.PartyDisconnectResult(Outcome.KEPT, wasLeader, newLeaderId);
      }
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
      private boolean open;
      private final Set<UUID> members;

      private PartyState(UUID partyId, UUID leaderId, boolean followLeader, boolean open, Set<UUID> members) {
         this.partyId = partyId;
         this.leaderId = leaderId;
         this.followLeader = followLeader;
         this.open = open;
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

      public boolean open() {
         return this.open;
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

      private void setOpen(boolean open) {
         this.open = open;
      }

      private PartyService.PartyState copy() {
         return new PartyService.PartyState(this.partyId, this.leaderId, this.followLeader, this.open, new HashSet<>(this.members));
      }
   }
}
