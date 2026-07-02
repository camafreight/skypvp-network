package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import network.skypvp.paper.database.AsyncDbExecutor;

public final class FriendGraphRepository {
   private final AsyncDbExecutor asyncDbExecutor;
   private final ConcurrentMap<String, Boolean> friendships = new ConcurrentHashMap<>();

   public FriendGraphRepository(AsyncDbExecutor asyncDbExecutor) {
      this.asyncDbExecutor = asyncDbExecutor;
   }

   public boolean areFriends(UUID first, UUID second) {
      return first != null && second != null && !first.equals(second) ? this.friendships.containsKey(this.pairKey(first, second)) : false;
   }

   public CompletableFuture<Void> refreshAsync() {
      return this.asyncDbExecutor.method_244("friends.refreshGraph", connection -> {
         ConcurrentMap<String, Boolean> next = new ConcurrentHashMap<>();
         String sql = "select player_a, player_b from network_friendships";

         try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery();
         ) {
            while (rs.next()) {
               Object rawA = rs.getObject("player_a");
               Object rawB = rs.getObject("player_b");
               if (rawA instanceof UUID) {
                  UUID a = (UUID)rawA;
                  if (rawB instanceof UUID b) {
                     next.put(this.pairKey(a, b), Boolean.TRUE);
                  }
               }
            }
         }

         this.friendships.clear();
         this.friendships.putAll(next);
      });
   }

   private String pairKey(UUID first, UUID second) {
      String left = first.toString();
      String right = second.toString();
      return left.compareTo(right) <= 0 ? left + ":" + right : right + ":" + left;
   }
}
