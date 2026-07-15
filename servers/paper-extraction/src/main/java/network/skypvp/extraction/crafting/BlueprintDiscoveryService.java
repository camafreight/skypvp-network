package network.skypvp.extraction.crafting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import network.skypvp.paper.repository.ExtractionCraftingRepository;

/** Tracks which blueprint recipes a player has discovered (PostgreSQL-backed, pod-safe). */
public final class BlueprintDiscoveryService {

    private final ExtractionCraftingRepository repository;
    private final Logger logger;
    private volatile List<BlueprintDefinition> catalog;
    private final ConcurrentHashMap<UUID, Set<String>> discovered = new ConcurrentHashMap<>();

    public BlueprintDiscoveryService(
            ExtractionCraftingRepository repository,
            Logger logger,
            CraftingConfigService config
    ) {
        this.repository = repository;
        this.logger = logger;
        this.catalog = config.blueprints();
        if (repository == null || !repository.isReady()) {
            logger.warning("[BlueprintDiscovery] Database repository unavailable; discoveries will not persist across pods.");
        }
    }

    public void refreshCatalog(List<BlueprintDefinition> next) {
        if (next != null) {
            this.catalog = List.copyOf(next);
        }
    }

    public void evictPlayer(UUID playerId) {
        if (playerId != null) {
            discovered.remove(playerId);
        }
    }

    public List<BlueprintDefinition> discoveredBlueprints(UUID playerId) {
        ensurePlayer(playerId);
        Set<String> ids = discovered.getOrDefault(playerId, Set.of());
        return catalog.stream().filter(bp -> ids.contains(bp.id())).toList();
    }

    public boolean isDiscovered(UUID playerId, String blueprintId) {
        ensurePlayer(playerId);
        return discovered.getOrDefault(playerId, Set.of()).contains(blueprintId);
    }

    public void discover(UUID playerId, String blueprintId) {
        if (playerId == null || blueprintId == null || blueprintId.isBlank()) {
            return;
        }
        String normalized = blueprintId.trim();
        AtomicBoolean changed = new AtomicBoolean(false);
        discovered.compute(playerId, (id, current) -> {
            Set<String> set = current == null ? new HashSet<>() : new HashSet<>(current);
            if (set.add(normalized)) {
                changed.set(true);
            }
            return set;
        });
        if (changed.get()) {
            persistDiscoveries(playerId, Set.of(normalized));
        }
    }

    private void ensurePlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        discovered.computeIfAbsent(playerId, this::loadFromDatabase);
        ensureStarter(playerId);
    }

    private Set<String> loadFromDatabase(UUID playerId) {
        if (repository == null || !repository.isReady()) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(repository.loadDiscoveredBlueprints(playerId));
        } catch (RuntimeException exception) {
            logger.warning("[BlueprintDiscovery] Failed to load for " + playerId + ": " + exception.getMessage());
            return new HashSet<>();
        }
    }

    private void ensureStarter(UUID playerId) {
        Set<String> starters = starterBlueprintIds();
        Set<String> missing = new HashSet<>();
        discovered.compute(playerId, (id, current) -> {
            Set<String> set = current == null ? new HashSet<>() : new HashSet<>(current);
            for (String starter : starters) {
                if (set.add(starter)) {
                    missing.add(starter);
                }
            }
            return set;
        });
        if (!missing.isEmpty()) {
            persistDiscoveries(playerId, missing);
        }
    }

    private void persistDiscoveries(UUID playerId, Set<String> blueprintIds) {
        if (repository == null || !repository.isReady() || blueprintIds.isEmpty()) {
            return;
        }
        try {
            repository.insertDiscoveredBlueprints(playerId, blueprintIds);
        } catch (RuntimeException exception) {
            logger.warning("[BlueprintDiscovery] Failed to persist for " + playerId + ": " + exception.getMessage());
        }
    }

    private Set<String> starterBlueprintIds() {
        Set<String> starters = new HashSet<>();
        for (BlueprintDefinition blueprint : catalog) {
            if (blueprint.starterDiscovered()) {
                starters.add(blueprint.id());
            }
        }
        return starters;
    }
}
