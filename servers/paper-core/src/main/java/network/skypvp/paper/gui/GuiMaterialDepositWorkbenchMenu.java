package network.skypvp.paper.gui;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Multi-slot material deposit workbench (blueprint crafting, medic benches, etc.). Chrome buttons use
 * {@link GuiWorkstationFrame}; deposit slots are player-editable and validated through {@link GuiDepositRequirements}.
 */
public abstract class GuiMaterialDepositWorkbenchMenu implements GuiMenu {

    private Inventory bound;
    private GuiWorkstationFrame frame;

    protected abstract CustomItemService customItemService();

    protected abstract List<GuiDepositSlot> depositSlots();

    /** Empty-slot placeholder for {@code depositSlot} when nothing is deposited yet. */
    protected abstract ItemStack depositPlaceholder(GuiDepositSlot depositSlot);

    protected abstract void buildFrame(GuiWorkstationFrame frame, Player viewer, Inventory inventory);

    /** Called after deposits change so subclasses can refresh craft/action buttons. */
    protected void onDepositsChanged(Player viewer) {
    }

    protected Component depositRejectedMessage(GuiDepositSlot slot) {
        return Component.text("That material doesn't match this slot.", NamedTextColor.RED);
    }

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
        Set<Integer> depositSlotIndices = depositSlotSet();
        GuiWorkstationFrame built = new GuiWorkstationFrame(inventory.getSize());
        buildFrame(built, viewer, inventory);
        this.frame = built;

        ItemStack filler = built.filler();
        if (filler != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (depositSlotIndices.contains(slot) || built.slot(slot) != null) {
                    continue;
                }
                inventory.setItem(slot, filler.clone());
            }
        }
        for (Map.Entry<Integer, GuiWorkstationFrame.Slot> entry : built.slots().entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().icon());
        }
        renderDepositPlaceholders(viewer, inventory);
    }

    @Override
    public final void onClick(GuiClickContext context) {
        if (this.frame == null || this.bound == null) {
            return;
        }
        int rawSlot = context.rawSlot();
        GuiWorkstationFrame.Slot chrome = this.frame.slot(rawSlot);
        if (chrome != null && chrome.kind() == GuiWorkstationFrame.Kind.BUTTON && chrome.onClick() != null) {
            chrome.onClick().accept(context);
            return;
        }
        GuiDepositSlot deposit = depositAt(rawSlot);
        if (deposit != null) {
            handleDepositClick(context.viewer(), context.event(), deposit);
            context.viewer().updateInventory();
            onDepositsChanged(context.viewer());
            context.refresh();
        }
    }

    @Override
    public final void onShiftInsert(GuiClickContext context) {
        ItemStack clicked = context.event().getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || this.bound == null) {
            return;
        }
        CustomItemService service = customItemService();
        for (GuiDepositSlot slot : depositSlots()) {
            if (!GuiDepositRequirements.accepts(service, clicked, slot.requirement())) {
                continue;
            }
            ItemStack existing = this.bound.getItem(slot.slot());
            if (!GuiDepositRequirements.accepts(service, existing, slot.requirement())) {
                ItemStack placed = clicked.clone();
                placed.setAmount(Math.min(placed.getAmount(), slot.requirement().minimumAmount()));
                this.bound.setItem(slot.slot(), placed);
                decrementClicked(context.event(), placed.getAmount());
            } else if (existing.getAmount() < slot.requirement().minimumAmount()) {
                int space = slot.requirement().minimumAmount() - existing.getAmount();
                int move = Math.min(space, clicked.getAmount());
                ItemStack updated = existing.clone();
                updated.setAmount(existing.getAmount() + move);
                this.bound.setItem(slot.slot(), updated);
                decrementClicked(context.event(), move);
            }
            onDepositsChanged(context.viewer());
            context.refresh();
            return;
        }
    }

    @Override
    public final void onClose(Player viewer) {
        if (this.bound == null) {
            return;
        }
        GuiDepositRequirements.returnDeposits(customItemService(), viewer, this.bound, depositSlots());
    }

    protected final boolean requirementsMet(Inventory inventory) {
        return GuiDepositRequirements.met(customItemService(), inventory, depositSlots());
    }

    protected final void consumeDeposits(Inventory inventory) {
        GuiDepositRequirements.consume(customItemService(), inventory, depositSlots());
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

    private void renderDepositPlaceholders(Player viewer, Inventory inventory) {
        CustomItemService service = customItemService();
        for (GuiDepositSlot slot : depositSlots()) {
            ItemStack deposited = inventory.getItem(slot.slot());
            if (GuiDepositRequirements.accepts(service, deposited, slot.requirement())) {
                continue;
            }
            inventory.setItem(slot.slot(), depositPlaceholder(slot));
        }
    }

    private void handleDepositClick(Player player, InventoryClickEvent event, GuiDepositSlot binding) {
        CustomItemService service = customItemService();
        ClickType click = event.getClick();
        Inventory top = this.bound;
        ItemStack current = top.getItem(binding.slot());
        boolean hasMaterial = GuiDepositRequirements.accepts(service, current, binding.requirement());
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();

        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (hasMaterial) {
                top.setItem(binding.slot(), null);
                giveOrDrop(player, current);
                NetworkSoundCue.UI_MENU_BACK.play(player);
            }
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        if (!hasMaterial && !cursorEmpty) {
            if (!GuiDepositRequirements.accepts(service, cursor, binding.requirement())) {
                player.sendMessage(depositRejectedMessage(binding));
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                return;
            }
            ItemStack placed = cursor.clone();
            placed.setAmount(Math.min(placed.getAmount(), binding.requirement().minimumAmount()));
            top.setItem(binding.slot(), placed);
            consumeCursor(player, cursor, placed.getAmount());
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        } else if (hasMaterial && cursorEmpty) {
            player.setItemOnCursor(current);
            top.setItem(binding.slot(), null);
            NetworkSoundCue.UI_MENU_BACK.play(player);
        }
    }

    private GuiDepositSlot depositAt(int rawSlot) {
        for (GuiDepositSlot slot : depositSlots()) {
            if (slot.slot() == rawSlot) {
                return slot;
            }
        }
        return null;
    }

    private Set<Integer> depositSlotSet() {
        Set<Integer> slots = new HashSet<>();
        for (GuiDepositSlot slot : depositSlots()) {
            slots.add(slot.slot());
        }
        return slots;
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private static void consumeCursor(Player player, ItemStack cursor, int amount) {
        int remaining = cursor.getAmount() - amount;
        if (remaining <= 0) {
            player.setItemOnCursor(null);
        } else {
            ItemStack updated = cursor.clone();
            updated.setAmount(remaining);
            player.setItemOnCursor(updated);
        }
    }

    private static void decrementClicked(InventoryClickEvent event, int amount) {
        GuiClickInventory.consumeClickedStack(event, amount);
    }
}
