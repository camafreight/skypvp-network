package network.skypvp.paper.gui;

import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.Event.Result;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Reusable bulk-storage GUI for oversized virtual stacks (material stash, future vault migration).
 * The {@link GuiBulkStorageSession} is the only source of truth — inventory slots are display-only.
 */
public abstract class GuiBulkStorageMenu implements GuiMenu {

    private Inventory bound;
    private GuiBulkStorageFrame chrome;

    protected abstract GuiBulkStorageSession session();

    /** Raw inventory indices that hold stored content. */
    protected abstract int[] contentSlots();

    protected abstract int contentIndex(int rawSlot);

    protected abstract boolean isContentSlot(int rawSlot);

    protected abstract void buildChrome(GuiBulkStorageFrame frame, Player viewer);

    /** Display copy placed into a content slot (may use amount=1 + lore). */
    protected abstract ItemStack displayStack(int contentIndex, ItemStack stored);

    protected abstract boolean acceptsDeposit(ItemStack stack);

    protected abstract boolean isBlockedServerItem(ItemStack stack);

    protected abstract int depositShiftClicked(Player player, ItemStack stack);

    /**
     * Deposits into a specific content slot when the player drags onto it.
     * Return {@code 0} to fall back to {@link #depositShiftClicked(Player, ItemStack)}.
     */
    protected int depositToContentIndex(Player player, int contentIndex, ItemStack stack) {
        return 0;
    }

    /**
     * Sweeps remaining matching stacks from the player inventory after a vanilla shift-double-click burst.
     * Default no-op; override for stash-like menus.
     */
    protected int depositAllMatchingShiftStacks(Player player, ItemStack reference) {
        return 0;
    }

    protected abstract void handleContentClick(GuiClickContext context, int contentIndex, int rawSlot);

    protected abstract void persist(Player viewer);

    @Override
    public final boolean allowsItemInteraction() {
        return true;
    }

