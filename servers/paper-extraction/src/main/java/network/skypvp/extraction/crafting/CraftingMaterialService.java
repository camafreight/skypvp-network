package network.skypvp.extraction.crafting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.ItemStackCodec;
import network.skypvp.extraction.stash.MaterialStashAccess;
import network.skypvp.extraction.stash.MaterialStashStackAmount;
import network.skypvp.extraction.stash.MaterialStashTierConfigService;
import network.skypvp.extraction.stash.MaterialStashTierDefinition;
import network.skypvp.paper.repository.ExtractionInventoryRepository;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Physical crafting material stash (PostgreSQL via {@link ExtractionInventoryRepository}).
 * Separate from the gear vault — stores {@code extraction:crafting_material} items with unlimited stack sizes.
 */
public final class CraftingMaterialService {

    private final PaperCorePlugin core;
    private final CraftingConfigService config;
    private final Logger logger;
    private final ExtractionInventoryRepository repository;
    private final CustomItemService itemService;
    private final MaterialStashTierConfigService tiers;
    private final AtomicLong saveSequence = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentHashMap<UUID, Map<Integer, ItemStack>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> tierCache = new ConcurrentHashMap<>();
    private network.skypvp.extraction.stash.MaterialStashGuiService stashGui;

    public CraftingMaterialService(
            PaperCorePlugin core,
            CraftingConfigService config,
            MaterialStashTierConfigService tiers,
            Logger logger
    ) {
        this.core = core;
        this.config = config;
        this.tiers = tiers;
        this.logger = logger;
        this.repository = core == null ? null : core.extractionInventoryRepository();
        this.itemService = core == null ? null : core.customItemService();
        if (repository == null) {
            logger.warning("[CraftingMaterials] Extraction inventory repository unavailable; stash will not persist.");
        }
    }

    public void bindStashGui(network.skypvp.extraction.stash.MaterialStashGuiService stashGui) {
        this.stashGui = stashGui;
    }

    public CraftingConfigService config() {
        return config;
    }

