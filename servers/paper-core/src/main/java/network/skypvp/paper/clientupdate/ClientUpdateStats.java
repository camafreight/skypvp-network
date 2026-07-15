package network.skypvp.paper.clientupdate;

import java.util.EnumMap;
import java.util.Map;

/** Immutable snapshot of pipeline counters (enqueue / coalesce / emit / drop). */
public final class ClientUpdateStats {
   private final Map<UpdateChannel, long[]> byChannel;
   private final long drainTicks;

   ClientUpdateStats(Map<UpdateChannel, long[]> byChannel, long drainTicks) {
      this.byChannel = byChannel;
      this.drainTicks = drainTicks;
   }

   public long drainTicks() {
      return this.drainTicks;
   }

   /** Indexes: 0=enqueued, 1=coalesced, 2=emitted, 3=dropped */
   public long[] counters(UpdateChannel channel) {
      long[] raw = this.byChannel.get(channel);
      return raw == null ? new long[]{0L, 0L, 0L, 0L} : raw.clone();
   }

   public long enqueued(UpdateChannel channel) {
      return this.counters(channel)[0];
   }

   public long coalesced(UpdateChannel channel) {
      return this.counters(channel)[1];
   }

   public long emitted(UpdateChannel channel) {
      return this.counters(channel)[2];
   }

   public long dropped(UpdateChannel channel) {
      return this.counters(channel)[3];
   }

   public Map<UpdateChannel, long[]> all() {
      EnumMap<UpdateChannel, long[]> copy = new EnumMap<>(UpdateChannel.class);
      for (Map.Entry<UpdateChannel, long[]> entry : this.byChannel.entrySet()) {
         copy.put(entry.getKey(), entry.getValue().clone());
      }
      return copy;
   }
}
