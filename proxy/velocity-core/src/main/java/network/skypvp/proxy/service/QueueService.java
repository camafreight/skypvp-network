package network.skypvp.proxy.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.shared.QueueStatusSnapshot;
import network.skypvp.shared.RedisConnectionSettings;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

public final class QueueService implements AutoCloseable {
   private static final String KEY_ACTIVE = "skypvp:queue:active";
   private static final String KEY_PLAYERS = "skypvp:queue:players";
   private static final String KEY_PREFIX = "skypvp:queue:data:";
   private final JedisPooled jedis;
   private final boolean useRedis;
   private final Map<String, Deque<QueueService.QueueEntry>> queues = new ConcurrentHashMap<>();
   private final Map<UUID, String> queueByPlayer = new ConcurrentHashMap<>();
   private final Map<UUID, QueueService.PendingSwap> swapConfirmations = new ConcurrentHashMap<>();

   public QueueService(RedisConnectionSettings redisSettings) {
      DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
         .password(redisSettings.sanitizedPassword().isBlank() ? null : redisSettings.sanitizedPassword())
         .database(redisSettings.database())
         .build();
      this.jedis = new JedisPooled(new HostAndPort(redisSettings.host(), redisSettings.port()), clientConfig);
      this.useRedis = true;
   }

   public QueueService() {
      this.jedis = null;
      this.useRedis = false;
   }

   public synchronized QueueService.QueueJoinResult joinQueue(UUID playerId, String username, String queueKey) {
      if (playerId == null || username == null || username.isBlank() || queueKey == null || queueKey.isBlank()) {
         return QueueService.QueueJoinResult.invalid();
      } else {
         return this.useRedis ? this.redisJoin(playerId, username, queueKey) : this.memJoin(playerId, username, queueKey);
      }
   }

   public synchronized QueueService.QueueLeaveResult leaveQueue(UUID playerId) {
      return this.useRedis ? this.redisLeave(playerId) : this.memLeave(playerId);
   }

   public synchronized Optional<QueueStatusSnapshot> status(UUID playerId, String bestTargetServerId) {
      return this.useRedis ? this.redisStatus(playerId, bestTargetServerId) : this.memStatus(playerId, bestTargetServerId);
   }

   public synchronized Optional<QueueService.QueueEntry> peek(String queueKey) {
      return this.useRedis ? this.redisPeek(queueKey) : this.memPeek(queueKey);
   }

   public synchronized Optional<QueueService.QueueEntry> poll(String queueKey) {
      return this.useRedis ? this.redisPoll(queueKey) : this.memPoll(queueKey);
   }

   public synchronized int sizeOf(String queueKey) {
      if (this.useRedis) {
         long len = this.jedis.llen(KEY_PREFIX + queueKey);
         return (int)len;
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
         return q == null ? 0 : q.size();
      }
   }

   public synchronized int totalQueuedPlayers() {
      return this.useRedis ? (int)this.jedis.hlen(KEY_PLAYERS) : this.queueByPlayer.size();
   }

   public synchronized int positionOf(UUID playerId, String queueKey) {
      if (this.useRedis) {
         List<String> all = this.jedis.lrange(KEY_PREFIX + queueKey, 0L, -1L);
         String uuidStr = playerId.toString();

         for (int i = 0; i < all.size(); i++) {
            QueueService.QueueEntry e = deserialize(all.get(i));
            if (e != null && e.playerId().equals(playerId)) {
               return i + 1;
            }
         }

         return -1;
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
         if (q == null) {
            return -1;
         } else {
            int pos = 1;

            for (QueueService.QueueEntry e : q) {
               if (e.playerId().equals(playerId)) {
                  return pos;
               }

               pos++;
            }

            return -1;
         }
      }
   }

   public synchronized Map<String, Integer> queueSizes() {
      Map<String, Integer> sizes = new LinkedHashMap<>();
      if (!this.useRedis) {
         this.queues.forEach((keyx, q) -> sizes.put(keyx, q.size()));
         return sizes;
      } else {
         for (String key : this.jedis.smembers(KEY_ACTIVE)) {
            sizes.put(key, (int)this.jedis.llen(KEY_PREFIX + key));
         }

         return sizes;
      }
   }

