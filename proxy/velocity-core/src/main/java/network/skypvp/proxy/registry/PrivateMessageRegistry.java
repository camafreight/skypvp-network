package network.skypvp.proxy.registry;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateMessageRegistry {
   private final ConcurrentHashMap<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

   public PrivateMessageRegistry() {
   }

   public void recordMessage(UUID from, UUID to) {
      this.lastPartner.put(from, to);
      this.lastPartner.put(to, from);
   }

   public UUID getReplyTarget(UUID playerId) {
      return this.lastPartner.get(playerId);
   }

   public void evict(UUID playerId) {
      this.lastPartner.remove(playerId);
   }
}