    @Override
    public long clickDebounceMillis() {
        return 0L;
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
                if (!isContentSlot(slot) && built.slot(slot) == null) {
                    inventory.setItem(slot, filler.clone());
                }
            }
        }
        for (var entry : built.slots().entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().icon());
        }
        for (int index = 0; index < contentSlots().length; index++) {
            if (!session().isSlotUnlocked(index)) {
                continue;
            }
            int rawSlot = contentSlots()[index];
            ItemStack stored = session().get(index);
            inventory.setItem(rawSlot, stored == null ? null : displayStack(index, stored));
        }
    }

    @Override
    public final void onClick(GuiClickContext context) {
        int rawSlot = context.rawSlot();
        if (!context.isTopInventorySlot() || this.bound == null) {
            return;
        }
        if (isBlocked(context)) {
            return;
        }
        GuiBulkStorageFrame.Slot chromeSlot = this.chrome == null ? null : this.chrome.slot(rawSlot);
        if (chromeSlot != null) {
            if (chromeSlot.kind() == GuiBulkStorageFrame.Kind.BUTTON && chromeSlot.onClick() != null) {
                chromeSlot.onClick().accept(context);
            }
            return;
        }
        if (isContentSlot(rawSlot)) {
            int contentIndex = contentIndex(rawSlot);
            if (contentIndex >= 0 && session().isSlotUnlocked(contentIndex)) {
                handleContentClick(context, contentIndex, rawSlot);
            }
        }
    }

    @Override
    public final void onShiftInsert(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || isBlockedServerItem(clicked)) {
            return;
        }
        if (!acceptsDeposit(clicked)) {
            return;
        }
        ItemStack reference = clicked.clone();
        int deposited = depositShiftClicked(context.viewer(), reference);
        if (deposited <= 0) {
            return;
        }
        int removed = GuiClickInventory.consumeClickedStack(event, deposited);
        if (removed < deposited) {
            depositShiftClickedRevert(context.viewer(), reference, deposited - removed);
        }
        GuiShiftDepositBurst.track(context.viewer(), context.manager(), () -> {
            int swept = depositAllMatchingShiftStacks(context.viewer(), reference);
            if (swept > 0) {
                refresh(context.viewer());
            }
        });
        context.refresh();
    }

    /** Called when fewer items were removed from the player than were credited to storage (anti-dupe rollback). */
    protected void depositShiftClickedRevert(Player player, ItemStack referenceStack, int amount) {
    }

    /**
     * Handles dragging the cursor stack onto unlocked content slots. Vanilla drag is cancelled for
     * bulk-storage menus because display stacks are virtual, so the deposit is replayed manually:
     * the dragged (old) cursor stack goes into the first targeted slot that accepts it, falling
     * back to smart placement.
     *
     * <p>The whole operation runs one tick later as one atomic step (verify cursor → credit storage
     * → shrink cursor). Writing the cursor during the cancelled event would race the deny-path
     * cursor restore, and crediting storage before consuming the cursor would dupe if the player
     * disconnected in between.
     */
    public final void handleDragDeposit(GuiManager manager, Player player, InventoryDragEvent event) {
        if (manager == null || player == null || event == null || this.bound == null) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        java.util.List<Integer> targetIndices = new java.util.ArrayList<>();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < 0 || rawSlot >= topSize || !isContentSlot(rawSlot)) {
                continue;
            }
            int index = contentIndex(rawSlot);
            if (index >= 0 && session().isSlotUnlocked(index) && !targetIndices.contains(index)) {
                targetIndices.add(index);
            }
        }
        if (targetIndices.isEmpty()) {
            return;
        }
        ItemStack cursor = event.getOldCursor();
        if (cursor == null || cursor.getType().isAir() || isBlockedServerItem(cursor) || !acceptsDeposit(cursor)) {
            return;
        }
        ItemStack reference = cursor.clone();
        Inventory boundAtDrag = this.bound;
        manager.runNextTick(player, () -> {
            // Session must still be live: on quit/close the carried stack is returned to the
            // player, so crediting the holder afterwards would duplicate the items.
            if (!player.isOnline() || !boundAtDrag.equals(player.getOpenInventory().getTopInventory())) {
                return;
            }
            ItemStack cursorNow = player.getItemOnCursor();
            boolean stillHeld = cursorNow != null
                    && !cursorNow.getType().isAir()
                    && cursorNow.isSimilar(reference)
                    && cursorNow.getAmount() == reference.getAmount();
            if (!stillHeld) {
                return;
            }
            int moved = 0;
            for (int targetIndex : targetIndices) {
                moved = depositToContentIndex(player, targetIndex, reference);
                if (moved > 0) {
                    break;
                }
            }
            if (moved <= 0) {
                moved = depositShiftClicked(player, reference);
            }
            if (moved <= 0) {
                return;
            }
            int remaining = reference.getAmount() - moved;
            if (remaining <= 0) {
                player.setItemOnCursor(null);
            } else {
                ItemStack updated = reference.clone();
                updated.setAmount(remaining);
                player.setItemOnCursor(updated);
            }
            persist(player);
            refresh(player);
        });
    }

    @Override
    public final void onClose(Player viewer) {
        persist(viewer);
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

    protected static ClickType clickType(GuiClickContext context) {
        return context.event().getClick();
    }

    protected static boolean cursorEmpty(GuiClickContext context) {
        ItemStack cursor = context.event().getCursor();
        return cursor == null || cursor.getType().isAir();
    }

    protected static boolean isShiftClick(GuiClickContext context) {
        return context.event().isShiftClick();
    }

    private boolean isBlocked(GuiClickContext context) {
        if (isBlockedServerItem(context.currentItem()) || isBlockedServerItem(context.event().getCursor())) {
            context.viewer().updateInventory();
            return true;
        }
        return false;
    }
}
