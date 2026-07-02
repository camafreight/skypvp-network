package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.loot.BreachLootChestDisplayService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestGuiService;
import network.skypvp.extraction.gameplay.loot.BreachLootChestLayout;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.NpcLibrary;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class BreachLootChestListener implements Listener {

    private final BreachEngine engine;
    private final BreachLootChestGuiService guiService;
    private final CoreHotbarService hotbarService;
    private final NpcLibrary npcLibrary;
    private final NamespacedKey actionKey;
    private final NamespacedKey blockPropTypeKey;

    public BreachLootChestListener(
            BreachEngine engine,
            BreachLootChestGuiService guiService,
            PaperCorePlugin core,
            org.bukkit.plugin.java.JavaPlugin plugin
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.guiService = Objects.requireNonNull(guiService, "guiService");
        this.hotbarService = Objects.requireNonNull(core, "core").coreHotbarService();
        this.npcLibrary = Objects.requireNonNull(core, "core").npcLibrary();
        this.actionKey = new NamespacedKey(plugin, BreachLootChestLayout.ACTION_KEY);
        this.blockPropTypeKey = this.npcLibrary.blockPropTypeKey();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction)) {
            return;
        }
        Entity clicked = event.getRightClicked();
        String propType = clicked.getPersistentDataContainer().get(this.blockPropTypeKey, PersistentDataType.STRING);
        if (!BreachLootChestDisplayService.PROP_TYPE.equals(propType)) {
            return;
        }
        this.npcLibrary.readBlockPropAnchor(clicked).ifPresent(location -> {
            if (!isActiveBreachChest(event.getPlayer(), location)) {
                return;
            }
            event.setCancelled(true);
            this.guiService.open(event.getPlayer(), location);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }
        if (!this.isActiveBreachChest(event.getPlayer(), block.getLocation())) {
            return;
        }
        event.setCancelled(true);
        this.guiService.open(event.getPlayer(), block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof network.skypvp.extraction.gameplay.loot.BreachLootChestHolder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        if (this.isBlockedServerItem(event.getCurrentItem()) || this.isBlockedServerItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < top.getSize()) {
            if (BreachLootChestLayout.isCloseSlot(rawSlot)) {
                event.setCancelled(true);
                player.closeInventory();
                return;
            }
            if (BreachLootChestLayout.isLootAllSlot(rawSlot)) {
                event.setCancelled(true);
                guiService.lootAll(player, top);
                return;
            }
            if (!BreachLootChestLayout.isLootSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }

        ItemStack current = event.getCurrentItem();
        if (current != null && current.hasItemMeta()) {
            String action = current.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action != null) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof network.skypvp.extraction.gameplay.loot.BreachLootChestHolder)) {
            return;
        }
        for (ItemStack item : event.getNewItems().values()) {
            if (this.isBlockedServerItem(item)) {
                event.setCancelled(true);
                return;
            }
        }
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize() && !BreachLootChestLayout.isLootSlot(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory.getHolder() instanceof network.skypvp.extraction.gameplay.loot.BreachLootChestHolder) {
            guiService.handleClose(inventory);
        }
    }

    private boolean isActiveBreachChest(Player player, org.bukkit.Location location) {
        if (this.engine.isSpectating(player)) {
            return false;
        }
        return engine.instanceFor(player)
                .filter(instance -> instance.state() == BreachState.ACTIVE)
                .filter(instance -> instance.world() != null && instance.world().equals(location.getWorld()))
                .isPresent()
                && guiService.registry().find(location.getWorld(), location)
                        .filter(state -> !state.isEmpty())
                        .isPresent();
    }

    private boolean isBlockedServerItem(ItemStack item) {
        return this.hotbarService.isServerItem(item);
    }
}
