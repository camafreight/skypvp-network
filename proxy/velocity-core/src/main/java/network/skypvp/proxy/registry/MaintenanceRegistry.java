package network.skypvp.proxy.registry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MaintenanceRegistry {
   private final AtomicBoolean enabled = new AtomicBoolean(false);
   private final AtomicLong enabledAtEpochMillis = new AtomicLong(0L);

   public MaintenanceRegistry() {
   }

   public boolean isEnabled() {
      return this.enabled.get();
   }

   public void setEnabled(boolean value) {
      boolean previous = this.enabled.getAndSet(value);
      if (value) {
         if (!previous) {
            this.enabledAtEpochMillis.set(System.currentTimeMillis());
         }
      } else {
         this.enabledAtEpochMillis.set(0L);
      }
   }

   public long enabledAtEpochMillis() {
      return this.enabledAtEpochMillis.get();
   }

   public long activeForMillis(long nowEpochMillis) {
      long startedAt = this.enabledAtEpochMillis.get();
      return startedAt <= 0L ? 0L : Math.max(0L, nowEpochMillis - startedAt);
   }
}
