package network.skypvp.paper.gui;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.item.api.CustomItemTypeId;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Reusable item-editing station built entirely on the core GUI framework. A single "anchor" item is deposited into a
 * dedicated slot; modifier {@link GuiWorkstationSocket}s install into / remove from that anchor. The framework owns
 * <em>every</em> inventory, cursor, shift-click, drag, close, and re-render interaction, so game modes never hand-roll
 * raw inventory code (and can never leak or dupe player items).
 *
 * <p>Subclasses provide only:</p>
 * <ul>
 *   <li>{@link #title()} / {@link #size()} — the window.</li>
 *   <li>{@link #anchorSlot()} / {@link #acceptsAnchor(ItemStack)} / {@link #anchorPlaceholder()} — the deposit slot.
 *       Prefer {@link #acceptsCustomItem(CustomItemService, ItemStack, GuiCustomItemRequirement)} when the anchor is a
 *       typed custom item.</li>
 *   <li>{@link #buildFrame} — a declarative description of decorations, buttons, and sockets for the current anchor.</li>
 * </ul>
 *
 * <p>Guarantees: the deposited anchor is never painted over, is atomically placed/removed, and is always returned to
 * the player on close. Sockets consume exactly one offered item on a successful install and hand back an item on a
 * successful removal.</p>
 */
public abstract class GuiWorkstationMenu implements GuiMenu {

    private Inventory bound;
    private GuiWorkstationFrame frame;

    // ---- Subclass contract --------------------------------------------------------------------------------

    /** Raw slot index that holds the deposited anchor item. */
    protected abstract int anchorSlot();

    /** Whether the given stack is a valid anchor (e.g. the armor this station edits). */
    protected abstract boolean acceptsAnchor(ItemStack stack);

    /** Icon shown in the anchor slot while it is empty. */
    protected abstract ItemStack anchorPlaceholder();

    /** Declare decorations/buttons/sockets for the current {@code anchor} (null when the station is empty). */
    protected abstract void buildFrame(GuiWorkstationFrame frame, Player viewer, ItemStack anchor);

    /** Hook fired after the anchor item is deposited, removed, or otherwise changed. */
    protected void onAnchorChanged(Player viewer, ItemStack anchor) {
    }

    /** Message shown when the player tries to deposit a non-anchor item. */
    protected Component anchorRejectedMessage() {
        return Component.text("That item can't go there.", NamedTextColor.RED);
    }

    /**
     * Whether {@code stack} satisfies a {@link GuiCustomItemRequirement}. Use from {@link #acceptsAnchor(ItemStack)}
     * when the workstation anchor is a custom item type (optionally narrowed by payload).
     */
    protected static boolean acceptsCustomItem(
            CustomItemService service,
            ItemStack stack,
            GuiCustomItemRequirement requirement
    ) {
        return GuiDepositRequirements.accepts(service, stack, requirement);
    }

    /** Whether {@code stack} is any instance of {@code typeId} (payload ignored). */
    protected static boolean acceptsCustomItem(CustomItemService service, ItemStack stack, CustomItemTypeId typeId) {
        return GuiDepositRequirements.accepts(service, stack, GuiCustomItemRequirement.require(typeId));
    }

    // ---- GuiMenu wiring -----------------------------------------------------------------------------------

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
        ItemStack anchor = inventory.getItem(anchorSlot());
        boolean hasAnchor = acceptsAnchor(anchor);

        GuiWorkstationFrame built = new GuiWorkstationFrame(inventory.getSize());
        buildFrame(built, viewer, hasAnchor ? anchor : null);
        this.frame = built;

        ItemStack filler = built.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot == anchorSlot()) {
                continue;
            }
            inventory.setItem(slot, filler);
        }
        for (Map.Entry<Integer, GuiWorkstationFrame.Slot> entry : built.slots().entrySet()) {
            if (entry.getKey() == anchorSlot()) {
                continue;
            }
            inventory.setItem(entry.getKey(), entry.getValue().icon());
        }
        if (!hasAnchor) {
            inventory.setItem(anchorSlot(), anchorPlaceholder());
        }
    }

    @Override
    public final void onClick(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        int rawSlot = event.getRawSlot();
        if (rawSlot == anchorSlot()) {
            handleAnchorClick(context);
            return;
        }
        GuiWorkstationFrame.Slot slot = this.frame == null ? null : this.frame.slot(rawSlot);
        if (slot == null) {
            return;
        }
        switch (slot.kind()) {
            case BUTTON -> {
                if (slot.onClick() != null) {
                    slot.onClick().accept(context);
                }
            }
            case SOCKET -> handleSocketClick(context.viewer(), event, slot.socket());
            case DECORATION -> {
            }
        }
    }

    @Override
    public final void onShiftInsert(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        Player viewer = context.viewer();
        ItemStack clicked = event.getCurrentItem();
        if (isEmpty(clicked)) {
            return;
        }
        Inventory top = this.bound;
        if (top == null) {
            return;
        }
        if (acceptsAnchor(clicked) && !acceptsAnchor(top.getItem(anchorSlot()))) {
            ItemStack placed = clicked.clone();
            placed.setAmount(1);
            top.setItem(anchorSlot(), placed);
            decrement(event, 1);
            afterAnchorChanged(viewer);
            return;
        }
        if (this.frame == null) {
            return;
        }
        for (GuiWorkstationFrame.Slot slot : this.frame.slots().values()) {
            if (slot.kind() != GuiWorkstationFrame.Kind.SOCKET) {
                continue;
            }
            GuiWorkstationSocket socket = slot.socket();
            if (socket.filled() || !socket.installable() || !socket.accepts(clicked)) {
                continue;
            }
            ItemStack offered = clicked.clone();
            offered.setAmount(1);
            GuiWorkstationSocket.InstallOutcome outcome = socket.install(offered);
            if (!outcome.accepted()) {
                failure(viewer, outcome.message());
                return;
            }
            top.setItem(anchorSlot(), outcome.updatedAnchor());
            decrement(event, 1);
            renderNow(viewer);
            NetworkSoundCue.UI_BUTTON_SUCCESS.play(viewer);
            return;
        }
    }

    @Override
    public final void onClose(Player viewer) {
        if (this.bound == null || viewer == null) {
            return;
        }
        ItemStack anchor = this.bound.getItem(anchorSlot());
        if (!acceptsAnchor(anchor)) {
            return;
        }
        this.bound.setItem(anchorSlot(), null);
        giveOrDrop(viewer, anchor);
    }

    // ---- Internals ----------------------------------------------------------------------------------------

    private void handleAnchorClick(GuiClickContext context) {
        InventoryClickEvent event = context.event();
        Player viewer = context.viewer();
        ClickType click = event.getClick();
        Inventory top = this.bound;
        if (top == null) {
            return;
        }
        ItemStack current = top.getItem(anchorSlot());
        boolean hasAnchor = acceptsAnchor(current);

        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (hasAnchor) {
                top.setItem(anchorSlot(), null);
                giveOrDrop(viewer, current);
                afterAnchorChanged(viewer);
            }
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }

        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = isEmpty(cursor);
        if (!hasAnchor && !cursorEmpty) {
            if (!acceptsAnchor(cursor)) {
                viewer.sendMessage(anchorRejectedMessage());
                NetworkSoundCue.UI_BUTTON_FAILURE.play(viewer);
                return;
            }
            ItemStack placed = cursor.clone();
            placed.setAmount(1);
            top.setItem(anchorSlot(), placed);
            consumeCursor(viewer, cursor, 1);
            afterAnchorChanged(viewer);
        } else if (hasAnchor && cursorEmpty) {
            viewer.setItemOnCursor(current);
            top.setItem(anchorSlot(), null);
            afterAnchorChanged(viewer);
        }
    }

    private void handleSocketClick(Player viewer, InventoryClickEvent event, GuiWorkstationSocket socket) {
        Inventory top = this.bound;
        if (top == null) {
            return;
        }
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = isEmpty(cursor);
        if (!cursorEmpty && socket.installable() && !socket.filled() && socket.accepts(cursor)) {
            ItemStack offered = cursor.clone();
            offered.setAmount(1);
            GuiWorkstationSocket.InstallOutcome outcome = socket.install(offered);
            if (!outcome.accepted()) {
                failure(viewer, outcome.message());
                return;
            }
            top.setItem(anchorSlot(), outcome.updatedAnchor());
            consumeCursor(viewer, cursor, 1);
            renderNow(viewer);
            NetworkSoundCue.UI_BUTTON_SUCCESS.play(viewer);
            return;
        }
        if (cursorEmpty && socket.removable() && socket.filled()) {
            GuiWorkstationSocket.RemoveOutcome outcome = socket.remove();
            if (!outcome.success()) {
                failure(viewer, outcome.message());
                return;
            }
            top.setItem(anchorSlot(), outcome.updatedAnchor());
            giveOrDrop(viewer, outcome.returned());
            renderNow(viewer);
            NetworkSoundCue.UI_MENU_BACK.play(viewer);
        }
    }

    private void afterAnchorChanged(Player viewer) {
        renderNow(viewer);
        onAnchorChanged(viewer, this.bound == null ? null : this.bound.getItem(anchorSlot()));
        NetworkSoundCue.UI_BUTTON_CLICK.play(viewer);
    }

    private void renderNow(Player viewer) {
        if (this.bound != null) {
            render(viewer, this.bound);
        }
        viewer.updateInventory();
    }

    private void failure(Player viewer, String message) {
        if (message != null && !message.isBlank()) {
            viewer.sendMessage(Component.text(message, NamedTextColor.RED));
        }
        NetworkSoundCue.UI_BUTTON_FAILURE.play(viewer);
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    private static void consumeCursor(Player viewer, ItemStack cursor, int amount) {
        if (cursor == null) {
            return;
        }
        int remaining = cursor.getAmount() - amount;
        if (remaining <= 0) {
            viewer.setItemOnCursor(null);
        } else {
            ItemStack updated = cursor.clone();
            updated.setAmount(remaining);
            viewer.setItemOnCursor(updated);
        }
        viewer.updateInventory();
    }

    private static void decrement(InventoryClickEvent event, int amount) {
        GuiClickInventory.consumeClickedStack(event, amount);
    }

    private static void giveOrDrop(Player viewer, ItemStack item) {
        if (isEmpty(item)) {
            return;
        }
        viewer.getInventory().addItem(item).values().forEach(leftover ->
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), leftover));
    }
}
