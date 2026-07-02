package network.skypvp.proxy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.core.database.DatabaseManager;
import org.slf4j.Logger;

public final class FriendRepository implements AutoCloseable {
   // $VF: renamed from: DDL java.lang.String
   private static final String DDL = "create table if not exists network_friend_requests (\n    requester_id uuid not null,\n    target_id uuid not null,\n    requested_at timestamptz not null default now(),\n    expires_at timestamptz not null,\n    primary key (requester_id, target_id)\n);\n\ncreate index if not exists idx_friend_requests_target\n    on network_friend_requests (target_id, requested_at desc);\n\ncreate table if not exists network_friendships (\n    player_a uuid not null,\n    player_b uuid not null,\n    created_at timestamptz not null default now(),\n    created_by uuid,\n    primary key (player_a, player_b)\n);\n\ncreate index if not exists idx_friendships_player_a on network_friendships (player_a);\ncreate index if not exists idx_friendships_player_b on network_friendships (player_b);\n";
   private final DatabaseManager dataSource;
   private final Logger logger;
   private final PlayerSocialSettingsRepository socialSettingsRepository;

   public FriendRepository(DatabaseManager dataSource, Logger logger) {
      this(dataSource, logger, null);
   }

   public FriendRepository(DatabaseManager dataSource, Logger logger, PlayerSocialSettingsRepository socialSettingsRepository) {
      this.dataSource = dataSource;
      this.logger = logger;
      this.socialSettingsRepository = socialSettingsRepository;
      this.applyMigration();
   }

   public FriendRepository.FriendRequestResult sendRequest(UUID requesterId, UUID targetId, Duration ttl) {
      if (requesterId != null && targetId != null && !requesterId.equals(targetId)) {
         if (this.socialSettingsRepository != null) {
            this.socialSettingsRepository.refresh(targetId);
            if (this.socialSettingsRepository.blocksFriendRequests(targetId)) {
               return FriendRepository.FriendRequestResult.REQUESTS_BLOCKED;
            }
         }
         UUID a = this.method_260(requesterId, targetId);
         UUID b = this.method_261(requesterId, targetId);

         try {
            FriendRepository.FriendRequestResult var15;
            try (Connection connection = this.dataSource.getConnection()) {
               if (this.isFriends(connection, a, b)) {
                  return FriendRepository.FriendRequestResult.ALREADY_FRIENDS;
               }

               try (PreparedStatement ps = connection.prepareStatement(
                     "insert into network_friend_requests (requester_id, target_id, expires_at) values (?, ?, ?) on conflict (requester_id, target_id) do update set expires_at = excluded.expires_at, requested_at = now()"
                  )) {
                  ps.setObject(1, requesterId);
                  ps.setObject(2, targetId);
                  ps.setTimestamp(3, Timestamp.from(Instant.now().plus(ttl == null ? Duration.ofMinutes(10L) : ttl)));
                  ps.executeUpdate();
               }

               var15 = FriendRepository.FriendRequestResult.SENT;
            }

            return var15;
         } catch (SQLException var14) {
            this.logger.warn("FriendRepository.sendRequest: {}", var14.getMessage());
            return FriendRepository.FriendRequestResult.ERROR;
         }
      } else {
         return FriendRepository.FriendRequestResult.INVALID;
      }
   }

   public FriendRepository.AcceptResult acceptRequest(UUID targetId, UUID requesterId) {
      if (targetId != null && requesterId != null && !targetId.equals(requesterId)) {
         UUID a = this.method_260(targetId, requesterId);
         UUID b = this.method_261(targetId, requesterId);

         try {
            FriendRepository.AcceptResult var25;
            try (Connection connection = this.dataSource.getConnection()) {
               connection.setAutoCommit(false);

               try {
                  boolean requestExists;
                  try (PreparedStatement check = connection.prepareStatement(
                        "select 1 from network_friend_requests where requester_id = ? and target_id = ? and expires_at >= now()"
                     )) {
                     check.setObject(1, requesterId);
                     check.setObject(2, targetId);

                     try (ResultSet rs = check.executeQuery()) {
                        requestExists = rs.next();
                     }
                  }

                  if (!requestExists) {
                     connection.rollback();
                     connection.setAutoCommit(true);
                     return FriendRepository.AcceptResult.NOT_FOUND;
                  }

                  try (PreparedStatement insert = connection.prepareStatement(
                        "insert into network_friendships (player_a, player_b, created_by) values (?, ?, ?) on conflict do nothing"
                     )) {
                     insert.setObject(1, a);
                     insert.setObject(2, b);
                     insert.setObject(3, targetId);
                     insert.executeUpdate();
                  }

                  try (PreparedStatement delete = connection.prepareStatement(
                        "delete from network_friend_requests where (requester_id = ? and target_id = ?) or (requester_id = ? and target_id = ?)"
                     )) {
                     delete.setObject(1, requesterId);
                     delete.setObject(2, targetId);
                     delete.setObject(3, targetId);
                     delete.setObject(4, requesterId);
                     delete.executeUpdate();
                  }

                  connection.commit();
                  connection.setAutoCommit(true);
                  var25 = FriendRepository.AcceptResult.ACCEPTED;
               } catch (SQLException var20) {
                  connection.rollback();
                  connection.setAutoCommit(true);
                  throw var20;
               }
            }

            return var25;
         } catch (SQLException var22) {
            this.logger.warn("FriendRepository.acceptRequest: {}", var22.getMessage());
            return FriendRepository.AcceptResult.ERROR;
         }
      } else {
         return FriendRepository.AcceptResult.INVALID;
      }
   }

