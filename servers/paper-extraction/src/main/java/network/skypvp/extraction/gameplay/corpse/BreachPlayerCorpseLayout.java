package network.skypvp.extraction.gameplay.corpse;

import java.util.Collection;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.paper.service.PlayerInventoryManager;
import network.skypvp.extraction.text.ExtractionTexts;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class BreachPlayerCorpseLayout {

    public static final int INVENTORY_SIZE = 54;

    private BreachPlayerCorpseLayout() {
    }

    public static Inventory createInventory(BreachPlayerCorpseHolder holder, String ownerName, ItemStack[] loot, org.bukkit.entity.Player viewer) {
        Component title = ExtractionTexts.miniMessage(viewer, "extraction.gui.corpse.title", ownerName);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        holder.bindInventory(inventory);
        fill(inventory, loot);
        return inventory;
    }

    public static void fill(Inventory inventory, ItemStack[] loot) {
        inventory.clear();
        if (loot == null) {
            return;
        }
        for (int slot = 0; slot < Math.min(loot.length, INVENTORY_SIZE); slot++) {
            ItemStack item = loot[slot];
            if (item != null && !item.getType().isAir()) {
                inventory.setItem(slot, item.clone());
            }
        }
    }

    public static void syncFromInventory(Inventory inventory, ItemStack[] loot) {
        for (int slot = 0; slot < Math.min(loot.length, INVENTORY_SIZE); slot++) {
            ItemStack item = inventory.getItem(slot);
            loot[slot] = item == null || item.getType().isAir() ? null : item.clone();
        }
    }

    public static boolean isEmpty(ItemStack[] loot) {
        if (loot == null) {
            return true;
        }
        for (ItemStack item : loot) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    public static boolean isEmptyInventory(Inventory inventory) {
        if (inventory == null) {
            return true;
        }
        for (int slot = 0; slot < Math.min(inventory.getSize(), INVENTORY_SIZE); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    public static ItemStack[] captureLootFromPlayer(
            Player player,
            PlayerInventoryManager inventoryManager,
            CoreHotbarService hotbarService,
            Collection<ItemStack> deathDrops
    ) {
        ItemStack[] loot = new ItemStack[INVENTORY_SIZE];
        if (player != null) {
            captureLiveInventory(player, hotbarService, loot);
        }
        if (deathDrops != null) {
            mergeCollectionIntoLoot(loot, deathDrops, hotbarService);
        }
        if (isEmpty(loot) && player != null && inventoryManager != null) {
            mapEncodedSlots(inventoryManager.captureRaidInventory(player), loot);
        }
        return loot;
    }

    private static void captureLiveInventory(Player player, CoreHotbarService hotbarService, ItemStack[] loot) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            mergeItemIntoLoot(loot, inventory.getItem(slot), hotbarService);
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int index = 0; index < armor.length; index++) {
            int targetSlot = mapRaidSlotToCorpseSlot(100 + index);
            mergeItemIntoSlot(loot, targetSlot, armor[index], hotbarService);
        }
        mergeItemIntoSlot(loot, mapRaidSlotToCorpseSlot(104), inventory.getItemInOffHand(), hotbarService);
    }

    private static void mergeCollectionIntoLoot(
            ItemStack[] loot,
            Collection<ItemStack> items,
            CoreHotbarService hotbarService
    ) {
        for (ItemStack item : items) {
            mergeItemIntoLoot(loot, item, hotbarService);
        }
    }

    private static void mergeItemIntoLoot(ItemStack[] loot, ItemStack item, CoreHotbarService hotbarService) {
        if (item == null || item.getType().isAir() || isIgnoredItem(item, hotbarService)) {
            return;
        }
        for (int slot = 0; slot < loot.length; slot++) {
            if (loot[slot] == null || loot[slot].getType().isAir()) {
                loot[slot] = item.clone();
                return;
            }
        }
    }

    private static void mergeItemIntoSlot(
            ItemStack[] loot,
            int targetSlot,
            ItemStack item,
            CoreHotbarService hotbarService
    ) {
        if (targetSlot < 0 || targetSlot >= loot.length) {
            return;
        }
        if (item == null || item.getType().isAir() || isIgnoredItem(item, hotbarService)) {
            return;
        }
        if (loot[targetSlot] == null || loot[targetSlot].getType().isAir()) {
            loot[targetSlot] = item.clone();
        }
    }

    private static boolean isIgnoredItem(ItemStack item, CoreHotbarService hotbarService) {
        return hotbarService != null && hotbarService.isServerItem(item);
    }

    private static void mapEncodedSlots(Map<Integer, String> encoded, ItemStack[] loot) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        encoded.forEach((slot, payload) -> {
            int targetSlot = mapRaidSlotToCorpseSlot(slot);
            if (targetSlot < 0 || targetSlot >= loot.length) {
                return;
            }
            if (loot[targetSlot] != null && !loot[targetSlot].getType().isAir()) {
                return;
            }
            try {
                loot[targetSlot] = network.skypvp.paper.library.ItemStackCodec.decode(payload);
            } catch (RuntimeException ignored) {
            }
        });
    }

    private static int mapRaidSlotToCorpseSlot(int raidSlot) {
        if (raidSlot >= 0 && raidSlot < 36) {
            return raidSlot;
        }
        return switch (raidSlot) {
            case 100 -> 36;
            case 101 -> 37;
            case 102 -> 38;
            case 103 -> 39;
            case 104 -> 40;
            default -> -1;
        };
    }
}
