package network.skypvp.extraction.gameplay.loot;

import java.util.HashSet;
import java.util.Set;
import network.skypvp.extraction.config.BreachConfigService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.text.ExtractionTexts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachLootChestLayout {

    public static final int INVENTORY_SIZE = 54;
    public static final int[] LOOT_SLOTS = buildLootSlots();
    public static final int[] CLOSE_SLOTS = {45, 46, 47};
    public static final int[] LOOT_ALL_SLOTS = {51, 52, 53};
    public static final String ACTION_KEY = "breach_chest_action";
    public static final String ACTION_CLOSE = "CLOSE";
    public static final String ACTION_LOOT_ALL = "LOOT_ALL";

    private static final Set<Integer> LOOT_SLOT_SET = box(LOOT_SLOTS);

    private BreachLootChestLayout() {
    }

    public static boolean isLootSlot(int slot) {
        return LOOT_SLOT_SET.contains(slot);
    }

    public static boolean isCloseSlot(int slot) {
        return slot == CLOSE_SLOTS[0] || slot == CLOSE_SLOTS[1] || slot == CLOSE_SLOTS[2];
    }

    public static boolean isLootAllSlot(int slot) {
        return slot == LOOT_ALL_SLOTS[0] || slot == LOOT_ALL_SLOTS[1] || slot == LOOT_ALL_SLOTS[2];
    }

    public static Inventory createInventory(
            JavaPlugin plugin,
            BreachLootChestHolder holder,
            BreachLootChestState state,
            BreachConfigService.LootChestFx fx,
            org.bukkit.entity.Player viewer
    ) {
        Component title = ExtractionTexts.miniMessage(viewer, "extraction.gui.loot_chest.title", state.tier());
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        fillFrame(inventory, plugin);
        fillControls(inventory, plugin, viewer);
        fillLoot(inventory, state);
        return inventory;
    }

    public static void fillLoot(Inventory inventory, BreachLootChestState state) {
        ItemStack[] loot = state.lootSnapshot();
        for (int i = 0; i < LOOT_SLOTS.length && i < loot.length; i++) {
            inventory.setItem(LOOT_SLOTS[i], cloneOrNull(loot[i]));
        }
    }

    public static void syncLootFromInventory(Inventory inventory, BreachLootChestState state) {
        ItemStack[] loot = new ItemStack[LOOT_SLOTS.length];
        for (int i = 0; i < LOOT_SLOTS.length; i++) {
            loot[i] = cloneOrNull(inventory.getItem(LOOT_SLOTS[i]));
        }
        state.replaceLoot(loot);
    }

    private static void fillFrame(Inventory inventory, JavaPlugin plugin) {
        ItemStack filler = fillerPane(plugin);
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (isLootSlot(slot) || isCloseSlot(slot) || isLootAllSlot(slot)) {
                continue;
            }
            if (slot >= 48 && slot <= 50) {
                inventory.setItem(slot, filler);
            } else if (slot < 9 || slot >= 36) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private static void fillControls(Inventory inventory, JavaPlugin plugin, org.bukkit.entity.Player viewer) {
        ItemStack close = controlButton(
                plugin,
                Material.RED_WOOL,
                ExtractionTexts.text(viewer, "extraction.gui.loot_chest.close"),
                ACTION_CLOSE
        );
        ItemStack lootAll = controlButton(
                plugin,
                Material.LIME_WOOL,
                ExtractionTexts.text(viewer, "extraction.gui.loot_chest.loot_all"),
                ACTION_LOOT_ALL
        );
        for (int slot : CLOSE_SLOTS) {
            inventory.setItem(slot, close.clone());
        }
        for (int slot : LOOT_ALL_SLOTS) {
            inventory.setItem(slot, lootAll.clone());
        }
    }

    private static ItemStack controlButton(JavaPlugin plugin, Material material, String label, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(label, NamedTextColor.WHITE));
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, ACTION_KEY),
                    PersistentDataType.STRING,
                    action
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack fillerPane(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static int[] buildLootSlots() {
        int[] slots = new int[21];
        int index = 0;
        for (int row = 1; row <= 3; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[index++] = row * 9 + col;
            }
        }
        return slots;
    }

    private static Set<Integer> box(int[] values) {
        Set<Integer> set = new HashSet<>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
