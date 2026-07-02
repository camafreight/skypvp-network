package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.connection.LoginEvent;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class ReconnectFloodGuard {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final int MAX_CONNECTS = 5;
   private static final long WINDOW_SECONDS = 10L;
   private static final int MAX_TRACKED_IPS = 8000;
   private final Logger logger;
   private final ConcurrentHashMap<String, long[]> connectTimes = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

   public ReconnectFloodGuard(Logger logger) {
      this.logger = logger;
   }

   @Subscribe(
      order = PostOrder.FIRST
   )
   public void onLogin(LoginEvent event) {
      if (event.getResult().isAllowed()) {
         String ip = extractIp(event.getPlayer().getRemoteAddress().getAddress());
         long nowSec = Instant.now().getEpochSecond();
         this.evictIfNeeded();
         this.lastSeen.put(ip, nowSec);
         long[] times = this.connectTimes.computeIfAbsent(ip, k -> new long[5]);
         boolean blocked;
         long retryIn;
         synchronized (times) {
            long windowStart = nowSec - 10L;
            int count = 0;
            long oldestInWindow = nowSec;

            for (long t : times) {
               if (t > windowStart) {
                  count++;
                  if (t < oldestInWindow) {
                     oldestInWindow = t;
                  }
               }
            }

            if (count >= 5) {
               retryIn = 10L - (nowSec - oldestInWindow) + 1L;
               blocked = true;
            } else {
               blocked = false;
               retryIn = 0L;
               int oldestIdx = 0;

               for (int i = 1; i < times.length; i++) {
                  if (times[i] < times[oldestIdx]) {
                     oldestIdx = i;
                  }
               }

               times[oldestIdx] = nowSec;
            }
         }

         if (blocked) {
            event.setResult(
               ComponentResult.denied(
                  ServerTextUtil.miniMessageComponent(
                     "<#FF5555><bold>Connection Limit</bold><reset>\n<#888888>You are connecting too quickly. Retry in " + retryIn + "s.<reset>"
                  )
               )
            );
            this.logger.warning("[FloodGuard] Blocked rapid reconnect from IP " + ip);
         }
      }
   }

   private void evictIfNeeded() {
      if (this.connectTimes.size() >= 8000) {
         long cutoff = Instant.now().getEpochSecond() - 20L;
         Iterator<Entry<String, Long>> it = this.lastSeen.entrySet().iterator();
         int evicted = 0;

         while (it.hasNext() && evicted < 1000) {
            Entry<String, Long> e = it.next();
            if (e.getValue() < cutoff) {
               it.remove();
               this.connectTimes.remove(e.getKey());
               evicted++;
            }
         }
      }
   }

   private static String extractIp(InetAddress addr) {
      return addr.getHostAddress();
   }
}
