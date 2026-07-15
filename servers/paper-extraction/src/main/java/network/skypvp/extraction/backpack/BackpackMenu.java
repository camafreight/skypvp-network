package network.skypvp.extraction.backpack;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiClickInventory;
import network.skypvp.paper.gui.GuiCloseContext;
import network.skypvp.paper.gui.GuiMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Scrollable backpack storage GUI — vault-style: 40 visible content slots, scroll rail in
 * column 8, custom skin + shared scroll thumb riding the inventory TITLE (which is why
 * scrolls re-open the menu instead of repainting in place). Contents live in the shared
 * {@link BackpackViewState} and persist into the worn item on close via {@link BackpackService}.
 */
final class BackpackMenu implements GuiMenu {

    private final BackpackService service;
    private final Player owner;
    private final BackpackViewState state;
    private Inventory bound;
    /** Scroll row this menu instance rendered; close-time syncs must use it, not state.scrollRow(). */
    private int renderedRow;

    BackpackMenu(BackpackService service, Player owner, BackpackViewState state) {
        this.service = Objects.requireNonNull(service, "service");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.state = Objects.requireNonNull(state, "state");
    }

    BackpackViewState state() {
        return state;
    }

    @Override
    public Component title() {
        // The skin (overlay + scroll thumb) rides the inventory TITLE — GuiManager builds
        // the client inventory from this component.
        return BackpackLayout.skinnedTitle(state.scrollRow(), state.maxScrollRow());
    }

    @Override
    public int size() {
        return BackpackLayout.INVENTORY_SIZE;
    }

    @Override
    public void onPreOpen(Player viewer, Inventory inventory) {
        state.bindInventory(inventory);
        this.bound = inventory;
        this.renderedRow = state.scrollRow();
    }

    @Override
    public void render(Player viewer, Inventory inventory) {
        this.bound = inventory;
        this.renderedRow = state.scrollRow();
        BackpackLayout.render(inventory, state);
    }

    @Override
    public boolean allowsItemInteraction() {
        return true;
    }

    @Override
    public boolean allowsDepositToTop() {
        return true;
    }

    @Override
    public boolean allowsVanillaContentSlot(int rawSlot) {
        int contentIndex = BackpackLayout.contentSlotIndex(rawSlot);
        if (contentIndex < 0) {
            return false;
        }
        return BackpackLayout.packIndexForContentSlot(state.scrollRow(), contentIndex) < state.capacity();
    }

    @Override
    public boolean isBlockedPlayerItem(ItemStack stack) {
        return service.isBackpack(stack);
    }

    @Override
    public long clickDebounceMillis() {
        return 0L;
    }

    @Override
    public void onClick(GuiClickContext context) {
        int rawSlot = context.rawSlot();
        if (rawSlot == BackpackLayout.CLOSE_SLOT) {
            context.close();
            return;
        }
        if (rawSlot == BackpackLayout.SKINS_SLOT) {
            service.openSkinMenu(context.viewer(), this);
            return;
        }
        if (rawSlot == BackpackLayout.SCROLL_UP_SLOT) {
            service.scroll(context.viewer(), this, -1);
            return;
        }
        if (rawSlot == BackpackLayout.SCROLL_DOWN_SLOT) {
            service.scroll(context.viewer(), this, 1);
        }
    }

    @Override
    public void onShiftInsert(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir() || isBlockedPlayerItem(moving)) {
            return;
        }
        syncFromInventory();
        int deposited = depositIntoState(moving);
        if (deposited <= 0) {
            return;
        }
        GuiClickInventory.consumeClickedStack(event, deposited);
        // Repaint from state; skipping the reset would let render() sync the stale
        // (pre-deposit) inventory back over the state and erase the insert.
        state.resetInventorySync();
        context.refresh();
    }

    /** Merges into similar stacks first, then empty slots — across ALL pack slots, not just visible ones. */
    private int depositIntoState(ItemStack moving) {
        int remaining = moving.getAmount();
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            boolean fillEmpty = pass == 1;
            for (int slot = 0; slot < state.capacity() && remaining > 0; slot++) {
                ItemStack existing = state.get(slot);
                if (existing == null) {
                    if (fillEmpty) {
                        ItemStack placed = moving.clone();
                        placed.setAmount(remaining);
                        state.put(slot, placed);
                        remaining = 0;
                    }
                    continue;
                }
                if (fillEmpty || !existing.isSimilar(moving)) {
                    continue;
                }
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, remaining);
                existing.setAmount(existing.getAmount() + move);
                state.put(slot, existing);
                remaining -= move;
            }
        }
        return moving.getAmount() - remaining;
    }

    @Override
    public void onPostClick(GuiClickContext context) {
        syncFromInventory();
    }

    @Override
    public void onPostDrag(Player viewer) {
        syncFromInventory();
    }

    @Override
    public void onPreClose(GuiCloseContext context) {
        syncFromInventory();
    }

    @Override
    public void onClose(Player viewer) {
        // Scroll re-opens replace this menu with a fresh instance sharing the same state;
        // the real close (ESC, close button, skins button) persists into the worn item.
        if (state.inScrollTransition()) {
            return;
        }
        service.handleMenuClosed(owner, state);
    }

    void syncFromInventory() {
        if (bound != null) {
            // renderedRow, not state.scrollRow(): during a scroll re-open the state has
            // already advanced while this (closing) inventory still shows the old rows.
            BackpackLayout.syncContentFromInventory(bound, state, renderedRow);
        }
    }
}
