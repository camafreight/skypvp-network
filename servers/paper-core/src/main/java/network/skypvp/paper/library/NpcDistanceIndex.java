package network.skypvp.paper.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Chunk broad-phase index for NPC / block-prop distance visibility checks.
 * Narrow-phase still uses the same 48-block radius as the legacy brute-force loop.
 */
final class NpcDistanceIndex {

    static final double VISIBILITY_RADIUS_SQ = 2304.0;
    private static final int CHUNK_SEARCH_RADIUS = 4;

    private record IndexedTrackable(String visibilityId, Entity entity, int chunkX, int chunkZ) {
    }

    private final Map<UUID, Map<Long, List<IndexedTrackable>>> byWorldChunk = new ConcurrentHashMap<>();
    private final Map<String, IndexedTrackable> byEntryKey = new ConcurrentHashMap<>();

    void register(String entryKey, String visibilityId, Location anchor, Entity entity) {
        if (entryKey == null || entryKey.isBlank() || anchor == null || anchor.getWorld() == null || entity == null) {
            return;
        }
        unregister(entryKey);
        int chunkX = anchor.getBlockX() >> 4;
        int chunkZ = anchor.getBlockZ() >> 4;
        IndexedTrackable trackable = new IndexedTrackable(
                visibilityId == null || visibilityId.isBlank() ? entryKey : visibilityId,
                entity,
                chunkX,
                chunkZ
        );
        byEntryKey.put(entryKey, trackable);
        byWorldChunk
                .computeIfAbsent(anchor.getWorld().getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                .add(trackable);
    }

    void unregister(String entryKey) {
        if (entryKey == null || entryKey.isBlank()) {
            return;
        }
        IndexedTrackable removed = byEntryKey.remove(entryKey);
        if (removed == null) {
            return;
        }
        removeFromChunkBuckets(removed);
    }

    void unregisterNpc(String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return;
        }
        String normalized = npcId.toLowerCase();
        unregister(normalized);
        unregister(normalized + ":interaction");
        unregister(normalized + ":display");
    }

    void unregisterBlockProp(String propId) {
        if (propId == null || propId.isBlank()) {
            return;
        }
        String normalized = propId.toLowerCase();
        unregister(normalized + ":block_display");
        unregister(normalized + ":block_interaction");
    }

    void registerBlockProp(String propId, Location anchor, Entity display, Entity interaction) {
        if (propId == null || propId.isBlank()) {
            return;
        }
        String normalized = propId.toLowerCase();
        if (display != null) {
            register(normalized + ":block_display", normalized, anchor, display);
        }
        if (interaction != null) {
            register(normalized + ":block_interaction", normalized, interaction.getLocation(), interaction);
        }
    }

    void clearWorld(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        byWorldChunk.remove(worldId);
        byEntryKey.entrySet().removeIf(entry -> {
            Entity entity = entry.getValue().entity();
            return entity != null && entity.getWorld() != null && entity.getWorld().getUID().equals(worldId);
        });
    }

    void rebuildWorld(World world, Map<String, ? extends Entity> interactions, Map<String, ? extends Entity> displays) {
        if (world == null) {
            return;
        }
        clearWorld(world);
        for (Map.Entry<String, ? extends Entity> entry : interactions.entrySet()) {
            Entity entity = entry.getValue();
            if (entity == null || !entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                continue;
            }
            register(entry.getKey() + ":interaction", entry.getKey(), entity.getLocation(), entity);
        }
        for (Map.Entry<String, ? extends Entity> entry : displays.entrySet()) {
            Entity entity = entry.getValue();
            if (entity == null || !entity.isValid() || entity.isDead() || !world.equals(entity.getWorld())) {
                continue;
            }
            register(entry.getKey() + ":display", entry.getKey(), entity.getLocation(), entity);
        }
    }

    void forEachNear(Player player, Location playerLocation, World playerWorld, BiConsumer<String, Entity> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (player == null || playerLocation == null || playerWorld == null) {
            return;
        }
        Map<Long, List<IndexedTrackable>> worldBuckets = byWorldChunk.get(playerWorld.getUID());
        if (worldBuckets == null || worldBuckets.isEmpty()) {
            return;
        }
        int playerChunkX = playerLocation.getBlockX() >> 4;
        int playerChunkZ = playerLocation.getBlockZ() >> 4;
        for (int dx = -CHUNK_SEARCH_RADIUS; dx <= CHUNK_SEARCH_RADIUS; dx++) {
            for (int dz = -CHUNK_SEARCH_RADIUS; dz <= CHUNK_SEARCH_RADIUS; dz++) {
                List<IndexedTrackable> trackables = worldBuckets.get(chunkKey(playerChunkX + dx, playerChunkZ + dz));
                if (trackables == null || trackables.isEmpty()) {
                    continue;
                }
                for (IndexedTrackable trackable : trackables) {
                    Entity entity = trackable.entity();
                    if (entity == null || !entity.isValid() || entity.isDead() || !entity.getWorld().equals(playerWorld)) {
                        continue;
                    }
                    if (playerLocation.distanceSquared(entity.getLocation()) > VISIBILITY_RADIUS_SQ) {
                        continue;
                    }
                    consumer.accept(trackable.visibilityId(), entity);
                }
            }
        }
    }

    private void removeFromChunkBuckets(IndexedTrackable trackable) {
        Entity entity = trackable.entity();
        if (entity == null || entity.getWorld() == null) {
            return;
        }
        Map<Long, List<IndexedTrackable>> worldBuckets = byWorldChunk.get(entity.getWorld().getUID());
        if (worldBuckets == null) {
            return;
        }
        List<IndexedTrackable> bucket = worldBuckets.get(chunkKey(trackable.chunkX(), trackable.chunkZ()));
        if (bucket == null) {
            return;
        }
        bucket.removeIf(existing -> existing.entity().equals(entity));
        if (bucket.isEmpty()) {
            worldBuckets.remove(chunkKey(trackable.chunkX(), trackable.chunkZ()));
        }
        if (worldBuckets.isEmpty()) {
            byWorldChunk.remove(entity.getWorld().getUID());
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
