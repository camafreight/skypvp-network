package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import network.skypvp.paper.database.AsyncDbExecutor;

public final class PartyGraphRepository {
   private final AsyncDbExecutor asyncDbExecutor;
   private final ConcurrentMap<String, Boolean> samePartyPairs = new ConcurrentHashMap<>();

   public PartyGraphRepository(AsyncDbExecutor asyncDbExecutor) {
      this.asyncDbExecutor = asyncDbExecutor;
   }

   public boolean inSameParty(UUID first, UUID second) {
      return first != null && second != null && !first.equals(second) ? this.samePartyPairs.containsKey(this.pairKey(first, second)) : false;
   }

   public CompletableFuture<Void> refreshAsync() {
      return this.asyncDbExecutor.method_244("party.refreshGraph", connection -> {
         ConcurrentMap<String, Boolean> next = new ConcurrentHashMap<>();
         String sql = "select party_id, member_id from network_party_members";
         ConcurrentMap<UUID, List<UUID>> membersByParty = new ConcurrentHashMap<>();

         try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery();
         ) {
            while (rs.next()) {
               Object rawParty = rs.getObject("party_id");
               Object rawMember = rs.getObject("member_id");
               if (rawParty instanceof UUID) {
                  UUID partyId = (UUID)rawParty;
                  if (rawMember instanceof UUID memberId) {
                     membersByParty.computeIfAbsent(partyId, ignored -> new ArrayList<>()).add(memberId);
                  }
               }
            }
         }

         for (List<UUID> members : membersByParty.values()) {
            for (int i = 0; i < members.size(); i++) {
               for (int j = i + 1; j < members.size(); j++) {
                  next.put(this.pairKey(members.get(i), members.get(j)), Boolean.TRUE);
               }
            }
         }

         this.samePartyPairs.clear();
         this.samePartyPairs.putAll(next);
      });
   }

   private String pairKey(UUID first, UUID second) {
      String left = first.toString();
      String right = second.toString();
      return left.compareTo(right) <= 0 ? left + ":" + right : right + ":" + left;
   }
}