   public synchronized List<QueueService.QueueEntry> entriesFor(String queueKey) {
      if (this.useRedis) {
         List<String> raw = this.jedis.lrange(KEY_PREFIX + queueKey, 0L, -1L);
         List<QueueService.QueueEntry> result = new ArrayList<>(raw.size());

         for (String s : raw) {
            QueueService.QueueEntry e = deserialize(s);
            if (e != null) {
               result.add(e);
            }
         }

         return result;
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
         return (List<QueueService.QueueEntry>)(q == null ? List.of() : new ArrayList<>(q));
      }
   }

   public synchronized Collection<String> queueKeys() {
      return (Collection<String>)(this.useRedis ? this.jedis.smembers(KEY_ACTIVE) : List.copyOf(this.queues.keySet()));
   }

   public synchronized boolean isQueued(UUID playerId) {
      return this.useRedis ? this.jedis.hexists(KEY_PLAYERS, playerId.toString()) : this.queueByPlayer.containsKey(playerId);
   }

   public synchronized Optional<String> queueKeyFor(UUID playerId) {
      return this.useRedis
         ? Optional.ofNullable(this.jedis.hget(KEY_PLAYERS, playerId.toString()))
         : Optional.ofNullable(this.queueByPlayer.get(playerId));
   }

   public synchronized int clearQueue(String queueKey) {
      if (this.useRedis) {
         List<String> all = this.jedis.lrange(KEY_PREFIX + queueKey, 0L, -1L);

         for (String raw : all) {
            QueueService.QueueEntry e = deserialize(raw);
            if (e != null) {
               this.jedis.hdel(KEY_PLAYERS, new String[]{e.playerId().toString()});
            }
         }

         this.jedis.del(KEY_PREFIX + queueKey);
         this.jedis.srem(KEY_ACTIVE, new String[]{queueKey});
         return all.size();
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.remove(queueKey);
         if (q == null) {
            return 0;
         } else {
            q.forEach(ex -> this.queueByPlayer.remove(ex.playerId()));
            return q.size();
         }
      }
   }

   @Override
   public void close() {
      if (this.jedis != null) {
         this.jedis.close();
      }
   }

   private QueueService.QueueJoinResult redisJoin(UUID playerId, String username, String queueKey) {
      String uuidStr = playerId.toString();
      String existing = this.jedis.hget(KEY_PLAYERS, uuidStr);
      if (existing != null) {
         if (existing.equalsIgnoreCase(queueKey)) {
            int pos = this.positionOf(playerId, existing);
            int size = (int)this.jedis.llen(KEY_PREFIX + existing);
            return QueueService.QueueJoinResult.alreadyQueued(existing, pos, size);
         }

         QueueService.PendingSwap pending = this.swapConfirmations.get(playerId);
         if (pending == null || !pending.targetQueue().equalsIgnoreCase(queueKey) || System.currentTimeMillis() - pending.timestamp() >= 10000L) {
            this.swapConfirmations.put(playerId, new QueueService.PendingSwap(queueKey, System.currentTimeMillis()));
            return QueueService.QueueJoinResult.requiresSwapConfirmation(existing, queueKey);
         }

         this.swapConfirmations.remove(playerId);
         this.redisLeave(playerId);
      }

      String json = serialize(new QueueService.QueueEntry(playerId, username, System.currentTimeMillis()));
      this.jedis.rpush(KEY_PREFIX + queueKey, new String[]{json});
      this.jedis.hset(KEY_PLAYERS, uuidStr, queueKey);
      this.jedis.sadd(KEY_ACTIVE, new String[]{queueKey});
      int size = (int)this.jedis.llen(KEY_PREFIX + queueKey);
      this.swapConfirmations.remove(playerId);
      return QueueService.QueueJoinResult.joined(queueKey, size, size);
   }

