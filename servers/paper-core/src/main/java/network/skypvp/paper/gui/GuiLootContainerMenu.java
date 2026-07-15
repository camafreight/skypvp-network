package network.skypvp.paper.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Container GUI with vanilla item movement in content slots (breach loot chests, player corpses,
 * bidirectional storage such as raid backpacks). Chrome buttons are framework-managed.
 */
public abstract class GuiLootContainerMenu implements GuiMenu {

    private Inventory bound;
    private GuiBulkStorageFrame chrome;

    protected abstract int[] lootSlots();

    protected abstract void buildChrome(GuiBulkStorageFrame frame, Player viewer);

    protected abstract void renderLoot(Player viewer, Inventory inventory);

    protected abstract void syncFromInventory(Inventory inventory);

    protected abstract void handleContainerClosed(Player viewer);

    @Override
    public final boolean allowsItemInteraction() {
        return true;
    }

    /** When {@code true}, players may shift-click and drag items from their inventory into content slots. */
    protected boolean allowsPlayerDeposit() {
        return false;
    }

    @Override
    public final boolean allowsDepositToTop() {
        return allowsPlayerDeposit();
    }

    @Override
    public long clickDebounceMillis() {
        return 0L;
    }

    @Override
    public final boolean allowsVanillaContentSlot(int rawSlot) {
        for (int lootSlot : lootSlots()) {
            if (lootSlot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    @Override
    public final void render(Player viewer, Inventory inventory) {
        this.bound = inventory;
        GuiBulkStorageFrame built = new GuiBulkStorageFrame(inventory.getSize());
        buildChrome(built, viewer);
        this.chrome = built;

        ItemStack filler = built.filler();
        if (filler != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (!allowsVanillaContentSlot(slot) && built.slot(slot) == null) {
                    inventory.setItem(slot, filler.clone());
                }
            }
        }
        for (var entry : built.slots().entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().icon());
        }
        renderLoot(viewer, inventory);
    }

    @Override
    public final void onClick(GuiClickContext context) {
        if (!context.isTopInventorySlot() || this.chrome == null) {
            return;
        }
        GuiBulkStorageFrame.Slot slot = this.chrome.slot(context.rawSlot());
        if (slot != null && slot.kind() == GuiBulkStorageFrame.Kind.BUTTON && slot.onClick() != null) {
            slot.onClick().accept(context);
        }
    }

    @Override
    public final void onClose(Player viewer) {
        if (this.bound != null) {
            syncFromInventory(this.bound);
        }
        handleContainerClosed(viewer);
    }

    protected final Inventory boundInventory() {
        return this.bound;
    }

    protected final void refresh(Player viewer) {
        if (this.bound != null) {
            render(viewer, this.bound);
        }
        viewer.updateInventory();
    }
}
