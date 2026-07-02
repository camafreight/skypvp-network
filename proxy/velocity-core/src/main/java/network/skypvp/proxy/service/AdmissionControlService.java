package network.skypvp.proxy.service;

import java.util.function.LongSupplier;

public final class AdmissionControlService {
   private final LongSupplier nanoTime;
   private boolean enabled;
   private int transfersPerSecond;
   private int burstCapacity;
   private double tokens;
   private long lastRefillNanos;

   public AdmissionControlService(int transfersPerSecond, int burstCapacity) {
      this(transfersPerSecond, burstCapacity, System::nanoTime);
   }

   AdmissionControlService(int transfersPerSecond, int burstCapacity, LongSupplier nanoTime) {
      this.nanoTime = nanoTime;
      this.enabled = true;
      this.transfersPerSecond = sanitizeRate(transfersPerSecond);
      this.burstCapacity = sanitizeBurst(burstCapacity);
      this.tokens = (double)this.burstCapacity;
      this.lastRefillNanos = nanoTime.getAsLong();
   }

   public synchronized boolean tryAcquireTransferPermit() {
      if (!this.enabled) {
         return true;
      } else {
         this.refill();
         if (this.tokens >= 1.0) {
            this.tokens--;
            return true;
         } else {
            return false;
         }
      }
   }

   public synchronized void setEnabled(boolean enabled) {
      this.enabled = enabled;
      if (enabled) {
         this.refill();
      }
   }

   public synchronized void reconfigure(int transfersPerSecond, int burstCapacity) {
      this.refill();
      this.transfersPerSecond = sanitizeRate(transfersPerSecond);
      this.burstCapacity = sanitizeBurst(burstCapacity);
      this.tokens = Math.min(this.tokens, (double)this.burstCapacity);
   }

   public synchronized AdmissionControlService.AdmissionSnapshot snapshot() {
      this.refill();
      return new AdmissionControlService.AdmissionSnapshot(this.enabled, this.transfersPerSecond, this.burstCapacity, Math.max(0.0, this.tokens));
   }

   private void refill() {
      long now = this.nanoTime.getAsLong();
      long elapsedNanos = Math.max(0L, now - this.lastRefillNanos);
      if (elapsedNanos != 0L) {
         double elapsedSeconds = (double)elapsedNanos / 1.0E9;
         this.tokens = Math.min((double)this.burstCapacity, this.tokens + elapsedSeconds * (double)this.transfersPerSecond);
         this.lastRefillNanos = now;
      }
   }

   private static int sanitizeRate(int value) {
      return Math.max(1, value);
   }

   private static int sanitizeBurst(int value) {
      return Math.max(1, value);
   }

   public static record AdmissionSnapshot(boolean enabled, int transfersPerSecond, int burstCapacity, double availableTokens) {
   }
}