   private QueueService.QueueLeaveResult redisLeave(UUID playerId) {
      String uuidStr = playerId.toString();
      String queueKey = this.jedis.hget(KEY_PLAYERS, uuidStr);
      if (queueKey == null) {
         return QueueService.QueueLeaveResult.notQueued();
      } else {
         this.jedis.hdel(KEY_PLAYERS, new String[]{uuidStr});

         for (String raw : this.jedis.lrange(KEY_PREFIX + queueKey, 0L, -1L)) {
            QueueService.QueueEntry e = deserialize(raw);
            if (e != null && e.playerId().equals(playerId)) {
               this.jedis.lrem(KEY_PREFIX + queueKey, 1L, raw);
               break;
            }
         }

         if (this.jedis.llen(KEY_PREFIX + queueKey) == 0L) {
            this.jedis.srem(KEY_ACTIVE, new String[]{queueKey});
         }

         return QueueService.QueueLeaveResult.left(queueKey);
      }
   }

   private Optional<QueueStatusSnapshot> redisStatus(UUID playerId, String bestTargetServerId) {
      String queueKey = this.jedis.hget(KEY_PLAYERS, playerId.toString());
      if (queueKey == null) {
         return Optional.empty();
      } else {
         int pos = this.positionOf(playerId, queueKey);
         int size = (int)this.jedis.llen(KEY_PREFIX + queueKey);
         if (pos < 0) {
            this.jedis.hdel(KEY_PLAYERS, new String[]{playerId.toString()});
            return Optional.empty();
         } else {
            List<String> all = this.jedis.lrange(KEY_PREFIX + queueKey, 0L, -1L);
            String username = playerId.toString();

            for (String raw : all) {
               QueueService.QueueEntry e = deserialize(raw);
               if (e != null && e.playerId().equals(playerId)) {
                  username = e.username();
                  break;
               }
            }

            return Optional.of(new QueueStatusSnapshot(playerId, username, queueKey, pos, size, bestTargetServerId));
         }
      }
   }

   private Optional<QueueService.QueueEntry> redisPeek(String queueKey) {
      List<String> head = this.jedis.lrange(KEY_PREFIX + queueKey, 0L, 0L);
      return head.isEmpty() ? Optional.empty() : Optional.ofNullable(deserialize(head.get(0)));
   }

   private Optional<QueueService.QueueEntry> redisPoll(String queueKey) {
      String raw = this.jedis.lpop(KEY_PREFIX + queueKey);
      if (raw == null) {
         return Optional.empty();
      } else {
         QueueService.QueueEntry entry = deserialize(raw);
         if (entry != null) {
            this.jedis.hdel(KEY_PLAYERS, new String[]{entry.playerId().toString()});
            if (this.jedis.llen(KEY_PREFIX + queueKey) == 0L) {
               this.jedis.srem(KEY_ACTIVE, new String[]{queueKey});
            }
         }

         return Optional.ofNullable(entry);
      }
   }

   private QueueService.QueueJoinResult memJoin(UUID playerId, String username, String queueKey) {
      String existing = this.queueByPlayer.get(playerId);
      if (existing != null) {
         if (existing.equalsIgnoreCase(queueKey)) {
            return QueueService.QueueJoinResult.alreadyQueued(existing, this.positionOf(playerId, existing), this.sizeOf(existing));
         }

         QueueService.PendingSwap pending = this.swapConfirmations.get(playerId);
         if (pending == null || !pending.targetQueue().equalsIgnoreCase(queueKey) || System.currentTimeMillis() - pending.timestamp() >= 10000L) {
            this.swapConfirmations.put(playerId, new QueueService.PendingSwap(queueKey, System.currentTimeMillis()));
            return QueueService.QueueJoinResult.requiresSwapConfirmation(existing, queueKey);
         }

         this.swapConfirmations.remove(playerId);
         this.memLeave(playerId);
      }

      Deque<QueueService.QueueEntry> q = this.queues.computeIfAbsent(queueKey, k -> new ArrayDeque<>());
      q.addLast(new QueueService.QueueEntry(playerId, username, System.currentTimeMillis()));
      this.queueByPlayer.put(playerId, queueKey);
      this.swapConfirmations.remove(playerId);
      return QueueService.QueueJoinResult.joined(queueKey, q.size(), q.size());
   }

