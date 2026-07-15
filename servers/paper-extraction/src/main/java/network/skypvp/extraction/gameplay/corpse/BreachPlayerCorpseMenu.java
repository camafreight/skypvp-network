package network.skypvp.extraction.gameplay.corpse;

import java.util.Objects;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.gui.GuiBulkStorageFrame;
import network.skypvp.paper.gui.GuiLootContainerMenu;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Shared live corpse loot via {@link GuiLootContainerMenu}. */
public final class BreachPlayerCorpseMenu extends GuiLootContainerMenu {

    private static final int[] ALL_SLOTS = IntStream.range(0, BreachPlayerCorpseLayout.INVENTORY_SIZE).toArray();

    private final BreachPlayerCorpseService.BreachPlayerCorpseState state;
    private final BreachPlayerCorpseService service;
    private final CoreHotbarService hotbarService;

    public BreachPlayerCorpseMenu(
            BreachPlayerCorpseService.BreachPlayerCorpseState state,
            BreachPlayerCorpseService service,
            CoreHotbarService hotbarService
    ) {
        this.state = Objects.requireNonNull(state, "state");
        this.service = Objects.requireNonNull(service, "service");
        this.hotbarService = hotbarService;
    }

    @Override
    public Component title() {
        return ExtractionTexts.miniMessage(null, "extraction.gui.corpse.title", state.ownerName());
    }

    @Override
    public int size() {
        return BreachPlayerCorpseLayout.INVENTORY_SIZE;
    }

    @Override
    protected int[] lootSlots() {
        return ALL_SLOTS;
    }

    @Override
    protected void buildChrome(GuiBulkStorageFrame frame, Player viewer) {
        // Full-screen loot; no chrome.
    }

    @Override
    public boolean isBlockedPlayerItem(ItemStack stack) {
        return hotbarService != null && hotbarService.isServerItem(stack)
                || network.skypvp.extraction.backpack.BackpackService.isPlaceholderItem(stack);
    }

    @Override
    protected void renderLoot(Player viewer, Inventory inventory) {
        if (BreachPlayerCorpseLayout.isEmptyInventory(inventory)) {
            BreachPlayerCorpseLayout.fill(inventory, state.loot());
        }
    }

    @Override
    protected void syncFromInventory(Inventory inventory) {
        BreachPlayerCorpseLayout.syncFromInventory(inventory, state.loot());
    }

    @Override
    protected void handleContainerClosed(Player viewer) {
        service.afterCorpseMenuClosed(state);
    }
}