   public boolean denyRequest(UUID targetId, UUID requesterId) {
      if (targetId != null && requesterId != null) {
         try {
            boolean var5;
            try (
               Connection connection = this.dataSource.getConnection();
               PreparedStatement ps = connection.prepareStatement("delete from network_friend_requests where requester_id = ? and target_id = ?");
            ) {
               ps.setObject(1, requesterId);
               ps.setObject(2, targetId);
               var5 = ps.executeUpdate() > 0;
            }

            return var5;
         } catch (SQLException var11) {
            this.logger.warn("FriendRepository.denyRequest: {}", var11.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public boolean removeFriend(UUID first, UUID second) {
      if (first != null && second != null && !first.equals(second)) {
         UUID a = this.method_260(first, second);
         UUID b = this.method_261(first, second);

         try {
            boolean var7;
            try (
               Connection connection = this.dataSource.getConnection();
               PreparedStatement ps = connection.prepareStatement("delete from network_friendships where player_a = ? and player_b = ?");
            ) {
               ps.setObject(1, a);
               ps.setObject(2, b);
               var7 = ps.executeUpdate() > 0;
            }

            return var7;
         } catch (SQLException var13) {
            this.logger.warn("FriendRepository.removeFriend: {}", var13.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public boolean areFriends(UUID first, UUID second) {
      if (first != null && second != null && !first.equals(second)) {
         UUID a = this.method_260(first, second);
         UUID b = this.method_261(first, second);

         try {
            boolean var6;
            try (Connection connection = this.dataSource.getConnection()) {
               var6 = this.isFriends(connection, a, b);
            }

            return var6;
         } catch (SQLException var10) {
            this.logger.warn("FriendRepository.areFriends: {}", var10.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   public List<UUID> listFriends(UUID playerId) {
      List<UUID> results = new ArrayList<>();
      if (playerId == null) {
         return results;
      } else {
         try (
            Connection connection = this.dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
               "select case when player_a = ? then player_b else player_a end as friend_id from network_friendships where player_a = ? or player_b = ?"
            );
         ) {
            ps.setObject(1, playerId);
            ps.setObject(2, playerId);
            ps.setObject(3, playerId);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  if (rs.getObject("friend_id") instanceof UUID id) {
                     results.add(id);
                  }
               }
            }
         } catch (SQLException var14) {
            this.logger.warn("FriendRepository.listFriends: {}", var14.getMessage());
         }

         return results;
      }
   }

   public Optional<UUID> latestIncomingRequestFrom(UUID targetId) {
      if (targetId == null) {
         return Optional.empty();
      } else {
         try (
            Connection connection = this.dataSource.getConnection();
            PreparedStatement ps = connection.prepareStatement(
               "select requester_id from network_friend_requests where target_id = ? and expires_at >= now() order by requested_at desc limit 1"
            );
         ) {
            ps.setObject(1, targetId);

            try (ResultSet rs = ps.executeQuery()) {
               return rs.next() && rs.getObject("requester_id") instanceof UUID id ? Optional.of(id) : Optional.empty();
            }
         } catch (SQLException var14) {
            this.logger.warn("FriendRepository.latestIncomingRequestFrom: {}", var14.getMessage());
            return Optional.empty();
         }
      }
   }

   private void applyMigration() {
      try (
         Connection connection = this.dataSource.getConnection();
         Statement statement = connection.createStatement();
      ) {
         statement.execute(
            "create table if not exists network_friend_requests (\n    requester_id uuid not null,\n    target_id uuid not null,\n    requested_at timestamptz not null default now(),\n    expires_at timestamptz not null,\n    primary key (requester_id, target_id)\n);\n\ncreate index if not exists idx_friend_requests_target\n    on network_friend_requests (target_id, requested_at desc);\n\ncreate table if not exists network_friendships (\n    player_a uuid not null,\n    player_b uuid not null,\n    created_at timestamptz not null default now(),\n    created_by uuid,\n    primary key (player_a, player_b)\n);\n\ncreate index if not exists idx_friendships_player_a on network_friendships (player_a);\ncreate index if not exists idx_friendships_player_b on network_friendships (player_b);\n"
         );
      } catch (SQLException var9) {
         this.logger.error("[Friends] Failed to apply migration: {}", var9.getMessage());
      }
   }

   private boolean isFriends(Connection connection, UUID a, UUID b) throws SQLException {
      boolean var6;
      try (PreparedStatement ps = connection.prepareStatement("select 1 from network_friendships where player_a = ? and player_b = ?")) {
         ps.setObject(1, a);
         ps.setObject(2, b);

         try (ResultSet rs = ps.executeQuery()) {
            var6 = rs.next();
         }
      }

      return var6;
   }

   // $VF: renamed from: min (java.util.UUID, java.util.UUID) java.util.UUID
   private UUID method_260(UUID left, UUID right) {
      return left.toString().compareTo(right.toString()) <= 0 ? left : right;
   }

   // $VF: renamed from: max (java.util.UUID, java.util.UUID) java.util.UUID
   private UUID method_261(UUID left, UUID right) {
      return left.toString().compareTo(right.toString()) >= 0 ? left : right;
   }

   @Override
   public void close() {
   }

   public static enum AcceptResult {
      ACCEPTED,
      NOT_FOUND,
      INVALID,
      ERROR;

      private AcceptResult() {
      }
   }

   public static enum FriendRequestResult {
      SENT,
      ALREADY_FRIENDS,
      REQUESTS_BLOCKED,
      INVALID,
      ERROR;

      private FriendRequestResult() {
      }
   }
}
