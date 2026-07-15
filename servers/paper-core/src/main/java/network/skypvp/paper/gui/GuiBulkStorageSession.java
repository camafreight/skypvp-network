package network.skypvp.paper.gui;

import org.bukkit.inventory.ItemStack;

/** Mutable slot backing store for {@link GuiBulkStorageMenu}. Holder is the source of truth — never the GUI inventory. */
public interface GuiBulkStorageSession {

    ItemStack get(int contentIndex);

    void put(int contentIndex, ItemStack stack);

    void remove(int contentIndex);

    boolean isSlotUnlocked(int contentIndex);
}
