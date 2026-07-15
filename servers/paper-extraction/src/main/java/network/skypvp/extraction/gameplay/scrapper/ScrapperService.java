package network.skypvp.extraction.gameplay.scrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Passive material pickup while actively raiding. Materials buffer quietly until collected at the extraction hub
 * scrapper NPC or {@code /scrapper}. Tier, cap, and loot pools come from {@link ScrapperTierConfigService}.
 */
public final class ScrapperService {

    public record BufferedMaterial(String materialId, int amount) {
    }

    public record WithdrawResult(int movedUnits, int leftoverUnits, boolean inventoryFull) {
    }

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final BreachEngine engine;
    private final ScrapperTierConfigService tierConfig;
    private final ScrapperProgressRepository progressRepository;
    private final Map<UUID, AtomicInteger> sessionCollected = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> sessionBuffer = new ConcurrentHashMap<>();

    public ScrapperService(
            PaperCorePlugin core,
            CraftingConfigService craftingConfig,
            BreachEngine engine,
            ScrapperTierConfigService tierConfig,
            ScrapperProgressRepository progressRepository
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tierConfig = Objects.requireNonNull(tierConfig, "tierConfig");
        this.progressRepository = Objects.requireNonNull(progressRepository, "progressRepository");
    }

    public void warmPlayer(Player player) {
        if (player != null) {
            progressRepository.warmTier(player.getUniqueId());
        }
    }

    public void refreshPlayerTier(UUID playerId) {
        if (playerId != null) {
            progressRepository.loadTier(playerId);
        }
    }

    public boolean isActiveInBreach(Player player) {
        if (player == null) {
            return false;
        }
        return engine.instanceFor(player)
                .filter(instance -> instance.containsPlayer(player.getUniqueId()))
                .filter(instance -> !instance.isEliminated(player.getUniqueId()))
                .filter(instance -> !instance.hasExtracted(player.getUniqueId()))
                .isPresent();
    }

    public int sessionCollected(Player player) {
        if (player == null) {
            return 0;
        }
        return sessionCollected.getOrDefault(player.getUniqueId(), new AtomicInteger()).get();
    }

    public int sessionBuffered(Player player) {
        if (player == null) {
            return 0;
        }
        return bufferFor(player.getUniqueId()).values().stream().mapToInt(Integer::intValue).sum();
    }

    public int sessionCap(Player player) {
        if (player == null) {
            return tierConfig.tier(tierConfig.defaultTier()).sessionCap();
        }
        return tierConfig.tier(playerTier(player)).sessionCap();
    }

    public int remainingCapacity(Player player) {
        return Math.max(0, sessionCap(player) - sessionCollected(player));
    }

    public int playerTier(Player player) {
        if (player == null) {
            return tierConfig.defaultTier();
        }
        return progressRepository.cachedTier(player.getUniqueId());
    }

    public String tierName(Player player) {
        return tierConfig.tier(playerTier(player)).name();
    }

    public List<BufferedMaterial> bufferedMaterials(Player player) {
        if (player == null) {
            return List.of();
        }
        List<BufferedMaterial> entries = new ArrayList<>();
        bufferFor(player.getUniqueId()).forEach((materialId, amount) -> {
            if (amount > 0) {
                entries.add(new BufferedMaterial(materialId, amount));
            }
        });
        return List.copyOf(entries);
    }

    /** Called periodically for each online player while in an active breach. */
    public void tickPassiveCollection(Player player) {
        if (player == null || !player.isOnline() || remainingCapacity(player) <= 0 || !isActiveInBreach(player)) {
            return;
        }
        collect(player, 1);
    }

    /** Adds materials to the scrapper buffer during an active breach session. */
    public int collect(Player player, int amount) {
        if (player == null || amount <= 0 || !isActiveInBreach(player)) {
            return 0;
        }
        AtomicInteger counter = sessionCollected.computeIfAbsent(player.getUniqueId(), ignored -> new AtomicInteger());
        int allowed = Math.min(amount, remainingCapacity(player));
        if (allowed <= 0) {
            return 0;
        }
        String materialId = rollMaterialId(player);
        if (materialId == null || materialId.isBlank()) {
            return 0;
        }
        Map<String, Integer> buffer = bufferFor(player.getUniqueId());
        buffer.merge(materialId, allowed, Integer::sum);
        counter.addAndGet(allowed);
        return allowed;
    }

    public WithdrawResult withdrawAllToInventory(Player player) {
        if (player == null) {
            return new WithdrawResult(0, 0, false);
        }
        Map<String, Integer> buffer = bufferFor(player.getUniqueId());
        if (buffer.isEmpty()) {
            return new WithdrawResult(0, 0, false);
        }
        int moved = 0;
        int leftover = 0;
        boolean inventoryFull = false;
        for (String materialId : List.copyOf(buffer.keySet())) {
            int amount = buffer.getOrDefault(materialId, 0);
            while (amount > 0) {
                int stackSize = Math.min(64, amount);
                ItemStack stack = CraftingMaterialItemFactory.create(
                        core.customItemService(), craftingConfig, materialId, stackSize);
                if (stack == null || stack.getType().isAir()) {
                    buffer.remove(materialId);
                    break;
                }
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                if (overflow.isEmpty()) {
                    moved += stackSize;
                    amount -= stackSize;
                    continue;
                }
                int returned = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
                int placed = stackSize - returned;
                moved += Math.max(0, placed);
                amount -= Math.max(0, placed);
                inventoryFull = true;
                break;
            }
            if (amount <= 0) {
                buffer.remove(materialId);
            } else {
                buffer.put(materialId, amount);
                leftover += amount;
            }
        }
        if (buffer.isEmpty()) {
            sessionBuffer.remove(player.getUniqueId());
        }
        return new WithdrawResult(moved, leftover, inventoryFull);
    }

    /** Clears the per-raid collection counter when a raid session ends; buffered materials are kept for hub pickup. */
    public void resetRaidProgress(Player player) {
        if (player != null) {
            sessionCollected.remove(player.getUniqueId());
        }
    }

    public boolean hasBufferedMaterials(Player player) {
        return sessionBuffered(player) > 0;
    }

    private Map<String, Integer> bufferFor(UUID playerId) {
        return sessionBuffer.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
    }

    private String rollMaterialId(Player player) {
        List<String> pool = tierConfig.tier(playerTier(player)).materials();
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
