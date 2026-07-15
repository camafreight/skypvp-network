package network.skypvp.paper.inventory.vault;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiCloseContext;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** General gear vault GUI via {@link GuiMenu}. */
public final class VaultMenu implements GuiMenu {

    private final VaultHolder holder;
    private final VaultGuiService service;
    private final CoreHotbarService hotbarService;
    private Inventory bound;
    /** Page this menu instance rendered; close-time syncs must use it, not holder.page(). */
    private int renderedPage;

    public VaultMenu(VaultHolder holder, VaultGuiService service, CoreHotbarService hotbarService) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.service = Objects.requireNonNull(service, "service");
        this.hotbarService = hotbarService;
    }

    public VaultHolder holder() {
        return holder;
    }

    @Override
    public Component title() {
        // The skin (overlay + scroll thumb) rides the inventory TITLE — GuiManager builds
        // the client inventory from this component, so the skinned title must live here.
        return VaultLayout.skinnedTitle(holder);
    }

    @Override
    public int size() {
        return VaultLayout.INVENTORY_SIZE;
    }

    @Override
    public void onPreOpen(Player viewer, Inventory inventory) {
        holder.bindInventory(inventory);
        this.bound = inventory;
        this.renderedPage = holder.page();
    }

    @Override
    public void render(Player viewer, Inventory inventory) {
        this.bound = inventory;
        this.renderedPage = holder.page();
        VaultLayout.render(inventory, holder);
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
        if (!VaultLayout.isContentSlot(rawSlot)) {
            return false;
        }
        int contentIndex = VaultLayout.contentSlotIndex(rawSlot);
        if (contentIndex < 0) {
            return false;
        }
        int vaultIndex = VaultLayout.vaultIndexForContentSlot(holder.page(), contentIndex);
        return holder.isDepositableVaultIndex(vaultIndex);
    }

    @Override
    public boolean isBlockedPlayerItem(ItemStack stack) {
        return hotbarService != null && hotbarService.isServerItem(stack);
    }

    @Override
    public long clickDebounceMillis() {
        return 0L;
    }

    @Override
    public void onClick(GuiClickContext context) {
        int rawSlot = context.rawSlot();
        Player player = context.viewer();
        if (rawSlot == VaultLayout.CLOSE_SLOT) {
            service.persistHolder(holder);
            context.close();
            return;
        }
        if (rawSlot == VaultLayout.BACK_SLOT) {
            service.handleBack(player, holder);
            return;
        }
        if (rawSlot == VaultLayout.SCROLL_UP_SLOT) {
            service.changePage(player, holder, -1);
            return;
        }
        if (rawSlot == VaultLayout.SCROLL_DOWN_SLOT) {
            service.changePage(player, holder, 1);
            return;
        }
        if (VaultLayout.isContentSlot(rawSlot)) {
            int contentIndex = VaultLayout.contentSlotIndex(rawSlot);
            if (contentIndex >= 0) {
                int vaultIndex = VaultLayout.vaultIndexForContentSlot(holder.page(), contentIndex);
                if (VaultLayout.isPurchasableSlotItem(context.currentItem())) {
                    service.promptRowPurchase(player, holder);
                    return;
                }
                if (!holder.isDepositableVaultIndex(vaultIndex)
                        || VaultLayout.isDecorativeSlotItem(context.currentItem())) {
                    return;
                }
            }
        }
    }

    @Override
    public void onShiftInsert(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || isBlockedPlayerItem(clicked)) {
            return;
        }
        ItemStack reference = clicked.clone();
        int deposited = service.depositShiftClickedStack(context.viewer(), holder, reference);
        if (deposited <= 0) {
            return;
        }
        int removed = removeDepositedStack(event.getClickedInventory(), event.getSlot(), deposited);
        if (removed < deposited) {
            service.revertDeposit(holder, reference, deposited - removed);
        }
        context.trackShiftDepositSweep(() -> {
            int swept = service.depositAllMatchingFromInventory(context.viewer(), holder, reference);
            if (swept > 0) {
                context.refresh();
            }
        });
        context.viewer().updateInventory();
        service.scheduleInventoryResync(context.viewer());
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
        // Scroll re-opens replace this menu with a fresh instance sharing the same holder;
        // persisting here would fire a bulk save per scroll click and those concurrent
        // writes deadlocked / duplicate-key-aborted in the DB. The real close (ESC, close
        // button, back button) still persists.
        if (holder.inScrollTransition()) {
            return;
        }
        service.persistHolder(holder);
    }

    private void syncFromInventory() {
        if (bound != null) {
            // renderedPage, not holder.page(): during a scroll re-open the holder has
            // already advanced while this (closing) inventory still shows the old page.
            VaultLayout.syncContentFromInventory(bound, holder, renderedPage);
        }
    }

    private static int removeDepositedStack(Inventory inventory, int slot, int amount) {
        if (inventory == null || slot < 0 || amount <= 0) {
            return 0;
        }
        ItemStack inSlot = inventory.getItem(slot);
        if (inSlot == null || inSlot.getType().isAir()) {
            return 0;
        }
        int remove = Math.min(amount, inSlot.getAmount());
        int remaining = inSlot.getAmount() - remove;
        if (remaining <= 0) {
            inventory.setItem(slot, null);
            return remove;
        }
        ItemStack updated = inSlot.clone();
        updated.setAmount(remaining);
        inventory.setItem(slot, updated);
        return remove;
    }
}
