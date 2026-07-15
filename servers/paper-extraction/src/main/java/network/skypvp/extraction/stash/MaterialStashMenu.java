package network.skypvp.extraction.stash;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.MaterialStashHelper;
import network.skypvp.paper.gui.GuiBulkStorageFrame;
import network.skypvp.paper.gui.GuiBulkStorageMenu;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** Material stash GUI via {@link GuiBulkStorageMenu}. */
public final class MaterialStashMenu extends GuiBulkStorageMenu {

    private final MaterialStashHolder holder;
    private final MaterialStashGuiService service;
    private final CustomItemService itemService;
    private final CoreHotbarService hotbarService;

    public MaterialStashMenu(
            MaterialStashHolder holder,
            MaterialStashGuiService service,
            CustomItemService itemService,
            CoreHotbarService hotbarService
    ) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.service = Objects.requireNonNull(service, "service");
        this.itemService = itemService;
        this.hotbarService = hotbarService;
    }

    public MaterialStashHolder holder() {
        return holder;
    }

    @Override
    public Component title() {
        return Component.text("Material Stash", NamedTextColor.GOLD);
    }

    @Override
    public int size() {
        return MaterialStashLayout.INVENTORY_SIZE;
    }

    @Override
    protected MaterialStashHolder session() {
        return holder;
    }

    @Override
    protected int[] contentSlots() {
        return MaterialStashLayout.contentSlotArray();
    }

    @Override
    protected int contentIndex(int rawSlot) {
        return MaterialStashLayout.contentIndex(rawSlot);
    }

    @Override
    protected boolean isContentSlot(int rawSlot) {
        return MaterialStashLayout.isContentSlot(rawSlot);
    }

    @Override
    protected ItemStack displayStack(int contentIndex, ItemStack stored) {
        return MaterialStashLayout.forDisplay(stored);
    }

    @Override
    protected void buildChrome(GuiBulkStorageFrame frame, Player viewer) {
        ItemStack filler = GuiItems.named(Material.GRAY_STAINED_GLASS_PANE, " ", java.util.List.of());
        frame.filler(filler);
        frame.decoration(1, filler);
        frame.decoration(2, filler);
        frame.decoration(3, filler);
        frame.decoration(5, filler);
        frame.decoration(6, filler);
        frame.button(MaterialStashLayout.CLOSE_SLOT, GuiButtonLibrary.close("Close stash"), GuiClickContext::close);
        frame.button(MaterialStashLayout.BACK_SLOT, GuiButtonLibrary.back("Return"), ctx -> service.handleBack(ctx.viewer(), holder));
        frame.decoration(MaterialStashLayout.INFO_SLOT, MaterialStashLayout.infoItem(holder));
        frame.button(MaterialStashLayout.UPGRADE_SLOT, MaterialStashLayout.upgradeItem(holder),
                ctx -> service.promptUpgrade(ctx.viewer(), holder));
        for (int index = 0; index < contentSlots().length; index++) {
            if (!holder.isSlotUnlocked(index)) {
                frame.decoration(contentSlots()[index], MaterialStashLayout.lockedSlotItem(holder, index));
            }
        }
    }

    @Override
    protected boolean acceptsDeposit(ItemStack stack) {
        return service.acceptsDeposit(stack);
    }

    @Override
    protected boolean isBlockedServerItem(ItemStack stack) {
        return hotbarService != null && hotbarService.isServerItem(stack);
    }

    @Override
    protected int depositShiftClicked(Player player, ItemStack stack) {
        return service.depositShiftClickedStack(player, holder, stack);
    }

    @Override
    protected int depositToContentIndex(Player player, int contentIndex, ItemStack stack) {
        return service.depositToContentIndex(holder, contentIndex, stack);
    }

    @Override
    protected int depositAllMatchingShiftStacks(Player player, ItemStack reference) {
        return service.depositAllMatchingFromInventory(player, holder, reference);
    }

    @Override
    protected void depositShiftClickedRevert(Player player, ItemStack referenceStack, int amount) {
        service.revertShiftDeposit(holder, referenceStack, amount);
    }

    @Override
    protected void persist(Player viewer) {
        service.persistHolder(holder);
    }

    @Override
    protected void handleContentClick(GuiClickContext context, int contentIndex, int rawSlot) {
        Player player = context.viewer();
        ClickType click = clickType(context);
        if (click == ClickType.SWAP_OFFHAND || click == ClickType.NUMBER_KEY || click == ClickType.DOUBLE_CLICK) {
            return;
        }
        ItemStack stashStack = holder.get(contentIndex);
        boolean cursorEmpty = cursorEmpty(context);

        if (isShiftClick(context) && cursorEmpty) {
            if (stashStack == null || stashStack.getType().isAir()) {
                return;
            }
            int moved = MaterialStashWithdrawHelper.withdrawAllToInventory(
                    itemService, player, holder, contentIndex);
            if (moved <= 0) {
                player.sendMessage(Component.text("Your inventory is full.", NamedTextColor.RED));
            } else {
                persist(player);
            }
            refresh(player);
            return;
        }

        if (cursorEmpty) {
            if (stashStack == null || stashStack.getType().isAir()) {
                return;
            }
            int stored = MaterialStashStackAmount.read(stashStack);
            int limit = MaterialStashWithdrawHelper.playerStackLimit(stashStack);
            int requested = click.isRightClick()
                    ? Math.min(limit, (stored + 1) / 2)
                    : Math.min(limit, stored);
            int moved = MaterialStashWithdrawHelper.withdrawToCursor(
                    itemService, player, holder, contentIndex, requested);
            if (moved <= 0 && !click.isRightClick()) {
                player.sendMessage(Component.text("Your cursor is full.", NamedTextColor.RED));
            } else if (moved > 0) {
                persist(player);
            }
            refresh(player);
            return;
        }

        ItemStack cursor = context.event().getCursor();
        if (!service.acceptsDeposit(cursor)) {
            player.sendMessage(Component.text("Only crafting materials can be stored here.", NamedTextColor.RED));
            return;
        }
        if (stashStack != null && !stashStack.getType().isAir()
                && !MaterialStashHelper.sameMaterial(itemService, stashStack, cursor)) {
            player.sendMessage(Component.text("That slot already holds a different material.", NamedTextColor.RED));
            return;
        }
        int deposited = MaterialStashWithdrawHelper.depositFromCursor(
                itemService, player, holder, contentIndex, rawSlot, holder.maxCapacity());
        if (deposited <= 0) {
            player.sendMessage(Component.text("Your material stash is at capacity.", NamedTextColor.RED));
        } else {
            persist(player);
        }
        refresh(player);
    }
}
