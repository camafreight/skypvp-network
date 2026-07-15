package network.skypvp.paper.inventory.vault;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class VaultHolder implements InventoryHolder {

    private final UUID playerId;
    private final boolean returnToNetworkMenu;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    private Inventory inventory;
    private int page;
    private int unlockedRows = VaultSlotAccess.defaultUnlockedRows();
    /** After the first paint, {@link VaultLayout#render} syncs inventory → holder before re-painting. */
    private boolean inventoryLive;
    /**
     * Set for the synchronous window of a scroll re-open: the replaced menu's close callback
     * must NOT persist — every scroll click would otherwise fire a concurrent bulk save,
     * which raced in the DB (duplicate-key aborts + deadlocks on extraction_inventory_slots).
     */
    private boolean scrollTransition;

    public VaultHolder(UUID playerId, boolean returnToNetworkMenu) {
        this.playerId = playerId;
        this.returnToNetworkMenu = returnToNetworkMenu;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public boolean returnToNetworkMenu() {
        return this.returnToNetworkMenu;
    }

    public int page() {
        return this.page;
    }

    public void setPage(int page) {
        this.page = Math.max(0, page);
    }

    public int unlockedRows() {
        return this.unlockedRows;
    }

    public void setUnlockedRows(int unlockedRows) {
        this.unlockedRows = VaultSlotAccess.clampUnlockedRows(unlockedRows);
    }

    public void resetInventorySync() {
        this.inventoryLive = false;
    }

    public void beginScrollTransition() {
        this.scrollTransition = true;
    }

    public void endScrollTransition() {
        this.scrollTransition = false;
    }

    public boolean inScrollTransition() {
        return this.scrollTransition;
    }

    public void bindInventory(Inventory inventory) {
        this.inventory = inventory;
        this.inventoryLive = false;
    }

    public boolean inventoryLive() {
        return this.inventoryLive;
    }

    public void markInventoryLive() {
        this.inventoryLive = true;
    }

    public void replaceAll(Map<Integer, ItemStack> source) {
        this.items.clear();
        if (source != null) {
            source.forEach((index, item) -> {
                if (item != null && !item.getType().isAir() && this.isDepositableVaultIndex(index)) {
                    this.items.put(index, item.clone());
                }
            });
        }
        this.page = Math.min(this.page, Math.max(0, this.totalPages() - 1));
    }

    public ItemStack get(int vaultIndex) {
        ItemStack item = this.items.get(vaultIndex);
        return item == null ? null : item.clone();
    }

    public void put(int vaultIndex, ItemStack item) {
        if (!this.isDepositableVaultIndex(vaultIndex)) {
            return;
        }
        if (item == null || item.getType().isAir()) {
            this.items.remove(vaultIndex);
            return;
        }
        this.items.put(vaultIndex, item.clone());
    }

    public void remove(int vaultIndex) {
        this.items.remove(vaultIndex);
    }

    public Map<Integer, ItemStack> snapshot() {
        Map<Integer, ItemStack> copy = new HashMap<>();
        int limit = this.depositableSlotLimit();
        this.items.forEach((index, item) -> {
            if (index < limit) {
                copy.put(index, item.clone());
            }
        });
        return copy;
    }

    public int highestOccupiedIndex() {
        return this.items.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    public int totalPages() {
        return VaultLayout.totalScrollPositions();
    }

    public int depositableSlotLimit() {
        return VaultSlotAccess.depositableLimit(this.unlockedRows);
    }

    public boolean isDepositableVaultIndex(int vaultIndex) {
        return VaultSlotAccess.isDepositableIndex(vaultIndex, this.unlockedRows);
    }

    public boolean isPurchasableVaultIndex(int vaultIndex) {
        return VaultSlotAccess.isPurchasableRow(VaultSlotAccess.rowForVaultIndex(vaultIndex), this.unlockedRows);
    }

    public int purchasableRow() {
        return this.unlockedRows;
    }

    public long purchasableRowPrice() {
        return VaultRowPricing.priceForRow(this.purchasableRow());
    }

    public int pageForVaultIndex(int vaultIndex) {
        return VaultSlotAccess.pageForVaultIndex(vaultIndex);
    }

    public int preferredOpenPage() {
        int firstEmpty = this.findFirstEmptyVaultSlot();
        if (firstEmpty < 0) {
            int purchasableRow = this.purchasableRow();
            if (purchasableRow < VaultSlotAccess.maxRows()) {
                return VaultSlotAccess.pageForVaultIndex(purchasableRow * VaultLayout.SLOTS_PER_ROW);
            }
            return Math.max(0, this.totalPages() - 1);
        }
        return this.pageForVaultIndex(firstEmpty);
    }

    public int findFirstEmptyVaultSlot() {
        int limit = this.depositableSlotLimit();
        for (int slot = 0; slot < limit; slot++) {
            if (!this.items.containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    public boolean hasEmptyContentSlotOnPage(Inventory inventory) {
        for (int slot : VaultLayout.CONTENT_SLOTS) {
            int vaultIndex = VaultLayout.vaultIndexForContentSlot(this.page(), VaultLayout.contentSlotIndex(slot));
            if (!this.isDepositableVaultIndex(vaultIndex)) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    public VaultHolder copyForSession() {
        VaultHolder copy = new VaultHolder(this.playerId, this.returnToNetworkMenu);
        copy.setUnlockedRows(this.unlockedRows);
        copy.setPage(this.page);
        copy.replaceAll(this.snapshot());
        return copy;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }
}
