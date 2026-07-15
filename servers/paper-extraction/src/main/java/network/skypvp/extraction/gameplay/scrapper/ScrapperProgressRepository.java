package network.skypvp.extraction.gameplay.scrapper;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.repository.ExtractionInventoryRepository;

/** Loads and upgrades durable scrapper tier progress from Postgres. */
public final class ScrapperProgressRepository {

    private final ExtractionInventoryRepository inventoryRepository;
    private final ScrapperTierConfigService tierConfig;
    private final ConcurrentHashMap<UUID, Integer> tierCache = new ConcurrentHashMap<>();

    public ScrapperProgressRepository(
            ExtractionInventoryRepository inventoryRepository,
            ScrapperTierConfigService tierConfig
    ) {
        // Nullable when Postgres is unavailable at boot; load/upgrade methods degrade safely.
        this.inventoryRepository = inventoryRepository;
        this.tierConfig = Objects.requireNonNull(tierConfig, "tierConfig");
    }

    public int cachedTier(UUID playerId) {
        if (playerId == null) {
            return tierConfig.defaultTier();
        }
        return tierCache.getOrDefault(playerId, tierConfig.defaultTier());
    }

    public CompletableFuture<Integer> loadTier(UUID playerId) {
        if (playerId == null || inventoryRepository == null) {
            return CompletableFuture.completedFuture(tierConfig.defaultTier());
        }
        return inventoryRepository.loadScrapperTier(playerId).thenApply(tier -> {
            int clamped = tierConfig.clampTier(tier);
            tierCache.put(playerId, clamped);
            return clamped;
        });
    }

    public void warmTier(UUID playerId) {
        loadTier(playerId);
    }

    public CompletableFuture<Boolean> tryUpgrade(UUID playerId, int expectedTier) {
        if (playerId == null || inventoryRepository == null) {
            return CompletableFuture.completedFuture(false);
        }
        return inventoryRepository.incrementScrapperTier(playerId, expectedTier, tierConfig.maxTier())
                .thenApply(success -> {
                    if (success) {
                        tierCache.put(playerId, tierConfig.clampTier(expectedTier + 1));
                    }
                    return success;
                });
    }

    public void evict(UUID playerId) {
        if (playerId != null) {
            tierCache.remove(playerId);
        }
    }
}
