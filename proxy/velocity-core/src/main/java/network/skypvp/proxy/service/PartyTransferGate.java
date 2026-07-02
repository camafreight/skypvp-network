package network.skypvp.proxy.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows proxy-initiated party transfers to bypass {@code PartyNavigationGuardListener}.
 */
public final class PartyTransferGate {
   private static final Duration DEFAULT_TTL = Duration.ofSeconds(30L);
   private final Map<UUID, PartyTransferGate.PendingTransfer> pendingByMember = new ConcurrentHashMap<>();

   public void authorize(UUID memberId, String targetServerId) {
      this.authorize(memberId, targetServerId, DEFAULT_TTL);
   }

   public void authorize(UUID memberId, String targetServerId, Duration ttl) {
      if (memberId == null || targetServerId == null || targetServerId.isBlank()) {
         return;
      }

      Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
      this.pendingByMember.put(
         memberId,
         new PartyTransferGate.PendingTransfer(targetServerId.trim(), Instant.now().plus(safeTtl))
      );
   }

   public boolean allows(UUID memberId, String targetServerId) {
      if (memberId == null || targetServerId == null || targetServerId.isBlank()) {
         return false;
      }

      PartyTransferGate.PendingTransfer pending = this.pendingByMember.get(memberId);
      if (pending == null) {
         return false;
      }

      if (pending.expiresAt().isBefore(Instant.now())) {
         this.pendingByMember.remove(memberId, pending);
         return false;
      }

      return targetServerId.equalsIgnoreCase(pending.targetServerId());
   }

   public void clear(UUID memberId) {
      if (memberId != null) {
         this.pendingByMember.remove(memberId);
      }
   }

   private record PendingTransfer(String targetServerId, Instant expiresAt) {
   }
}
