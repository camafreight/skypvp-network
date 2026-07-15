package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;

public final class SocialGraphRepository {
   private final AsyncDbExecutor asyncDbExecutor;

   public SocialGraphRepository(AsyncDbExecutor asyncDbExecutor) {
      this.asyncDbExecutor = asyncDbExecutor;
   }

   public CompletableFuture<List<SocialGraphRepository.SocialPlayer>> listFriends(UUID playerId) {
      return playerId == null
         ? CompletableFuture.completedFuture(List.of())
         : this.asyncDbExecutor
            .supply(
               "social.listFriends",
               connection -> {
                  String sql = "select\n    case when f.player_a = ? then f.player_b else f.player_a end as friend_id,\n    p.last_username as friend_name\nfrom network_friendships f\nleft join network_players p\n    on p.player_id = case when f.player_a = ? then f.player_b else f.player_a end\nwhere f.player_a = ? or f.player_b = ?\n";
                  List<SocialGraphRepository.SocialPlayer> friends = new ArrayList<>();

                  try (PreparedStatement statement = connection.prepareStatement(sql)) {
                     statement.setObject(1, playerId);
                     statement.setObject(2, playerId);
                     statement.setObject(3, playerId);
                     statement.setObject(4, playerId);

                     try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                           UUID friendId = toUuid(rs.getObject("friend_id"));
                           if (friendId != null) {
                              friends.add(new SocialGraphRepository.SocialPlayer(friendId, resolveName(rs.getString("friend_name"), friendId)));
                           }
                        }
                     }
                  }

                  friends.sort(Comparator.comparing(SocialGraphRepository.SocialPlayer::username, String.CASE_INSENSITIVE_ORDER));
                  return List.copyOf(friends);
               }
            );
   }

   public CompletableFuture<List<SocialGraphRepository.IncomingFriendRequest>> listIncomingFriendRequests(UUID targetId) {
      return targetId == null
         ? CompletableFuture.completedFuture(List.of())
         : this.asyncDbExecutor
            .supply(
               "social.listIncomingFriendRequests",
               connection -> {
                  String sql = "select r.requester_id, p.last_username as requester_name, r.requested_at, r.expires_at\nfrom network_friend_requests r\nleft join network_players p on p.player_id = r.requester_id\nwhere r.target_id = ?\n  and r.expires_at >= now()\norder by r.requested_at desc\n";
                  List<SocialGraphRepository.IncomingFriendRequest> requests = new ArrayList<>();

                  try (PreparedStatement statement = connection.prepareStatement(sql)) {
                     statement.setObject(1, targetId);

                     try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                           UUID requesterId = toUuid(rs.getObject("requester_id"));
                           if (requesterId != null) {
                              requests.add(
                                 new SocialGraphRepository.IncomingFriendRequest(
                                    requesterId,
                                    resolveName(rs.getString("requester_name"), requesterId),
                                    toInstant(rs.getTimestamp("requested_at")),
                                    toInstant(rs.getTimestamp("expires_at"))
                                 )
                              );
                           }
                        }
                     }
                  }

                  return List.copyOf(requests);
               }
            );
   }

   public CompletableFuture<List<SocialGraphRepository.OutgoingFriendRequest>> listOutgoingFriendRequests(UUID requesterId) {
      return requesterId == null
         ? CompletableFuture.completedFuture(List.of())
         : this.asyncDbExecutor
            .supply(
               "social.listOutgoingFriendRequests",
               connection -> {
                  String sql = "select r.target_id, p.last_username as target_name, r.requested_at, r.expires_at\nfrom network_friend_requests r\nleft join network_players p on p.player_id = r.target_id\nwhere r.requester_id = ?\n  and r.expires_at >= now()\norder by r.requested_at desc\n";
                  List<SocialGraphRepository.OutgoingFriendRequest> requests = new ArrayList<>();

                  try (PreparedStatement statement = connection.prepareStatement(sql)) {
                     statement.setObject(1, requesterId);

                     try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                           UUID targetId = toUuid(rs.getObject("target_id"));
                           if (targetId != null) {
                              requests.add(
                                 new SocialGraphRepository.OutgoingFriendRequest(
                                    targetId,
                                    resolveName(rs.getString("target_name"), targetId),
                                    toInstant(rs.getTimestamp("requested_at")),
                                    toInstant(rs.getTimestamp("expires_at"))
                                 )
                              );
                           }
                        }
                     }
                  }

                  return List.copyOf(requests);
               }
            );
   }

   /**
    * Returns the subset of {@code memberIds} that currently have an OPEN network session (i.e. are online somewhere on
    * the network). Backs cross-server presence dots on the party scoreboard. Best-effort: a crashed server can leave a
    * session row open, so this is an approximation, not a hard guarantee.
    */
   public CompletableFuture<java.util.Set<UUID>> onlineMembers(java.util.Collection<UUID> memberIds) {
      if (memberIds == null || memberIds.isEmpty()) {
         return CompletableFuture.completedFuture(java.util.Set.of());
      }
      List<UUID> ids = List.copyOf(memberIds);
      return this.asyncDbExecutor.supply("social.onlineMembers", connection -> {
         StringBuilder placeholders = new StringBuilder();
         for (int index = 0; index < ids.size(); index++) {
            placeholders.append(index == 0 ? "?" : ",?");
         }
         String sql = "select distinct player_id from network_player_sessions "
            + "where left_at is null and player_id in (" + placeholders + ")";
         java.util.Set<UUID> online = new java.util.HashSet<>();
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < ids.size(); index++) {
               statement.setObject(index + 1, ids.get(index));
            }
            try (ResultSet rs = statement.executeQuery()) {
               while (rs.next()) {
                  UUID id = toUuid(rs.getObject("player_id"));
                  if (id != null) {
                     online.add(id);
                  }
               }
            }
         }
         return online;
      });
   }

   public CompletableFuture<Optional<SocialGraphRepository.PartySnapshot>> partyForMember(UUID memberId) {
      return memberId == null
         ? CompletableFuture.completedFuture(Optional.empty())
         : this.asyncDbExecutor
            .supply(
               "social.partyForMember",
               connection -> {
                  String sql = "select p.party_id, p.leader_id, p.follow_leader, p.open,\n       m.member_id, m.role, np.last_username as member_name\nfrom network_party_members anchor\njoin network_parties p on p.party_id = anchor.party_id\njoin network_party_members m on m.party_id = p.party_id\nleft join network_players np on np.player_id = m.member_id\nwhere anchor.member_id = ?\norder by np.last_username asc nulls last\n";
                  List<SocialGraphRepository.PartyMember> members = new ArrayList<>();
                  UUID partyId = null;
                  UUID leaderId = null;
                  boolean followLeader = true;
                  boolean open = false;

                  try (PreparedStatement statement = connection.prepareStatement(sql)) {
                     statement.setObject(1, memberId);

                     try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                           if (partyId == null) {
                              partyId = toUuid(rs.getObject("party_id"));
                              leaderId = toUuid(rs.getObject("leader_id"));
                              followLeader = rs.getBoolean("follow_leader");
                              open = rs.getBoolean("open");
                           }

                           UUID rowMemberId = toUuid(rs.getObject("member_id"));
                           if (rowMemberId != null) {
                              String name = resolveName(rs.getString("member_name"), rowMemberId);
                              boolean leader = rowMemberId.equals(leaderId);
                              PartyRole role = PartyRole.fromDatabase(rs.getString("role"), leader);
                              members.add(new SocialGraphRepository.PartyMember(rowMemberId, name, leader, role));
                           }
                        }
                     }
                  }

                  if (partyId != null && leaderId != null) {
                     members.sort(Comparator.comparing(SocialGraphRepository.PartyMember::username, String.CASE_INSENSITIVE_ORDER));
                     return Optional.of(new SocialGraphRepository.PartySnapshot(partyId, leaderId, followLeader, open, List.copyOf(members)));
                  } else {
                     return Optional.empty();
                  }
               }
            );
   }

   /**
    * Lists open parties a seeker could join for the "find a party" browser. Skips the seeker's own party and any
    * party already at/over {@code maxSize}, ordering the fullest (most social) parties first so groups fill up.
    */
   public CompletableFuture<List<SocialGraphRepository.OpenPartySummary>> listOpenParties(UUID viewerId, int maxSize) {
      if (viewerId == null) {
         return CompletableFuture.completedFuture(List.of());
      }
      return this.asyncDbExecutor.supply("social.listOpenParties", connection -> {
         String sql = "select p.party_id, p.leader_id, np.last_username as leader_name, count(m.member_id) as member_count\n"
            + "from network_parties p\n"
            + "join network_party_members m on m.party_id = p.party_id\n"
            + "left join network_players np on np.player_id = p.leader_id\n"
            + "where p.open = true\n"
            + "  and not exists (select 1 from network_party_members vm where vm.party_id = p.party_id and vm.member_id = ?)\n"
            + "group by p.party_id, p.leader_id, np.last_username\n"
            + "having count(m.member_id) < ?\n"
            + "order by member_count desc\n"
            + "limit 45\n";
         List<SocialGraphRepository.OpenPartySummary> parties = new ArrayList<>();
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, viewerId);
            statement.setInt(2, maxSize);
            try (ResultSet rs = statement.executeQuery()) {
               while (rs.next()) {
                  UUID partyId = toUuid(rs.getObject("party_id"));
                  UUID leaderId = toUuid(rs.getObject("leader_id"));
                  if (partyId != null && leaderId != null) {
                     parties.add(new SocialGraphRepository.OpenPartySummary(
                        partyId,
                        leaderId,
                        resolveName(rs.getString("leader_name"), leaderId),
                        rs.getInt("member_count")
                     ));
                  }
               }
            }
         }
         return List.copyOf(parties);
      });
   }

   public CompletableFuture<Boolean> setPartyFollowLeader(UUID actorId, boolean followLeader) {
      if (actorId == null) {
         return CompletableFuture.completedFuture(false);
      }
      return this.asyncDbExecutor.supply("social.setPartyFollowLeader", connection -> {
         String leaderSql = "select party_id from network_parties where leader_id = ?";
         UUID partyId = null;
         try (PreparedStatement statement = connection.prepareStatement(leaderSql)) {
            statement.setObject(1, actorId);
            try (ResultSet rs = statement.executeQuery()) {
               if (rs.next()) {
                  partyId = toUuid(rs.getObject("party_id"));
               }
            }
         }
         if (partyId == null) {
            return false;
         }
         String updateSql = "update network_parties set follow_leader = ?, updated_at = now() where party_id = ?";
         try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setBoolean(1, followLeader);
            statement.setObject(2, partyId);
            return statement.executeUpdate() > 0;
         }
      });
   }

   public CompletableFuture<Boolean> updatePartyMemberRole(UUID actorId, UUID targetMemberId, PartyRole newRole) {
      if (actorId == null || targetMemberId == null || newRole == null || newRole == PartyRole.LEADER) {
         return CompletableFuture.completedFuture(false);
      }
      return this.asyncDbExecutor.supply("social.updatePartyMemberRole", connection -> {
         UUID partyId = this.resolvePartyId(connection, actorId);
         if (partyId == null || !actorId.equals(this.resolveLeaderId(connection, partyId))) {
            return false;
         }
         if (targetMemberId.equals(actorId)) {
            return false;
         }
         String sql = "update network_party_members set role = ? where party_id = ? and member_id = ?";
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newRole.databaseValue());
            statement.setObject(2, partyId);
            statement.setObject(3, targetMemberId);
            return statement.executeUpdate() > 0;
         }
      });
   }

   public CompletableFuture<Boolean> transferPartyLeadership(UUID actorId, UUID newLeaderId) {
      if (actorId == null || newLeaderId == null || actorId.equals(newLeaderId)) {
         return CompletableFuture.completedFuture(false);
      }
      return this.asyncDbExecutor.supply("social.transferPartyLeadership", connection -> {
         UUID partyId = this.resolvePartyId(connection, actorId);
         if (partyId == null || !actorId.equals(this.resolveLeaderId(connection, partyId))) {
            return false;
         }
         if (!this.isPartyMember(connection, partyId, newLeaderId)) {
            return false;
         }
         String updateParty = "update network_parties set leader_id = ?, updated_at = now() where party_id = ?";
         String demoteOld = "update network_party_members set role = 'CO_LEADER' where party_id = ? and member_id = ?";
         String promoteNew = "update network_party_members set role = 'LEADER' where party_id = ? and member_id = ?";
         try (PreparedStatement updatePartyStatement = connection.prepareStatement(updateParty)) {
            updatePartyStatement.setObject(1, newLeaderId);
            updatePartyStatement.setObject(2, partyId);
            if (updatePartyStatement.executeUpdate() <= 0) {
               return false;
            }
         }
         try (PreparedStatement demoteStatement = connection.prepareStatement(demoteOld)) {
            demoteStatement.setObject(1, partyId);
            demoteStatement.setObject(2, actorId);
            demoteStatement.executeUpdate();
         }
         try (PreparedStatement promoteStatement = connection.prepareStatement(promoteNew)) {
            promoteStatement.setObject(1, partyId);
            promoteStatement.setObject(2, newLeaderId);
            return promoteStatement.executeUpdate() > 0;
         }
      });
   }

   private UUID resolvePartyId(java.sql.Connection connection, UUID memberId) throws java.sql.SQLException {
      String sql = "select party_id from network_party_members where member_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
         statement.setObject(1, memberId);
         try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? toUuid(rs.getObject("party_id")) : null;
         }
      }
   }

   private UUID resolveLeaderId(java.sql.Connection connection, UUID partyId) throws java.sql.SQLException {
      String sql = "select leader_id from network_parties where party_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
         statement.setObject(1, partyId);
         try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? toUuid(rs.getObject("leader_id")) : null;
         }
      }
   }

   private boolean isPartyMember(java.sql.Connection connection, UUID partyId, UUID memberId) throws java.sql.SQLException {
      String sql = "select 1 from network_party_members where party_id = ? and member_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
         statement.setObject(1, partyId);
         statement.setObject(2, memberId);
         try (ResultSet rs = statement.executeQuery()) {
            return rs.next();
         }
      }
   }

   public CompletableFuture<List<SocialGraphRepository.IncomingPartyInvite>> listIncomingPartyInvites(UUID targetId) {
      return targetId == null
         ? CompletableFuture.completedFuture(List.of())
         : this.asyncDbExecutor
            .supply(
               "social.listIncomingPartyInvites",
               connection -> {
                  String sql = "select i.inviter_id, i.party_id, i.expires_at, p.last_username as inviter_name\nfrom network_party_invites i\nleft join network_players p on p.player_id = i.inviter_id\nwhere i.target_id = ?\n  and i.expires_at >= now()\norder by i.created_at desc\n";
                  List<SocialGraphRepository.IncomingPartyInvite> invites = new ArrayList<>();

                  try (PreparedStatement statement = connection.prepareStatement(sql)) {
                     statement.setObject(1, targetId);

                     try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                           UUID inviterId = toUuid(rs.getObject("inviter_id"));
                           UUID partyId = toUuid(rs.getObject("party_id"));
                           if (inviterId != null && partyId != null) {
                              invites.add(
                                 new SocialGraphRepository.IncomingPartyInvite(
                                    inviterId, resolveName(rs.getString("inviter_name"), inviterId), partyId, toInstant(rs.getTimestamp("expires_at"))
                                 )
                              );
                           }
                        }
                     }
                  }

                  return List.copyOf(invites);
               }
            );
   }

   private static UUID toUuid(Object raw) {
      return raw instanceof UUID id ? id : null;
   }

   private static Instant toInstant(Timestamp timestamp) {
      return timestamp == null ? null : timestamp.toInstant();
   }

   private static String resolveName(String username, UUID playerId) {
      if (username != null && !username.isBlank()) {
         return username;
      } else if (playerId == null) {
         return "unknown";
      } else {
         String token = playerId.toString();
         return token.length() <= 8 ? token : token.substring(0, 8);
      }
   }

   public static record IncomingFriendRequest(UUID requesterId, String requesterName, Instant requestedAt, Instant expiresAt) {
   }

   public static record IncomingPartyInvite(UUID inviterId, String inviterName, UUID partyId, Instant expiresAt) {
   }

   public static record OutgoingFriendRequest(UUID targetId, String targetName, Instant requestedAt, Instant expiresAt) {
   }

   public static record PartyMember(UUID playerId, String username, boolean leader, PartyRole role) {
   }

   public static record PartySnapshot(UUID partyId, UUID leaderId, boolean followLeader, boolean open, List<SocialGraphRepository.PartyMember> members) {
   }

   public static record OpenPartySummary(UUID partyId, UUID leaderId, String leaderName, int memberCount) {
   }

   public static record SocialPlayer(UUID playerId, String username) {
   }
}