   private QueueService.QueueLeaveResult memLeave(UUID playerId) {
      String queueKey = this.queueByPlayer.remove(playerId);
      if (queueKey == null) {
         return QueueService.QueueLeaveResult.notQueued();
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
         if (q != null) {
            q.removeIf(e -> e.playerId().equals(playerId));
            if (q.isEmpty()) {
               this.queues.remove(queueKey);
            }
         }

         return QueueService.QueueLeaveResult.left(queueKey);
      }
   }

   private Optional<QueueStatusSnapshot> memStatus(UUID playerId, String bestTargetServerId) {
      String queueKey = this.queueByPlayer.get(playerId);
      if (queueKey == null) {
         return Optional.empty();
      } else {
         Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
         if (q == null) {
            this.queueByPlayer.remove(playerId);
            return Optional.empty();
         } else {
            int position = 1;

            for (QueueService.QueueEntry e : q) {
               if (e.playerId().equals(playerId)) {
                  return Optional.of(new QueueStatusSnapshot(playerId, e.username(), queueKey, position, q.size(), bestTargetServerId));
               }

               position++;
            }

            this.queueByPlayer.remove(playerId);
            return Optional.empty();
         }
      }
   }

   private Optional<QueueService.QueueEntry> memPeek(String queueKey) {
      Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
      return q == null ? Optional.empty() : Optional.ofNullable(q.peekFirst());
   }

   private Optional<QueueService.QueueEntry> memPoll(String queueKey) {
      Deque<QueueService.QueueEntry> q = this.queues.get(queueKey);
      if (q == null) {
         return Optional.empty();
      } else {
         QueueService.QueueEntry e = q.pollFirst();
         if (e == null) {
            return Optional.empty();
         } else {
            this.queueByPlayer.remove(e.playerId());
            if (q.isEmpty()) {
               this.queues.remove(queueKey);
            }

            return Optional.of(e);
         }
      }
   }

   private static String serialize(QueueService.QueueEntry e) {
      JsonObject o = new JsonObject();
      o.addProperty("p", e.playerId().toString());
      o.addProperty("u", e.username());
      o.addProperty("t", e.joinedAtEpochMillis());
      return o.toString();
   }

   private static QueueService.QueueEntry deserialize(String json) {
      try {
         JsonObject o = JsonParser.parseString(json).getAsJsonObject();
         UUID uuid = UUID.fromString(o.get("p").getAsString());
         String username = o.get("u").getAsString();
         long ts = o.get("t").getAsLong();
         return new QueueService.QueueEntry(uuid, username, ts);
      } catch (Exception var6) {
         return null;
      }
   }

   public static record PendingSwap(String targetQueue, long timestamp) {
   }

   public static record QueueEntry(UUID playerId, String username, long joinedAtEpochMillis) {
   }

   public static record QueueJoinResult(
      boolean joined,
      boolean alreadyQueued,
      boolean requiresSwapConfirmation,
      boolean valid,
      String queueKey,
      String targetQueueKey,
      int position,
      int queueSize
   ) {
      static QueueService.QueueJoinResult joined(String queueKey, int position, int queueSize) {
         return new QueueService.QueueJoinResult(true, false, false, true, queueKey, null, position, queueSize);
      }

      static QueueService.QueueJoinResult alreadyQueued(String queueKey, int position, int queueSize) {
         return new QueueService.QueueJoinResult(false, true, false, true, queueKey, null, position, queueSize);
      }

      static QueueService.QueueJoinResult requiresSwapConfirmation(String currentQueue, String targetQueue) {
         return new QueueService.QueueJoinResult(false, false, true, true, currentQueue, targetQueue, -1, 0);
      }

      static QueueService.QueueJoinResult invalid() {
         return new QueueService.QueueJoinResult(false, false, false, false, null, null, -1, 0);
      }
   }

   public static record QueueLeaveResult(boolean left, String queueKey) {
      static QueueService.QueueLeaveResult left(String queueKey) {
         return new QueueService.QueueLeaveResult(true, queueKey);
      }

      static QueueService.QueueLeaveResult notQueued() {
         return new QueueService.QueueLeaveResult(false, null);
      }
   }
}
