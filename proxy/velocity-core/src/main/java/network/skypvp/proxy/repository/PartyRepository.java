package network.skypvp.proxy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import network.skypvp.core.database.DatabaseManager;
import org.slf4j.Logger;

public final class PartyRepository implements AutoCloseable {
   // $VF: renamed from: DDL java.lang.String
   private static final String DDL = "create table if not exists network_parties (\n    party_id uuid primary key,\n    leader_id uuid not null,\n    follow_leader boolean not null default true,\n    created_at timestamptz not null default now(),\n    updated_at timestamptz not null default now()\n);\n\ncreate table if not exists network_party_members (\n    party_id uuid not null references network_parties(party_id) on delete cascade,\n    member_id uuid not null,\n    joined_at timestamptz not null default now(),\n    role text not null default 'MEMBER',\n    primary key (party_id, member_id)\n);\n\ncreate index if not exists idx_party_members_member on network_party_members (member_id);\n\ncreate table if not exists network_party_invites (\n    target_id uuid not null,\n    inviter_id uuid not null,\n    party_id uuid not null,\n    expires_at timestamptz not null,\n    created_at timestamptz not null default now(),\n    primary key (target_id, inviter_id)\n);\n\ncreate index if not exists idx_party_invites_target on network_party_invites (target_id, expires_at desc);\n";
   private final DatabaseManager dataSource;
   private final Logger logger;

   public PartyRepository(DatabaseManager dataSource, Logger logger) {
      this.dataSource = dataSource;
      this.logger = logger;
      this.applyMigration();
   }

   public Map<UUID, PartyRepository.PartySnapshot> loadParties() {
      Map<UUID, PartyRepository.PartySnapshot> parties = new HashMap<>();
      String partySql = "select party_id, leader_id, follow_leader from network_parties";
      String memberSql = "select party_id, member_id from network_party_members";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement partyStatement = connection.prepareStatement(partySql);
         ResultSet partyRows = partyStatement.executeQuery();
      ) {
         while (partyRows.next()) {
            UUID partyId = (UUID)partyRows.getObject("party_id");
            UUID leaderId = (UUID)partyRows.getObject("leader_id");
            boolean followLeader = partyRows.getBoolean("follow_leader");
            parties.put(partyId, new PartyRepository.PartySnapshot(partyId, leaderId, followLeader, new HashSet<>()));
         }

         try (
            PreparedStatement memberStatement = connection.prepareStatement(memberSql);
            ResultSet memberRows = memberStatement.executeQuery();
         ) {
            while (memberRows.next()) {
               UUID partyId = (UUID)memberRows.getObject("party_id");
               UUID memberId = (UUID)memberRows.getObject("member_id");
               PartyRepository.PartySnapshot snapshot = parties.get(partyId);
               if (snapshot != null) {
                  snapshot.members().add(memberId);
               }
            }
         }
      } catch (SQLException var22) {
         this.logger.warn("PartyRepository.loadParties: {}", var22.getMessage());
      }

