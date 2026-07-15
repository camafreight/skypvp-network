package network.skypvp.extraction.stash;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.crafting.MaterialStashConstants;
import network.skypvp.paper.gui.GuiBulkStorageSession;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** In-memory state for one player's material stash GUI session. */
public final class MaterialStashHolder implements InventoryHolder, GuiBulkStorageSession {

    private final UUID playerId;
    private final Runnable onBack;
    private final Map<Integer, ItemStack> slots = new java.util.HashMap<>();
    private Inventory inventory;
    private int tier = 1;
    private int unlockedSlots = MaterialStashConstants.MAX_SLOTS;
    private int maxCapacity = Integer.MAX_VALUE;
    private String tierName = "Stash";
    private MaterialStashTierDefinition nextTier;

    public MaterialStashHolder(UUID playerId, Runnable onBack) {
        this.playerId = playerId;
        this.onBack = onBack;
    }

    public UUID playerId() {
        return playerId;
    }

    public Runnable onBack() {
        return onBack;
    }

    public int tier() {
        return tier;
    }

    public int unlockedSlots() {
        return unlockedSlots;
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    public String tierName() {
        return tierName;
    }

    public MaterialStashTierDefinition nextTier() {
        return nextTier;
    }

    public int usedCapacity() {
        return MaterialStashAccess.usedCapacity(slots);
    }

    public int capacityPercent() {
        return MaterialStashAccess.capacityPercent(usedCapacity(), maxCapacity);
    }

    public void applyTier(MaterialStashTierConfigService config, int rawTier) {
        if (config == null) {
            return;
        }
        this.tier = config.clampTier(rawTier);
        MaterialStashTierDefinition current = config.tier(this.tier);
        this.unlockedSlots = Math.min(MaterialStashConstants.MAX_SLOTS, current.maxSlots());
        this.maxCapacity = current.maxCapacity();
        this.tierName = current.name();
        this.nextTier = config.nextTier(this.tier).orElse(null);
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public boolean isSlotUnlocked(int index) {
        return index >= 0 && index < unlockedSlots;
    }

    public void bindInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void replaceAll(Map<Integer, ItemStack> source) {
        slots.clear();
        if (source == null) {
            return;
        }
        source.forEach((index, item) -> {
            if (index >= 0 && index < MaterialStashConstants.MAX_SLOTS && item != null && !item.getType().isAir()) {
                slots.put(index, MaterialStashStackAmount.normalize(item));
            }
        });
    }

    public ItemStack get(int index) {
        ItemStack item = slots.get(index);
        if (item == null) {
            return null;
        }
        return MaterialStashStackAmount.withAmount(item, MaterialStashStackAmount.read(item));
    }

    public void put(int index, ItemStack item) {
        if (index < 0 || index >= MaterialStashConstants.MAX_SLOTS || !isSlotUnlocked(index)) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            slots.remove(index);
            return;
        }
        slots.put(index, MaterialStashStackAmount.normalize(item));
    }

    public void remove(int index) {
        slots.remove(index);
    }

    public Map<Integer, ItemStack> snapshot() {
        Map<Integer, ItemStack> copy = new java.util.HashMap<>();
        slots.forEach((index, item) -> copy.put(index, item.clone()));
        return copy;
    }

    public MaterialStashHolder copyForSession() {
        MaterialStashHolder copy = new MaterialStashHolder(playerId, onBack);
        copy.tier = tier;
        copy.unlockedSlots = unlockedSlots;
        copy.maxCapacity = maxCapacity;
        copy.tierName = tierName;
        copy.nextTier = nextTier;
        copy.replaceAll(snapshot());
        return copy;
    }

    public int findMergeIndex(CustomItemService service, ItemStack stack) {
        if (service == null || stack == null) {
            return -1;
        }
        Optional<String> targetId = network.skypvp.extraction.crafting.CraftingMaterialItemFactory.materialIdOf(service, stack);
        if (targetId.isEmpty()) {
            return -1;
        }
        for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
            if (!isSlotUnlocked(entry.getKey())) {
                continue;
            }
            ItemStack stored = entry.getValue();
            if (stored == null || stored.getType().isAir()) {
                continue;
            }
            if (targetId.equals(network.skypvp.extraction.crafting.CraftingMaterialItemFactory.materialIdOf(service, stored))) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public int findEmptyIndex() {
        for (int index = 0; index < unlockedSlots; index++) {
            if (!slots.containsKey(index)) {
                return index;
            }
        }
        return -1;
    }
}
