package network.skypvp.extraction.gameplay.loot;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.gui.GuiBulkStorageFrame;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiLootContainerMenu;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Breach loot chest GUI via {@link GuiLootContainerMenu}. */
public final class BreachLootChestMenu extends GuiLootContainerMenu {

    private final JavaPlugin plugin;
    private final BreachLootChestGuiService service;
    private final BreachLootChestState state;
    private final Location chestLocation;
    private final CoreHotbarService hotbarService;

    private final String tier;

    public BreachLootChestMenu(
            JavaPlugin plugin,
            BreachLootChestGuiService service,
            BreachLootChestState state,
            Location chestLocation,
            CoreHotbarService hotbarService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
        this.state = Objects.requireNonNull(state, "state");
        this.chestLocation = Objects.requireNonNull(chestLocation, "chestLocation");
        this.hotbarService = hotbarService;
        this.tier = state.tier();
    }

    @Override
    public Component title() {
        return ExtractionTexts.miniMessage(null, "extraction.gui.loot_chest.title", tier);
    }

    @Override
    public int size() {
        return BreachLootChestLayout.INVENTORY_SIZE;
    }

    @Override
    protected int[] lootSlots() {
        return BreachLootChestLayout.LOOT_SLOTS;
    }

    @Override
    public boolean isBlockedPlayerItem(ItemStack stack) {
        return hotbarService != null && hotbarService.isServerItem(stack);
    }

    @Override
    protected void buildChrome(GuiBulkStorageFrame frame, Player viewer) {
        frame.filler(BreachLootChestLayout.fillerPane(plugin));
        ItemStack close = BreachLootChestLayout.controlButton(
                plugin,
                Material.RED_WOOL,
                ExtractionTexts.text(viewer, "extraction.gui.loot_chest.close"),
                BreachLootChestLayout.ACTION_CLOSE
        );
        ItemStack lootAll = BreachLootChestLayout.controlButton(
                plugin,
                Material.LIME_WOOL,
                ExtractionTexts.text(viewer, "extraction.gui.loot_chest.loot_all"),
                BreachLootChestLayout.ACTION_LOOT_ALL
        );
        for (int slot : BreachLootChestLayout.CLOSE_SLOTS) {
            frame.button(slot, close.clone(), GuiClickContext::close);
        }
        for (int slot : BreachLootChestLayout.LOOT_ALL_SLOTS) {
            frame.button(slot, lootAll.clone(), ctx -> {
                service.lootAll(ctx.viewer(), boundInventory(), state, chestLocation);
                ctx.refresh();
            });
        }
    }

    @Override
    protected void renderLoot(Player viewer, Inventory inventory) {
        BreachLootChestLayout.fillLoot(inventory, state);
    }

    @Override
    protected void syncFromInventory(Inventory inventory) {
        BreachLootChestLayout.syncLootFromInventory(inventory, state);
    }

    @Override
    protected void handleContainerClosed(Player viewer) {
        service.handleClose(boundInventory(), chestLocation);
    }

    public BreachLootChestState state() {
        return state;
    }

    public Location chestLocation() {
        return chestLocation;
    }
}