      return parties;
   }

   public List<PartyRepository.InviteSnapshot> loadInvites() {
      List<PartyRepository.InviteSnapshot> invites = new ArrayList<>();
      String sql = "select target_id, inviter_id, party_id, expires_at from network_party_invites where expires_at >= now()";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet rows = statement.executeQuery();
      ) {
         while (rows.next()) {
            invites.add(
               new PartyRepository.InviteSnapshot(
                  (UUID)rows.getObject("target_id"),
                  (UUID)rows.getObject("inviter_id"),
                  (UUID)rows.getObject("party_id"),
                  rows.getTimestamp("expires_at").toInstant()
               )
            );
         }
      } catch (SQLException var14) {
         this.logger.warn("PartyRepository.loadInvites: {}", var14.getMessage());
      }

      return invites;
   }

   public boolean upsertParty(UUID partyId, UUID leaderId, boolean followLeader) {
      String sql = "insert into network_parties (party_id, leader_id, follow_leader)\nvalues (?, ?, ?)\non conflict (party_id)\ndo update set leader_id = excluded.leader_id,\n              follow_leader = excluded.follow_leader,\n              updated_at = now()\n";

      try {
         boolean var7;
         try (
            Connection connection = this.dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql);
         ) {
            statement.setObject(1, partyId);
            statement.setObject(2, leaderId);
            statement.setBoolean(3, followLeader);
            var7 = statement.executeUpdate() > 0;
         }

         return var7;
      } catch (SQLException var13) {
         this.logger.warn("PartyRepository.upsertParty: {}", var13.getMessage());
         return false;
      }
   }

   public void replaceMembers(UUID partyId, Set<UUID> members) {
      String deleteSql = "delete from network_party_members where party_id = ?";
      String insertSql = "insert into network_party_members (party_id, member_id) values (?, ?)";

      try (Connection connection = this.dataSource.getConnection()) {
         connection.setAutoCommit(false);

         try (PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
            deleteStatement.setObject(1, partyId);
            deleteStatement.executeUpdate();
         }

         try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
            for (UUID memberId : members) {
               insertStatement.setObject(1, partyId);
               insertStatement.setObject(2, memberId);
               insertStatement.addBatch();
            }

            insertStatement.executeBatch();
         }

         connection.commit();
         connection.setAutoCommit(true);
      } catch (SQLException var15) {
         this.logger.warn("PartyRepository.replaceMembers: {}", var15.getMessage());
      }
   }

   public void deleteParty(UUID partyId) {
      String sql = "delete from network_parties where party_id = ?";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, partyId);
         statement.executeUpdate();
      } catch (SQLException var11) {
         this.logger.warn("PartyRepository.deleteParty: {}", var11.getMessage());
      }
   }

   public void upsertInvite(UUID targetId, UUID inviterId, UUID partyId, Instant expiresAt) {
      String sql = "insert into network_party_invites (target_id, inviter_id, party_id, expires_at)\nvalues (?, ?, ?, ?)\non conflict (target_id, inviter_id)\ndo update set party_id = excluded.party_id,\n              expires_at = excluded.expires_at,\n              created_at = now()\n";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, targetId);
         statement.setObject(2, inviterId);
         statement.setObject(3, partyId);
         statement.setObject(4, expiresAt);
         statement.executeUpdate();
      } catch (SQLException var14) {
         this.logger.warn("PartyRepository.upsertInvite: {}", var14.getMessage());
      }
   }

   public void clearInvite(UUID targetId, UUID inviterId) {
      String sql = "delete from network_party_invites where target_id = ? and inviter_id = ?";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, targetId);
         statement.setObject(2, inviterId);
         statement.executeUpdate();
      } catch (SQLException var12) {
         this.logger.warn("PartyRepository.clearInvite: {}", var12.getMessage());
      }
   }

   public Optional<Boolean> followLeaderForParty(UUID partyId) {
      if (partyId == null) {
         return Optional.empty();
      }

      String sql = "select follow_leader from network_parties where party_id = ?";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, partyId);

         try (ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
               return Optional.of(rows.getBoolean("follow_leader"));
            }
         }
      } catch (SQLException exception) {
         this.logger.warn("PartyRepository.followLeaderForParty: {}", exception.getMessage());
      }

      return Optional.empty();
   }

   public Optional<String> memberRole(UUID partyId, UUID memberId) {
      if (partyId == null || memberId == null) {
         return Optional.empty();
      }
      String sql = "select role from network_party_members where party_id = ? and member_id = ?";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, partyId);
         statement.setObject(2, memberId);
         try (ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
               return Optional.ofNullable(rows.getString("role"));
            }
         }
      } catch (SQLException exception) {
         this.logger.warn("PartyRepository.memberRole: {}", exception.getMessage());
      }

      return Optional.empty();
   }

   public void clearInvitesForTarget(UUID targetId) {
      String sql = "delete from network_party_invites where target_id = ?";

      try (
         Connection connection = this.dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setObject(1, targetId);
         statement.executeUpdate();
      } catch (SQLException var11) {
         this.logger.warn("PartyRepository.clearInvitesForTarget: {}", var11.getMessage());
      }
   }

   private void applyMigration() {
      try (
         Connection connection = this.dataSource.getConnection();
         Statement statement = connection.createStatement();
      ) {
         statement.execute(
            "create table if not exists network_parties (\n    party_id uuid primary key,\n    leader_id uuid not null,\n    follow_leader boolean not null default true,\n    created_at timestamptz not null default now(),\n    updated_at timestamptz not null default now()\n);\n\ncreate table if not exists network_party_members (\n    party_id uuid not null references network_parties(party_id) on delete cascade,\n    member_id uuid not null,\n    joined_at timestamptz not null default now(),\n    role text not null default 'MEMBER',\n    primary key (party_id, member_id)\n);\n\ncreate index if not exists idx_party_members_member on network_party_members (member_id);\n\ncreate table if not exists network_party_invites (\n    target_id uuid not null,\n    inviter_id uuid not null,\n    party_id uuid not null,\n    expires_at timestamptz not null,\n    created_at timestamptz not null default now(),\n    primary key (target_id, inviter_id)\n);\n\ncreate index if not exists idx_party_invites_target on network_party_invites (target_id, expires_at desc);\n"
         );
      } catch (SQLException var9) {
         this.logger.error("[Party] Failed to apply migration: {}", var9.getMessage());
      }
   }

   @Override
   public void close() {
   }

   public static record InviteSnapshot(UUID targetId, UUID inviterId, UUID partyId, Instant expiresAt) {
   }

   public static record PartySnapshot(UUID partyId, UUID leaderId, boolean followLeader, Set<UUID> members) {
   }
}
