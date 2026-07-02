package network.skypvp.extraction.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

public final class BreachQueueService {

    private final BreachConfigServiceAccessor config;
    private final Deque<QueuedPlayer> queue = new ArrayDeque<>();
    private final Map<UUID, QueuedPlayer> byPlayer = new ConcurrentHashMap<>();

    public interface BreachConfigServiceAccessor {
        int queueTimeoutSeconds();

        String defaultMapId();
    }

    public BreachQueueService(BreachConfigServiceAccessor config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized boolean enqueue(Player player, String mapId) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        if (byPlayer.containsKey(playerId)) {
            return false;
        }
        String resolvedMap = resolveMapId(mapId);
        QueuedPlayer queued = new QueuedPlayer(playerId, resolvedMap, System.currentTimeMillis());
        queue.addLast(queued);
        byPlayer.put(playerId, queued);
        return true;
    }

    public synchronized boolean dequeue(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        QueuedPlayer removed = byPlayer.remove(playerId);
        if (removed == null) {
            return false;
        }
        queue.remove(removed);
        return true;
    }

    public synchronized Optional<QueuedPlayer> pollReadyPlayer() {
        purgeExpired();
        QueuedPlayer next = queue.pollFirst();
        if (next == null) {
            return Optional.empty();
        }
        byPlayer.remove(next.playerId());
        return Optional.of(next);
    }

    public synchronized Optional<QueuedPlayer> find(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPlayer.get(playerId));
    }

   public synchronized int size() {
      purgeExpired();
      return queue.size();
   }

   public synchronized int queuedCount() {
      return this.size();
   }

    public synchronized int position(UUID playerId) {
        purgeExpired();
        int index = 1;
        for (QueuedPlayer queued : queue) {
            if (queued.playerId().equals(playerId)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    public synchronized void clear() {
        queue.clear();
        byPlayer.clear();
    }

    private synchronized void purgeExpired() {
        long timeoutMillis = config.queueTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        Iterator<QueuedPlayer> iterator = queue.iterator();
        while (iterator.hasNext()) {
            QueuedPlayer queued = iterator.next();
            if (now - queued.enqueuedAtMillis() > timeoutMillis) {
                iterator.remove();
                byPlayer.remove(queued.playerId());
            }
        }
    }

    private String resolveMapId(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return config.defaultMapId().toLowerCase(Locale.ROOT);
        }
        return mapId.trim().toLowerCase(Locale.ROOT);
    }

    public record QueuedPlayer(UUID playerId, String mapId, long enqueuedAtMillis) {
    }
}