    public void evictPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        cache.remove(playerId);
        tierCache.remove(playerId);
        if (repository != null) {
            repository.evictContainer(playerId, MaterialStashConstants.CONTAINER);
        }
    }

    public int balance(UUID playerId, String materialId) {
        return MaterialStashHelper.countMaterial(loadCached(playerId), itemService, materialId);
    }

    public StashStatus stashStatus(UUID playerId) {
        MaterialStashTierDefinition tier = tierDefinition(playerId);
        int used = MaterialStashAccess.usedCapacity(loadCached(playerId));
        return new StashStatus(tier.name(), used, tier.maxCapacity(), MaterialStashAccess.capacityPercent(used, tier.maxCapacity()));
    }

    public record StashStatus(String tierName, int used, int max, int percent) {
    }

    public Map<String, Integer> balances(UUID playerId) {
        Map<String, Integer> totals = new HashMap<>();
        for (ItemStack stack : loadCached(playerId).values()) {
            CraftingMaterialItemFactory.materialIdOf(itemService, stack).ifPresent(id ->
                    totals.merge(id, MaterialStashStackAmount.read(stack), Integer::sum));
        }
        return Map.copyOf(totals);
    }

    public Map<Integer, ItemStack> snapshotSlots(UUID playerId) {
        return MaterialStashHelper.copy(loadCached(playerId));
    }

    public void restoreSlots(UUID playerId, Map<Integer, ItemStack> slots) {
        saveSlots(playerId, MaterialStashHelper.copy(slots));
    }

    public void ensureStarterKit(UUID playerId) {
        if (playerId == null || !balances(playerId).isEmpty()) {
            return;
        }
        grant(playerId, "cloth_scrap", 24);
        grant(playerId, "fiber_bundle", 12);
        grant(playerId, "metal_shards", 16);
        grant(playerId, "field_suture", 6);
        grant(playerId, "stim_compound", 4);
        grant(playerId, "capacitor_cell", 6);
    }

    public void grant(UUID playerId, String materialId, int amount) {
        if (playerId == null || materialId == null || amount <= 0 || itemService == null) {
            return;
        }
        ItemStack created = CraftingMaterialItemFactory.create(itemService, config, materialId, amount);
        if (created == null) {
            return;
        }
        depositStack(playerId, created);
    }

    public boolean depositStack(UUID playerId, ItemStack stack) {
        if (playerId == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        Map<Integer, ItemStack> slots = MaterialStashHelper.copy(loadCached(playerId));
        MaterialStashTierDefinition tierDef = tierDefinition(playerId);
        if (!MaterialStashHelper.deposit(slots, itemService, stack, tierDef.maxSlots(), tierDef.maxCapacity())) {
            return false;
        }
        saveSlots(playerId, slots);
        return true;
    }

    public boolean trySpend(UUID playerId, Map<String, Integer> costs) {
        if (playerId == null || costs == null || costs.isEmpty()) {
            return true;
        }
        Map<Integer, ItemStack> slots = MaterialStashHelper.copy(loadCached(playerId));
        if (!MaterialStashHelper.withdraw(slots, itemService, costs)) {
            return false;
        }
        saveSlots(playerId, slots);
        return true;
    }

    public Map<String, Integer> toCostMap(BlueprintDefinition blueprint) {
        Map<String, Integer> costs = new HashMap<>();
        for (BlueprintDefinition.MaterialCost cost : blueprint.materials()) {
            costs.merge(cost.materialId(), cost.amount(), Integer::sum);
        }
        return costs;
    }

    public CompletableFuture<Map<Integer, ItemStack>> loadSlotsAsync(UUID playerId) {
        if (repository == null || playerId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return repository.loadContainer(playerId, MaterialStashConstants.CONTAINER)
                .thenApply(this::decodeSlots);
    }

    public void openStashGui(Player player, Runnable onBack) {
        if (player == null || stashGui == null) {
            return;
        }
        stashGui.open(player, onBack);
    }

    public void saveSlots(UUID playerId, Map<Integer, ItemStack> slots) {
        Map<Integer, ItemStack> normalized = MaterialStashHelper.copy(slots);
        cache.put(playerId, normalized);
        if (repository == null) {
            return;
        }
        Map<Integer, String> encoded = new HashMap<>();
        normalized.forEach((index, item) -> {
            if (item != null && !item.getType().isAir()) {
                encoded.put(index, ItemStackCodec.encode(item));
            }
        });
        long revision = saveSequence.incrementAndGet();
        String checksum = checksum(encoded);
        repository.saveContainerBulk(playerId, MaterialStashConstants.CONTAINER, encoded, revision, checksum);
    }

    private MaterialStashTierDefinition tierDefinition(UUID playerId) {
        if (tiers == null) {
            return new MaterialStashTierDefinition(1, "Stash", Integer.MAX_VALUE, MaterialStashConstants.MAX_SLOTS, 0L, 0L);
        }
        return tiers.tier(loadCachedTier(playerId));
    }

    private int loadCachedTier(UUID playerId) {
        if (playerId == null) {
            return 1;
        }
        return tierCache.computeIfAbsent(playerId, id -> {
            if (repository == null || tiers == null) {
                return tiers == null ? 1 : tiers.defaultTier();
            }
            try {
                return tiers.clampTier(repository.loadMaterialStashTier(id).join());
            } catch (Exception exception) {
                logger.warning("[CraftingMaterials] Failed to load stash tier for " + id + ": " + exception.getMessage());
                return tiers.defaultTier();
            }
        });
    }

    Map<Integer, ItemStack> loadCached(UUID playerId) {
        return cache.computeIfAbsent(playerId, id -> {
            if (repository == null) {
                return new HashMap<>();
            }
            try {
                return decodeSlots(repository.loadContainer(id, MaterialStashConstants.CONTAINER).join());
            } catch (Exception exception) {
                logger.warning("[CraftingMaterials] Failed to load stash for " + id + ": " + exception.getMessage());
                return new HashMap<>();
            }
        });
    }

    private Map<Integer, ItemStack> decodeSlots(Map<Integer, String> payloads) {
        Map<Integer, ItemStack> decoded = new HashMap<>();
        if (payloads == null) {
            return decoded;
        }
        payloads.forEach((index, payload) -> {
            try {
                ItemStack item = ItemStackCodec.decode(payload);
                if (item != null && !item.getType().isAir() && MaterialStashHelper.isCraftingMaterial(itemService, item)) {
                    if (itemService != null) {
                        // reconcile (not just refreshPresentation): stash stacks are stored
                        // fully serialized, so ones minted before a material's icon/model
                        // changed keep the OLD look forever unless modernized on decode.
                        item = itemService.reconcile(item);
                    }
                    decoded.put(index, item);
                }
            } catch (RuntimeException ignored) {
            }
        });
        return decoded;
    }

    private String checksum(Map<Integer, String> slots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            slots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    digest.update((entry.getKey() + ":" + entry.getValue()).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            return "";
        }
    }
}
