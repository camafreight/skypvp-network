package network.skypvp.extraction.backpack;

import java.util.ArrayList;
import java.util.List;
import network.skypvp.extraction.item.BackpackSkins;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Live view state of one open backpack — the vault-holder analogue. Scroll re-opens replace
 * the {@link BackpackMenu} instance (the title carries the scroll thumb, and only an
 * open-screen packet resends the title) while this object carries the contents, scroll row,
 * and transition flags across instances. Persisted back into the worn item on real close.
 */
final class BackpackViewState {

    private final int tier;
    private final String skin;
    /** Positional pack slots; null = empty. Index maps to scroll geometry as row = i/8. */
    private final ItemStack[] contents;

    private Inventory inventory;
    private int scrollRow;
    /** After the first paint, {@link BackpackLayout#render} syncs inventory → state before re-painting. */
    private boolean inventoryLive;
    /** Set for the synchronous window of a scroll re-open: the replaced menu must not persist/announce. */
    private boolean scrollTransition;
    /** Real close, but quiet — set when the skins browser replaces the pack view. */
    private boolean silentClose;

    BackpackViewState(int tier, String skin, int capacity, List<ItemStack> stored) {
        this.tier = tier;
        this.skin = BackpackSkins.byId(skin).id();
        this.contents = new ItemStack[capacity];
        for (int slot = 0; slot < capacity && slot < stored.size(); slot++) {
            ItemStack item = stored.get(slot);
            if (item != null && !item.getType().isAir()) {
                this.contents[slot] = item.clone();
            }
        }
    }

    int tier() {
        return tier;
    }

    String skin() {
        return skin;
    }

    int capacity() {
        return contents.length;
    }

    ItemStack get(int packIndex) {
        ItemStack item = contents[packIndex];
        return item == null ? null : item.clone();
    }

    void put(int packIndex, ItemStack item) {
        contents[packIndex] = item == null || item.getType().isAir() ? null : item.clone();
    }

    /** Positional copy for persistence; carries nulls for empty slots. */
    List<ItemStack> contentsList() {
        List<ItemStack> list = new ArrayList<>(contents.length);
        for (ItemStack item : contents) {
            list.add(item == null ? null : item.clone());
        }
        return list;
    }

    int scrollRow() {
        return scrollRow;
    }

    void setScrollRow(int scrollRow) {
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow));
    }

    int maxScrollRow() {
        return BackpackLayout.maxScrollRow(capacity());
    }

    void bindInventory(Inventory inventory) {
        this.inventory = inventory;
        this.inventoryLive = false;
    }

    Inventory inventory() {
        return inventory;
    }

    boolean inventoryLive() {
        return inventoryLive;
    }

    void markInventoryLive() {
        this.inventoryLive = true;
    }

    void resetInventorySync() {
        this.inventoryLive = false;
    }

    void beginScrollTransition() {
        this.scrollTransition = true;
    }

    void endScrollTransition() {
        this.scrollTransition = false;
    }

    boolean inScrollTransition() {
        return scrollTransition;
    }

    void markSilentClose() {
        this.silentClose = true;
    }

    boolean silentClose() {
        return silentClose;
    }
}
